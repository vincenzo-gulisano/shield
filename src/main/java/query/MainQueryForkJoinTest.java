package query;

import common.util.Util;
import component.operator.Operator;
import component.operator.in1.map.MapFunction;
import component.sink.Sink;
import component.source.Source;
import component.source.SourceFunction;
import event.GenericEvent;
import metrics.performance.utils.StreamStatsWindow;

import java.util.*;

public class MainQueryForkJoinTest {

    // Record to contain the final results events and the collected performance metrics
    public record QueryResult(List<GenericEvent> events, StreamStatsWindow statsWindow) implements MainQueryResult {}

    public static QueryResult process(List<GenericEvent> inputStream, String queryId, long minTs, long maxTs) {

        final long resolution = 3000000L;  // 50 minutes
        final double filter1min = 4000.0;
        final double filter1max = 6000.0;
        final double filter2min = 24000.0;
        final double filter2max = 26000.0;

        // Define the stages to monitor for performance
        StreamStatsWindow statsWindow = new StreamStatsWindow(
                Set.of("sourceStream", "branch1filter", "branch2filter"),
                minTs, maxTs, resolution);

        final List<GenericEvent> collectedEvents = Collections.synchronizedList(new ArrayList<>());
        Query query = new Query();

        // Create and add a source that reads from the provided in-memory list
        SourceFunction<GenericEvent> collectionSource = createCollectionSource(inputStream);
        Source<GenericEvent> inputSource = query.addBaseSource("I1_" + queryId, collectionSource);

        // Filter for branch 1
        Operator<GenericEvent, GenericEvent> b1f = query.addFilterOperator(
                "b1f_" + queryId,
                tuple -> {
                    double f1 = tuple.getAttribute("f1");
                    return (f1 >= filter1min && f1 <= filter1max);
                });

        // Filter for branch 2
        Operator<GenericEvent, GenericEvent> b2f = query.addFilterOperator(
                "b2f_" + queryId,
                tuple -> {
                    double f2 = tuple.getAttribute("f2");
                    return (f2 >= filter2min && f2 <= filter2max);
                });

        // Inner class for performance metric recording
        class InnerPerformanceRecorder implements MapFunction<GenericEvent, GenericEvent> {
            private final String streamId;
            private final StreamStatsWindow statsWindowLocal;
            private long currentPerformanceMetricTimestamp = -1L;

            public InnerPerformanceRecorder(String streamId, StreamStatsWindow statsWindowLocal) {
                this.streamId = streamId;
                this.statsWindowLocal = statsWindowLocal;
            }

            @Override
            public GenericEvent apply(GenericEvent t) {
                if (t != null) {
                    currentPerformanceMetricTimestamp = (t.getTimestamp() - statsWindowLocal.minTimestamp()) / statsWindowLocal.getResolutionMillis();
                    long alignedTs = statsWindowLocal.minTimestamp() + currentPerformanceMetricTimestamp * statsWindowLocal.getResolutionMillis();
                    assert(alignedTs >= statsWindowLocal.minTimestamp());
                    assert (alignedTs <= statsWindowLocal.maxTimestamp());
                    statsWindowLocal.addTuples(streamId, alignedTs, 1);
                }
                return t;
            }
        }

        // Create performance recorder operators for each stage
        Operator<GenericEvent, GenericEvent> recorderAfterSource = query.addMapOperator("rec_s_" + queryId,
                new InnerPerformanceRecorder("sourceStream", statsWindow));
        Operator<GenericEvent, GenericEvent> recorderAfterBranch1 = query.addMapOperator("rec_b1_" + queryId,
                new InnerPerformanceRecorder("branch1filter", statsWindow));
        Operator<GenericEvent, GenericEvent> recorderAfterBranch2 = query.addMapOperator("rec_b2_" + queryId,
                new InnerPerformanceRecorder("branch2filter", statsWindow));

        // Final Sink that adds every valid event to the results list
        Sink<GenericEvent> sink = query.addBaseSink("o1_" + queryId, event -> {
            if (event != null && event.getEventType() != GenericEvent.EventType.EMPTY_WINDOW) {
                collectedEvents.add(event);
            }
        });

        // Connect the pipeline components
        query.connect(inputSource, recorderAfterSource)
                .connect(recorderAfterSource, b1f)
                .connect(b1f, recorderAfterBranch1)
                .connect(recorderAfterSource, b2f)
                .connect(b2f, recorderAfterBranch2)
                .connect(recorderAfterBranch2, sink)
                .connect(recorderAfterBranch2, sink);

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
            @Override public boolean isInputFinished() { return isFinished; }
            @Override public void enable() { this.enabled = true; }
            @Override public boolean isEnabled() { return enabled; }
            @Override public void disable() { this.enabled = false; }
            @Override public boolean canRun() { return !isFinished; }
        };
    }

}