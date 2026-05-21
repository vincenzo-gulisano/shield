package usecase.forkjoin.synthetic;

import common.util.Util;
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
import usecase.common.Tuple;

import java.util.*;

public class MainQuery {

    // Record to contain the final results events and the collected performance
    // metrics
    public record QueryResult(List<Tuple> events, StreamStatsWindow statsWindow) {
    }

    public static QueryResult process(List<Tuple> inputStream, String queryId, long minTs, long maxTs) {

        final long resolution = 60000L; // 1 minute
        final double filter1min = 4000.0;
        final double filter1max = 6000.0;
        final double filter2min = 24000.0;
        final double filter2max = 26000.0;

        // Define the stages to monitor for performance
        StreamStatsWindow statsWindow = new StreamStatsWindow(
                Set.of("sourceStream", "branch1filter", "branch2filter"),
                minTs, maxTs, resolution);

        final List<Tuple> collectedEvents = Collections.synchronizedList(new ArrayList<>());
        Query query = new Query(100000);

        // Create and add a source that reads from the provided in-memory list
        SourceFunction<Tuple> collectionSource = createCollectionSource(inputStream);
        Source<Tuple> inputSource = query.addBaseSource("I1-" + queryId, collectionSource);

        RouterOperator<Tuple> router = query.addRouterOperator("router-" + queryId);

        // Filter for branch 1
        Operator<Tuple, Tuple> b1f = query.addFilterOperator(
                "b1f-" + queryId,
                tuple -> {
                    return (tuple.getField("f1") > OneCoreSyntheticTupleCsvGenerator.INNER_1_FIELD_1_MIN
                            && tuple.getField("f1") < OneCoreSyntheticTupleCsvGenerator.INNER_1_FIELD_1_MAX
                            && tuple.getField("f2") > OneCoreSyntheticTupleCsvGenerator.INNER_1_FIELD_2_MIN
                            && tuple.getField("f2") < OneCoreSyntheticTupleCsvGenerator.INNER_1_FIELD_2_MAX);
                });

        // Filter for branch 2
        Operator<Tuple, Tuple> b2f = query.addFilterOperator(
                "b2f-" + queryId,
                tuple -> {
                    return (tuple.getField("f1") > OneCoreSyntheticTupleCsvGenerator.INNER_2_FIELD_1_MIN
                            && tuple.getField("f1") < OneCoreSyntheticTupleCsvGenerator.INNER_2_FIELD_1_MAX
                            && tuple.getField("f2") > OneCoreSyntheticTupleCsvGenerator.INNER_2_FIELD_2_MIN
                            && tuple.getField("f2") < OneCoreSyntheticTupleCsvGenerator.INNER_2_FIELD_2_MAX);
                });

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

    // Helper method to create a Source Function that reads from a list
    private static <T> SourceFunction<T> createCollectionSource(final List<T> list) {
        return new SourceFunction<>() {
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