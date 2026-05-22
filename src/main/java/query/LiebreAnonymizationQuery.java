package query;

import common.util.Util;
import component.operator.Operator;
import component.sink.Sink;
import component.source.Source;
import component.source.SourceFunction;
import event.EventFactory;
import event.GenericEvent;
import mappers.QueryRepresentation;
import query.utils.MovingAggregateMap;
import query.utils.OperatorUtils;
import query.utils.RIRMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static query.utils.OperatorUtils.*;

// Class that translates a high-level QueryRepresentation (phenotype) into an executable Liebre query and
// processes an input stream to produce a modified stream of events
public class LiebreAnonymizationQuery {

    private static final Logger logger = LoggerFactory.getLogger(LiebreAnonymizationQuery.class);
    private static final long DEFAULT_RANDOM_SEED = 0x5EED_DA7A_171EB2EL;
    private final Random random;

    public LiebreAnonymizationQuery() {
        this.random = new Random(DEFAULT_RANDOM_SEED);
    }

    public List<GenericEvent> processAnonymizationQuery(QueryRepresentation representation, String inputFile, String keyColumn) throws IOException {

        final List<GenericEvent> collectedEvents = Collections.synchronizedList(new ArrayList<>());

        // Read all lines from the input CSV file into memory
        List<String> linesFromCsv;
        try (InputStream is = LiebreAnonymizationQuery.class.getClassLoader().getResourceAsStream(inputFile)) {
            if (is == null) {
                logger.error("Input CSV resource not found: {}", inputFile);
                throw new IOException("Resource not found: " + inputFile);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                linesFromCsv = reader.lines().collect(Collectors.toList());
            }
        }

        String headerLine = linesFromCsv.get(0);
        String[] headers = Arrays.stream(headerLine.split(",")).map(String::trim).toArray(String[]::new);

        Query query = new Query();
        SourceFunction<String> collectionSource = createCollectionSource(linesFromCsv);

        // Define Source and CSV Reader (fixed part of the pipeline)
        long[] idCounter = {0};
        Source<String> source = query.addBaseSource("input-source", collectionSource);
        Operator<String, GenericEvent> reader = query.addMapOperator(
                "csv-reader",
                line -> {
                    if (line.equals(headerLine)) return null;
                    return EventFactory.createEventFromLine(line, headers, keyColumn, idCounter[0]++);
                }
        );
        query.connect(source, reader);

        // Build the operator chain by iterating through the representation's nodes
        Operator<?, GenericEvent> lastOperatorInChain = reader;
        int opCounter = 0;

        // Loop through each operator node in the phenotype representation
        for (QueryRepresentation.OperatorNode node: representation.operators()) {
            // Create a unique id for the Liebre operator
            String operatorId = node.type().name().toLowerCase() + "-" + opCounter++;

            // Build the correct Liebre operator based on the node's type
            switch (node.type()) {
                case FILTER:
                    QueryRepresentation.FilterArgs filterArgs =
                            (QueryRepresentation.FilterArgs) node.arguments();

                    Operator<GenericEvent, GenericEvent> filterOperator =
                            query.addFilterOperator(
                                    operatorId,
                                    event -> OperatorUtils.evaluateCondition(
                                            event,
                                            filterArgs
                                    )
                            );

                    query.connect(lastOperatorInChain, filterOperator);
                    lastOperatorInChain = filterOperator;
                    break;


                case MAP_DUPLICATE:
                    QueryRepresentation.MapDuplicateArgs duplicateArgs = (QueryRepresentation.MapDuplicateArgs) node.arguments();
                    double duplicateProb = duplicateArgs.probability();

                    Operator<GenericEvent, GenericEvent> duplicateOperator = query.addFlatMapOperator(
                            operatorId,
                            event -> {
                                List<GenericEvent> results = new ArrayList<>();
                                results.add(event);
                                if (random.nextDouble() < duplicateProb) {
                                    results.add(new GenericEvent(event, GenericEvent.EventType.DUPLICATE));
                                }
                                return results;
                            }
                    );
                    query.connect(lastOperatorInChain, duplicateOperator);
                    lastOperatorInChain = duplicateOperator;
                    break;

                case MAP_NOISE:
                    QueryRepresentation.MapNoiseArgs noiseArgs = (QueryRepresentation.MapNoiseArgs) node.arguments();

                    Operator<GenericEvent, GenericEvent> noiseOperator = query.addMapOperator(
                            operatorId,
                            event -> {
                                if (event == null) return null;
                                double originalValue = getAttributeValue(event, noiseArgs.attribute());
                                if (Double.isNaN(originalValue)) return event;
                                double sigma = noiseArgs.percentage() * Math.abs(originalValue);
                                double noise = random.nextGaussian() * sigma;
                                return applyNoise(event, noiseArgs.attribute(), originalValue, noise);
                            }
                    );
                    query.connect(lastOperatorInChain, noiseOperator);
                    lastOperatorInChain = noiseOperator;
                    break;

                case MAP_AGGREGATE:
                    QueryRepresentation.MapAggregateArgs aggregateArgs =
                            (QueryRepresentation.MapAggregateArgs) node.arguments();

                    Operator<GenericEvent, GenericEvent> aggregateOperator =
                            query.addMapOperator(
                                    operatorId,
                                    new MovingAggregateMap(aggregateArgs.attribute(), aggregateArgs.function(), aggregateArgs.windowSize())
                            );

                    query.connect(lastOperatorInChain, aggregateOperator);
                    lastOperatorInChain = aggregateOperator;
                    break;

                case MAP_RIR:
                    throw new IllegalArgumentException("Map RIR can be created only from graph representations");
                    // QueryRepresentation.MapRIRArgs rirArgs =
                    //         (QueryRepresentation.MapRIRArgs) node.arguments();

                    // Operator<GenericEvent, GenericEvent> rirMapOperator =
                    //         query.addMapOperator(
                    //                 operatorId,
                    //                 new RIRMap(rirArgs.attribute())
                    //         );

                    // query.connect(lastOperatorInChain, rirMapOperator);
                    // lastOperatorInChain = rirMapOperator;
                    // break;

                default:
                    logger.warn("Unsupported operator type in representation: {}", node.type());
            }
        }

        // Define the final Sink
        Sink<GenericEvent> sink = query.addBaseSink("output-sink", event -> {
            if (event != null) {
                collectedEvents.add(event);
            }
        });
        query.connect(lastOperatorInChain, sink);
        query.activate();


        while(sink.isEnabled()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        query.deActivate();
        return collectedEvents;
    }

    private static <T> SourceFunction<T> createCollectionSource(final List<T> list) {
        return new SourceFunction<T>() {
            private int currentIndex = 0;
            private boolean isFinished = false;
            private static final long IDLE_SLEEP = 10;
            private boolean enabled;

            @Override
            public T get() {
                if (isFinished) {
                    Util.sleep(IDLE_SLEEP);
                    return null;
                }
                if (currentIndex < list.size()) {
                    T item = list.get(currentIndex);
                    currentIndex++;
                    return item;
                } else {
                    isFinished = true;
                    return null;
                }
            }

            @Override
            public boolean isInputFinished() {
                return isFinished;
            }

            @Override
            public void enable() {
                this.enabled = true;
            }

            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public void disable() {
                this.enabled = false;
            }

            @Override
            public boolean canRun() {
                return !isFinished;
            }
        };
    }
}
