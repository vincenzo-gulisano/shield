package query;

import common.util.Util;
import component.operator.Operator;
import component.sink.Sink;
import component.source.Source;
import component.source.SourceFunction;
import event.EventFactory;
import event.GenericEvent;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import mappers.QueryMapper.Arc;
import mappers.QueryMapper.OperatorRepresentation;
import mappers.QueryRepresentation;
import query.utils.MovingAggregateMap;
import query.utils.OperatorUtils;
import query.utils.RIRMap;
import usecase.common.Tuple;

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
public class LiebreAnonymizationQueryFromGraph {

    private static final Logger logger = LoggerFactory.getLogger(LiebreAnonymizationQueryFromGraph.class);

    private final Random random;

    public LiebreAnonymizationQueryFromGraph() {
        this.random = new Random();
    }

    public List<Tuple> processAnonymizationQuery(Graph<OperatorRepresentation, Arc> g, List<Tuple> inputTuples) throws IOException {

        final List<Tuple> collectedEvents = Collections.synchronizedList(new ArrayList<>());

        Query query = new Query();

        Source<Tuple> source = null;
        SourceFunction<Tuple> collectionSource = createCollectionSource(inputTuples);

        // Traverse the graph, and add operators depending on the type of the node in the graph
        for(OperatorRepresentation opRep : g.nodes()) {
            // String[] opRepresentation = opRep.getRepresentation();
            // // Check the representaion format with a regex, it should be "OperatorType(param1,param2,...)", otherwise, the format is invalid
            // switch (opRepresentation[0]) {
            //     case "Source":
            //         source = query.addBaseSource("source", collectionSource);
            //         break;
            
            //     default:
            //         break;
            // }
        }



        // Define Source and CSV Reader (fixed part of the pipeline)
        // long[] idCounter = {0};
        // Source<String> source = query.addBaseSource("input-source", collectionSource);
        // Operator<String, GenericEvent> reader = query.addMapOperator(
        //         "csv-reader",
        //         line -> {
        //             if (line.equals(headerLine)) return null;
        //             return EventFactory.createEventFromLine(line, headers, keyColumn, idCounter[0]++);
        //         }
        // );
        // query.connect(source, reader);

        // Build the operator chain by iterating through the representation's nodes
        // Operator<?, GenericEvent> lastOperatorInChain = reader;
        // int opCounter = 0;

        // // Loop through each operator node in the phenotype representation
        // for (QueryRepresentation.OperatorNode node: representation.operators()) {
        //     // Create a unique id for the Liebre operator
        //     String operatorId = node.type().name().toLowerCase() + "-" + opCounter++;

        //     // Build the correct Liebre operator based on the node's type
        //     switch (node.type()) {
        //         case FILTER:
        //             QueryRepresentation.FilterArgs filterArgs =
        //                     (QueryRepresentation.FilterArgs) node.arguments();

        //             Operator<GenericEvent, GenericEvent> filterOperator =
        //                     query.addFilterOperator(
        //                             operatorId,
        //                             event -> OperatorUtils.evaluateCondition(
        //                                     event,
        //                                     filterArgs
        //                             )
        //                     );

        //             query.connect(lastOperatorInChain, filterOperator);
        //             lastOperatorInChain = filterOperator;
        //             break;


        //         case MAP_DUPLICATE:
        //             QueryRepresentation.MapDuplicateArgs duplicateArgs = (QueryRepresentation.MapDuplicateArgs) node.arguments();
        //             double duplicateProb = duplicateArgs.probability();

        //             Operator<GenericEvent, GenericEvent> duplicateOperator = query.addFlatMapOperator(
        //                     operatorId,
        //                     event -> {
        //                         List<GenericEvent> results = new ArrayList<>();
        //                         results.add(event);
        //                         if (random.nextDouble() < duplicateProb) {
        //                             results.add(new GenericEvent(event, GenericEvent.EventType.DUPLICATE));
        //                         }
        //                         return results;
        //                     }
        //             );
        //             query.connect(lastOperatorInChain, duplicateOperator);
        //             lastOperatorInChain = duplicateOperator;
        //             break;

        //         case MAP_NOISE:
        //             QueryRepresentation.MapNoiseArgs noiseArgs = (QueryRepresentation.MapNoiseArgs) node.arguments();

        //             Operator<GenericEvent, GenericEvent> noiseOperator = query.addMapOperator(
        //                     operatorId,
        //                     event -> {
        //                         if (event == null) return null;
        //                         double originalValue = getAttributeValue(event, noiseArgs.attribute());
        //                         if (Double.isNaN(originalValue)) return event;
        //                         double sigma = noiseArgs.percentage() * Math.abs(originalValue);
        //                         double noise = random.nextGaussian() * sigma;
        //                         return applyNoise(event, noiseArgs.attribute(), originalValue, noise);
        //                     }
        //             );
        //             query.connect(lastOperatorInChain, noiseOperator);
        //             lastOperatorInChain = noiseOperator;
        //             break;

        //         case MAP_AGGREGATE:
        //             QueryRepresentation.MapAggregateArgs aggregateArgs =
        //                     (QueryRepresentation.MapAggregateArgs) node.arguments();

        //             Operator<GenericEvent, GenericEvent> aggregateOperator =
        //                     query.addMapOperator(
        //                             operatorId,
        //                             new MovingAggregateMap(aggregateArgs.attribute(), aggregateArgs.function(), aggregateArgs.windowSize())
        //                     );

        //             query.connect(lastOperatorInChain, aggregateOperator);
        //             lastOperatorInChain = aggregateOperator;
        //             break;

        //         case MAP_RIR:
        //             QueryRepresentation.MapRIRArgs rirArgs =
        //                     (QueryRepresentation.MapRIRArgs) node.arguments();

        //             Operator<GenericEvent, GenericEvent> rirMapOperator =
        //                     query.addMapOperator(
        //                             operatorId,
        //                             new RIRMap(rirArgs.attribute())
        //                     );

        //             query.connect(lastOperatorInChain, rirMapOperator);
        //             lastOperatorInChain = rirMapOperator;
        //             break;

        //         default:
        //             logger.warn("Unsupported operator type in representation: {}", node.type());
        //     }
        // }

        // // Define the final Sink
        // Sink<GenericEvent> sink = query.addBaseSink("output-sink", event -> {
        //     if (event != null) {
        //         collectedEvents.add(event);
        //     }
        // });
        // query.connect(lastOperatorInChain, sink);
        // query.activate();


        // while(sink.isEnabled()) {
        //     try {
        //         Thread.sleep(10);
        //     } catch (InterruptedException e) {
        //         e.printStackTrace();
        //     }
        // }

        query.deActivate();
        return collectedEvents;
    }

    private static <T> SourceFunction<T> createCollectionSource(final List<T> list) {
        return new SourceFunction<T>() {
            private int currentIndex = 0;
            private boolean isFinished = false;
            private static final long IDLE_SLEEP = 0;
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