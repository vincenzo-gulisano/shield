package query;

import common.util.Util;
import component.operator.Operator;
import component.operator.in1.map.MapFunction;
import component.sink.Sink;
import component.sink.SinkFunction;
import component.source.Source;
import component.source.SourceFunction;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.graph.Graph.Arc;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import usecase.common.Tuple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

// Class that translates a high-level QueryRepresentation (phenotype) into an executable Liebre query and
// processes an input stream to produce a modified stream of events
public class LiebreAnonymizationQueryFromGraph {

    private static final Logger logger = LoggerFactory.getLogger(LiebreAnonymizationQueryFromGraph.class);

    public LiebreAnonymizationQueryFromGraph() {
    }

    public List<Tuple> processAnonymizationQuery(Graph<OperatorRepresentation, ArcType> g, List<Tuple> inputTuples)
            throws IOException {

        final List<Tuple> collectedEvents = Collections.synchronizedList(new ArrayList<>());

        Query query = new Query();

        SourceFunction<Tuple> collectionSource = createCollectionSource(inputTuples);

        Source<Tuple> source = null;
        Sink<Tuple> sink = null;
        Map<String, Operator<Tuple, Tuple>> operators = new HashMap<>();

        // Traverse the graph, and add operators depending on the type of the node in
        // the graph, notice we do not add forks / unions, since those are basically
        // multi-writer and/or multi-writer streams.
        for (OperatorRepresentation opRep : g.nodes()) {
            switch (opRep) {
                case mappers.QueryMapper.FilterOperator f -> {
                    operators.put(f.getID(), query.addFilterOperator(f.getID(), f.createFilterFunction()));
                }
                case mappers.QueryMapper.MapDuplicate m -> {
                    operators.put(m.getID(), query.addFlatMapOperator(m.getID(), m.createMapFunction()));
                }
                case mappers.QueryMapper.MapNoise m -> {
                    operators.put(m.getID(), query.addMapOperator(m.getID(), m.createMapFunction()));
                }
                case mappers.QueryMapper.MapRIR m -> {
                    operators.put(m.getID(), query.addMapOperator(m.getID(), m.createRIRMap()));
                }
                case mappers.QueryMapper.MapAggregate m -> {
                    operators.put(m.getID(), query.addMapOperator(m.getID(), m.createMapFunction()));
                }
                case mappers.QueryMapper.Fork f -> {
                    operators.put(f.getID(),
                            query.addMapOperator(f.getID(), new MapFunction<Tuple, Tuple>() {
                                @Override
                                public Tuple apply(Tuple in) {
                                    return in;
                                }

                            }));
                }
                case mappers.QueryMapper.Union u -> {
                    operators.put(u.getID(),
                            query.addMapOperator(u.getID(), new MapFunction<Tuple, Tuple>() {

                                @Override
                                public Tuple apply(Tuple in) {
                                    return in;
                                }

                            }));
                }
                case

                        mappers.QueryMapper.Source s -> {
                    source = query.addBaseSource(s.getID(), collectionSource);
                }
                case
                        mappers.QueryMapper.Sink s -> {
                    sink = query.addBaseSink(s.getID(), new SinkFunction<Tuple>() {
                        @Override
                        public void accept(Tuple t) {
                            if (t != null) {
                                collectedEvents.add(t);
                            }
                        }
                    });
                }
                default -> {
                    throw new IllegalArgumentException(
                            "Unknown operator type in graph: " + opRep.getClass().getSimpleName());
                }
            }
        }

        // Now that operators are in place, we place connections. Single in - Single out
        // can be placed immediately, from fork and to union must be "gathered" and than
        // placed.
        Map<Operator<Tuple, Tuple>, List<Operator<Tuple, Tuple>>> unionSources = new HashMap<>();
        Map<Operator<Tuple, Tuple>, List<Operator<Tuple, Tuple>>> forkTargets = new HashMap<>();
        for (Arc<OperatorRepresentation> arc : g.arcs()) {

            // If the source id is the actual source, then it is safe to connected it to the
            // target operator immediately
            if (arc.source() instanceof mappers.QueryMapper.Source) {
                query.connect(source, operators.get(arc.target().getID()));
            }

            // If the target id is the actual sink, then it is safe to connect it to the
            // source operator immediately
            else if (arc.target() instanceof mappers.QueryMapper.Sink) {
                query.connect(operators.get(arc.source().getID()), sink);
            }

            // If the target is a union, we need to keep track of all the sources that need
            // to be connected to it, and connect them later, since union can have multiple
            // sources
            else if (arc.target() instanceof mappers.QueryMapper.Union) {
                unionSources.computeIfAbsent(operators.get(arc.target().getID()), k -> new ArrayList<>())
                        .add(operators.get(arc.source().getID()));
            }

            // If the source is a fork, we need to keep track of all the targets that need
            // to be connected to it, and connect them later, since fork can have multiple
            // targets
            else if (arc.source() instanceof mappers.QueryMapper.Fork) {
                forkTargets.computeIfAbsent(operators.get(arc.source().getID()), k -> new ArrayList<>())
                        .add(operators.get(arc.target().getID()));
            }

            // In the other cases, we can connect the source and target operator immediately
            else {
                query.connect(operators.get(arc.source().getID()), operators.get(arc.target().getID()));
            }
        }

        // Connect unions to their sources
        for (Map.Entry<Operator<Tuple, Tuple>, List<Operator<Tuple, Tuple>>> entry : unionSources.entrySet()) {
            query.connect(entry.getValue(), entry.getKey());
        }

        // Connect forks to their targets
        for (Map.Entry<Operator<Tuple, Tuple>, List<Operator<Tuple, Tuple>>> entry : forkTargets.entrySet()) {
            query.connect(List.of(entry.getKey()), entry.getValue());
        }
        query.activate();

        while (sink.isEnabled()) {
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