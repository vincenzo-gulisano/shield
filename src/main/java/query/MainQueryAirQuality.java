package query;

import common.util.Util;
import component.operator.Operator;
import component.operator.in1.aggregate.BaseTimeWindowAddRemove;
import component.operator.in1.aggregate.TimeWindowAddRemove;
import component.operator.in1.map.MapFunction;
import component.sink.Sink;
import component.source.Source;
import component.source.SourceFunction;
import event.GenericEvent;
import metrics.performance.utils.StreamStatsWindow;

import java.util.*;

public class MainQueryAirQuality {

    // Record to contain the final results events and the collected performance metrics
    public record QueryResult(List<GenericEvent> events, StreamStatsWindow statsWindow) implements MainQueryResult {}

    public static QueryResult process(List<GenericEvent> inputStream, String queryId, long minTs, long maxTs) {

        final long resolution = 3600000L;  // 1 hour

        StreamStatsWindow statsWindow = new StreamStatsWindow(
                Set.of("sourceStream", "afterFilter1", "afterAggregate", "outputStream"),
                minTs, maxTs, resolution);

        final List<GenericEvent> collectedEvents = Collections.synchronizedList(new ArrayList<>());
        Query query = new Query();

        // Create and add a source that reads from the provided in-memory list
        SourceFunction<GenericEvent> collectionSource = createCollectionSource(inputStream);
        Source<GenericEvent> inputSource = query.addBaseSource("I1_" + queryId, collectionSource);

        // Operator to filter tuple with CO level >= 2.0 and NO2 level >= 40.0
        Operator<GenericEvent, GenericEvent> filter1 = query.addFilterOperator(
                "filter1_" + queryId, tuple -> (tuple.getAttribute("CO(GT)") >= 2.0 && tuple.getAttribute("NO2(GT)") >= 40.0));

        // Window of 3 hours, sliding every 1 hour
        final long WINDOW_SIZE = 3 * 60 * 60 * 1000;
        final long WINDOW_SLIDE = 60 * 60 * 1000;

        // Operator to aggregate the CO level and NO2 level in a window of 3 hours
        Operator<GenericEvent, GenericEvent> aggregateOperator = query.addTimeAggregateOperator(
                "average_" + queryId,
                WINDOW_SIZE, WINDOW_SLIDE, new AggregateWindow());

        // Operator to filter tuple with aggregate CO level >= 5.0 and aggregate NO2
        // level >= 100.0
        Operator<GenericEvent, GenericEvent> filter2 = query.addFilterOperator(
                "filter2_" + queryId,
                tuple -> (tuple.getAttribute("CO(GT)") >= 5.0 && tuple.getAttribute("NO2(GT)") >= 100.0));

        class InnerMainQueryKeys implements MapFunction<GenericEvent, GenericEvent> {

            private final HashSet<String> keysSet = new HashSet<>();
            private final String id;
            private final StreamStatsWindow statsWindowLocal;

            private long currentPerformanceMetricTimestamp = -1L;

            public InnerMainQueryKeys(String id, StreamStatsWindow statsWindowLocal) {
                this.id = id;
                this.statsWindowLocal = statsWindowLocal;
            }

            @Override
            public GenericEvent apply(GenericEvent t) {
                if (t != null) {

                    // Calculate the bucket index for the current event's timestamp.
                    long bucketIndex =
                            (t.getTimestamp() - statsWindowLocal.minTimestamp())
                                    / statsWindowLocal.getResolutionMillis();

                    if (currentPerformanceMetricTimestamp != -1
                            && currentPerformanceMetricTimestamp != bucketIndex) {
                        // New timestamp, reset the keys set
                        keysSet.clear();
                    }
                    currentPerformanceMetricTimestamp = bucketIndex;

                    // Reconstruct the aligned timestamp for the start of the current bucket to use in the method addKeys and addTuples
                    long alignedTs = statsWindowLocal.minTimestamp() + bucketIndex * statsWindowLocal.getResolutionMillis();

                    // Clamp timestamp to avoid out-of-bounds generated from the aggregation
                    if (alignedTs < statsWindowLocal.minTimestamp()) {
                        alignedTs = statsWindowLocal.minTimestamp();
                    }
                    if (alignedTs > statsWindowLocal.maxTimestamp()) {
                        alignedTs = statsWindowLocal.maxTimestamp();
                    }

                    // Update the performance statistics
                    if (!keysSet.contains(t.getKey())
                            && t.getEventType() != GenericEvent.EventType.EMPTY_WINDOW) {
                        keysSet.add(t.getKey());
                        statsWindowLocal.addKeys(id, alignedTs, 1);
                    }

                    statsWindowLocal.addTuples(id, alignedTs, 1);
                }

                return t;
            }
        }

        Operator<GenericEvent, GenericEvent> keyRecorderAfterSource = query.addMapOperator("rec_as_" + queryId,
                new InnerMainQueryKeys("sourceStream", statsWindow));
        Operator<GenericEvent, GenericEvent> keyRecorderAfterFilter1 = query.addMapOperator("rec_af1_" + queryId,
                new InnerMainQueryKeys("afterFilter1", statsWindow));
        Operator<GenericEvent, GenericEvent> keyRecorderAfterAggregate = query.addMapOperator("rec_aa_" + queryId,
                new InnerMainQueryKeys("afterAggregate", statsWindow));
        Operator<GenericEvent, GenericEvent> keyRecorderAfterFilter2 = query.addMapOperator("rec_af2_" + queryId,
                new InnerMainQueryKeys("outputStream", statsWindow));

        // Final Sink that adds every event to a list
        Sink<GenericEvent> sink = query.addBaseSink("o1_" + queryId, event -> {
            if (event != null && event.getEventType() != GenericEvent.EventType.EMPTY_WINDOW) {
                collectedEvents.add(event);
            }
        });

        query.connect(inputSource, keyRecorderAfterSource)
                .connect(keyRecorderAfterSource, filter1)
                .connect(filter1, keyRecorderAfterFilter1)
                .connect(keyRecorderAfterFilter1, aggregateOperator)
                .connect(aggregateOperator, keyRecorderAfterAggregate)
                .connect(keyRecorderAfterAggregate, filter2)
                .connect(filter2, keyRecorderAfterFilter2)
                .connect(keyRecorderAfterFilter2, sink);

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

    private static class AggregateWindow extends BaseTimeWindowAddRemove<GenericEvent, GenericEvent> {
        private int count = 0;
        private double sumCO = 0.0;
        private double sumNO2 = 0.0;
        private GenericEvent lastEvent = null;
        private long lastOutputTs = -1L;

        @Override
        public void add(GenericEvent event) {
            double co = event.getAttribute("CO(GT)");
            double no2 = event.getAttribute("NO2(GT)");
            if (!Double.isNaN(co) && !Double.isNaN(no2)) {
                sumCO += co;
                sumNO2 += no2;
                count++;
                lastEvent = event;
            }
        }

        @Override
        public void remove(GenericEvent event) {
            double co = event.getAttribute("CO(GT)");
            double no2 = event.getAttribute("NO2(GT)");
            if (!Double.isNaN(co) && !Double.isNaN(no2)) {
                sumCO -= co;
                sumNO2 -= no2;
                count--;
            }
        }

        @Override
        public GenericEvent getAggregatedResult() {
            if (count == 0 || lastEvent == null) {
                return GenericEvent.createEmptyEvent(this.startTimestamp);
            }

            // Avoid duplicates due to the previous filter operator in the pipeline
            if (lastEvent.getTimestamp() == lastOutputTs) {
                return GenericEvent.createEmptyEvent(this.startTimestamp);
            }
            double averageCO = sumCO / count;
            double averageNO2 = sumNO2 / count;
            lastOutputTs = lastEvent.getTimestamp();

            GenericEvent resultEvent = new GenericEvent(lastEvent);
            resultEvent.setAttribute("CO(GT)", averageCO);
            resultEvent.setAttribute("NO2(GT)", averageNO2);

            return resultEvent;
        }

        @Override
        public TimeWindowAddRemove<GenericEvent, GenericEvent> factory() {
            return new AggregateWindow();
        }
    }

}