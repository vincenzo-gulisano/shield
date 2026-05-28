package usecase.forkjoin.synthetic;

import common.util.backoff.InactiveBackoff;
import component.operator.Operator;
import component.operator.in1.map.MapFunction;
import component.operator.router.RouterOperator;
import component.operator.union.UnionOperator;
import component.sink.Sink;
import component.source.Source;
import component.source.SourceFunction;
import metrics.performance.utils.StreamStatsWindow;
import query.Query;
import usecase.common.CollectionSourceFactory;
import usecase.common.Tuple;

import java.util.*;

public class MainQuery {

    // Record to contain the final results events and the collected performance
    // metrics
    public record QueryResult(List<Tuple> events, StreamStatsWindow statsWindow) {
    }

    public static QueryResult process(List<Tuple> inputStream, String queryId, long minTs, long maxTs) {

        final long resolution = 60000L; // 1 minute
        final int expectedBranchTuples = Math.min(
                OneCoreSyntheticTupleCsvGenerator.INNER_1_TUPLES,
                OneCoreSyntheticTupleCsvGenerator.INNER_2_TUPLES);
        final FilterBounds branch1Bounds = centeredBoundsForExpectedCount(
                OneCoreSyntheticTupleCsvGenerator.INNER_1_FIELD_1_MIN,
                OneCoreSyntheticTupleCsvGenerator.INNER_1_FIELD_1_MAX,
                OneCoreSyntheticTupleCsvGenerator.INNER_1_FIELD_2_MIN,
                OneCoreSyntheticTupleCsvGenerator.INNER_1_FIELD_2_MAX,
                OneCoreSyntheticTupleCsvGenerator.INNER_1_TUPLES,
                expectedBranchTuples);
        final FilterBounds branch2Bounds = centeredBoundsForExpectedCount(
                OneCoreSyntheticTupleCsvGenerator.INNER_2_FIELD_1_MIN,
                OneCoreSyntheticTupleCsvGenerator.INNER_2_FIELD_1_MAX,
                OneCoreSyntheticTupleCsvGenerator.INNER_2_FIELD_2_MIN,
                OneCoreSyntheticTupleCsvGenerator.INNER_2_FIELD_2_MAX,
                OneCoreSyntheticTupleCsvGenerator.INNER_2_TUPLES,
                expectedBranchTuples);

        // Define the stages to monitor for performance
        StreamStatsWindow statsWindow = new StreamStatsWindow(
                Set.of("sourceStream", "branch1filter", "branch2filter"),
                minTs, maxTs, resolution);

        final List<Tuple> collectedEvents = Collections.synchronizedList(new ArrayList<>());
        Query query = new Query(100000);

        // Create and add a source that reads from the provided in-memory list
        SourceFunction<Tuple> collectionSource = CollectionSourceFactory.fromList(inputStream);
        Source<Tuple> inputSource = query.addBaseSource("I1-" + queryId, collectionSource);

        RouterOperator<Tuple> router = query.addRouterOperator("router-" + queryId);

        // Filter for branch 1
        Operator<Tuple, Tuple> b1f = query.addFilterOperator(
                "b1f-" + queryId,
                branch1Bounds::contains);

        // Filter for branch 2
        Operator<Tuple, Tuple> b2f = query.addFilterOperator(
                "b2f-" + queryId,
                branch2Bounds::contains);

        // Inner class for performance metric recording
        class InnerPerformanceRecorder implements MapFunction<Tuple, Tuple> {
            private final String streamId;
            private final StreamStatsWindow statsWindowLocal;
            private long currentPerformanceMetricTimestamp = -1L;

            public InnerPerformanceRecorder(String streamId, StreamStatsWindow statsWindowLocal) {
                this.streamId = streamId;
                this.statsWindowLocal = statsWindowLocal;
            }

            @Override
            public Tuple apply(Tuple t) {
                if (t != null) {
                    currentPerformanceMetricTimestamp = (t.getTimestamp() - statsWindowLocal.minTimestamp())
                            / statsWindowLocal.getResolutionMillis();
                    long alignedTs = statsWindowLocal.minTimestamp()
                            + currentPerformanceMetricTimestamp * statsWindowLocal.getResolutionMillis();
                    assert (alignedTs >= statsWindowLocal.minTimestamp());
                    assert (alignedTs <= statsWindowLocal.maxTimestamp());
                    statsWindowLocal.addTuples(streamId, alignedTs, 1);
                }
                return t;
            }
        }

        // Create performance recorder operators for each stage
        Operator<Tuple, Tuple> recorderAfterSource = query.addMapOperator("rec-s-" + queryId,
                new InnerPerformanceRecorder("sourceStream", statsWindow));
        Operator<Tuple, Tuple> recorderAfterBranch1 = query.addMapOperator("rec-b1-" + queryId,
                new InnerPerformanceRecorder("branch1filter", statsWindow));
        Operator<Tuple, Tuple> recorderAfterBranch2 = query.addMapOperator("rec-b2-" + queryId,
                new InnerPerformanceRecorder("branch2filter", statsWindow));

        UnionOperator<Tuple> union = query.addUnionOperator("union-" + queryId);

        // Final Sink that adds every valid event to the results list
        Sink<Tuple> sink = query.addBaseSink("o1-" + queryId, event -> {
            collectedEvents.add(event);
        });

        // Connect the pipeline components
        query.connect(inputSource, recorderAfterSource)
                .connect(recorderAfterSource, router)
                .connect(router, b1f)
                .connect(router, b2f)
                .connect(b1f, recorderAfterBranch1)
                .connect(b2f, recorderAfterBranch2)
                .connect(recorderAfterBranch1, union, InactiveBackoff.INSTANCE)
                .connect(recorderAfterBranch2, union, InactiveBackoff.INSTANCE)
                .connect(union, sink);

        query.activate();

        while (sink.isEnabled()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        query.deActivate();

        return new QueryResult(collectedEvents, statsWindow);
    }

    private static FilterBounds centeredBoundsForExpectedCount(double f1Min,
                                                               double f1Max,
                                                               double f2Min,
                                                               double f2Max,
                                                               int clusterTupleCount,
                                                               int expectedTupleCount) {
        if (clusterTupleCount <= 0) {
            throw new IllegalArgumentException("clusterTupleCount must be positive");
        }
        if (expectedTupleCount < 0) {
            throw new IllegalArgumentException("expectedTupleCount cannot be negative");
        }

        double selectedAreaFraction = Math.min(1.0, (double) expectedTupleCount / clusterTupleCount);
        double sideScale = Math.sqrt(selectedAreaFraction);
        return centeredBounds(f1Min, f1Max, f2Min, f2Max, sideScale);
    }

    private static FilterBounds centeredBounds(double f1Min,
                                               double f1Max,
                                               double f2Min,
                                               double f2Max,
                                               double sideScale) {
        double f1Center = (f1Min + f1Max) / 2.0;
        double f2Center = (f2Min + f2Max) / 2.0;
        double f1HalfWidth = (f1Max - f1Min) * sideScale / 2.0;
        double f2HalfWidth = (f2Max - f2Min) * sideScale / 2.0;
        return new FilterBounds(
                f1Center - f1HalfWidth,
                f1Center + f1HalfWidth,
                f2Center - f2HalfWidth,
                f2Center + f2HalfWidth);
    }

    private record FilterBounds(double f1Min, double f1Max, double f2Min, double f2Max) {
        private boolean contains(Tuple tuple) {
            return tuple.getField("f1") > f1Min
                    && tuple.getField("f1") < f1Max
                    && tuple.getField("f2") > f2Min
                    && tuple.getField("f2") < f2Max;
        }
    }

}
