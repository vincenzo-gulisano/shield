package query;

import component.operator.Operator;
import component.operator.in1.aggregate.BaseTimeWindowAddRemove;
import component.operator.in1.aggregate.TimeWindowAddRemove;
import component.operator.in1.map.MapFunction;
import component.sink.Sink;
import component.source.Source;
import component.source.SourceFunction;
import event.GenericEvent;
import metrics.performance.utils.StreamStatsWindow;
import usecase.common.CollectionSourceFactory;

import java.util.*;

public class MainQueryGeoLife {

    // Record to contain the final results events and the collected performance metrics
    public record QueryResult(List<GenericEvent> events, StreamStatsWindow statsWindow) implements MainQueryResult {}

    public static QueryResult process(List<GenericEvent> inputStream, String queryId, long minTs, long maxTs) {

        final long resolution = 3600000L;  // 1 hour
        final double centerX = 341097.79;
        final double centerY = 111055.50;
        final double range = 2000.0;

        // Define the stages to monitor for performance
        StreamStatsWindow statsWindow = new StreamStatsWindow(
                Set.of("sourceStream", "afterAggregate", "outputStream"),
                minTs, maxTs, resolution);

        final List<GenericEvent> collectedEvents = Collections.synchronizedList(new ArrayList<>());
        Query query = new Query();

        // Create and add a source that reads from the provided in-memory list
        SourceFunction<GenericEvent> collectionSource = CollectionSourceFactory.fromList(inputStream);
        Source<GenericEvent> inputSource = query.addBaseSource("I1_" + queryId, collectionSource);

        // Window of 3 hours, sliding every 1 hour
        final long WINDOW_SIZE = 3 * 60 * 60 * 1000;
        final long WINDOW_SLIDE = 60 * 60 * 1000;

        // Operator to aggregate the avg_X and avg_Y for each user in a window of 3 hours
        Operator<GenericEvent, GenericEvent> aggregateOperator = query.addTimeAggregateOperator(
                "average_" + queryId,
                WINDOW_SIZE, WINDOW_SLIDE, new AggregateWindow());

        // Operator to filter users whose aggregated position is within the center box
        Operator<GenericEvent, GenericEvent> filter = query.addFilterOperator(
                "filter_center_" + queryId,
                tuple -> {
                    double x = tuple.getAttribute("avg_X");
                    double y = tuple.getAttribute("avg_Y");
                    return (x > centerX - range && x < centerX + range &&
                            y > centerY - range && y < centerY + range);
                });

        // Inner class for performance metric recording
        class InnerPerformanceRecorder implements MapFunction<GenericEvent, GenericEvent> {
            private final HashSet<String> keysSet = new HashSet<>();
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
                    long bucketIndex = (t.getTimestamp() - statsWindowLocal.minTimestamp()) / statsWindowLocal.getResolutionMillis();
                    if (currentPerformanceMetricTimestamp != -1 && currentPerformanceMetricTimestamp != bucketIndex) {
                        keysSet.clear();
                    }
                    currentPerformanceMetricTimestamp = bucketIndex;
                    long alignedTs = statsWindowLocal.minTimestamp() + bucketIndex * statsWindowLocal.getResolutionMillis();
                    if (alignedTs < statsWindowLocal.minTimestamp()) alignedTs = statsWindowLocal.minTimestamp();
                    if (alignedTs > statsWindowLocal.maxTimestamp()) alignedTs = statsWindowLocal.maxTimestamp();

                    if (!keysSet.contains(t.getKey()) && t.getEventType() != GenericEvent.EventType.EMPTY_WINDOW) {
                        keysSet.add(t.getKey());
                        statsWindowLocal.addKeys(streamId, alignedTs, 1);
                    }
                    statsWindowLocal.addTuples(streamId, alignedTs, 1);
                }
                return t;
            }
        }

        // Create performance recorder operators for each stage
        Operator<GenericEvent, GenericEvent> recorderAfterSource = query.addMapOperator("rec_as_" + queryId,
                new InnerPerformanceRecorder("sourceStream", statsWindow));
        Operator<GenericEvent, GenericEvent> recorderAfterAggregate = query.addMapOperator("rec_aa_" + queryId,
                new InnerPerformanceRecorder("afterAggregate", statsWindow));
        Operator<GenericEvent, GenericEvent> recorderAfterFilter = query.addMapOperator("rec_af_" + queryId,
                new InnerPerformanceRecorder("outputStream", statsWindow));

        // Final Sink that adds every valid event to the results list
        Sink<GenericEvent> sink = query.addBaseSink("o1_" + queryId, event -> {
            if (event != null && event.getEventType() != GenericEvent.EventType.EMPTY_WINDOW) {
                collectedEvents.add(event);
            }
        });

        // Connect the pipeline components
        query.connect(inputSource, recorderAfterSource)
                .connect(recorderAfterSource, aggregateOperator)
                .connect(aggregateOperator, recorderAfterAggregate)
                .connect(recorderAfterAggregate, filter)
                .connect(filter, recorderAfterFilter)
                .connect(recorderAfterFilter, sink);

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

    // Aggregation logic specific to the GeoLife dataset
    private static class AggregateWindow extends BaseTimeWindowAddRemove<GenericEvent, GenericEvent> {
        private int count = 0;
        private double sumX = 0.0;
        private double sumY = 0.0;
        private GenericEvent lastEvent = null;
        private long lastOutputTs = -1L;

        @Override
        public void add(GenericEvent event) {
            double x = event.getAttribute("avg_X");
            double y = event.getAttribute("avg_Y");
            if (!Double.isNaN(x) && !Double.isNaN(y)) {
                sumX += x;
                sumY += y;
                count++;
                lastEvent = event;
            }
        }

        @Override
        public void remove(GenericEvent event) {
            double x = event.getAttribute("avg_X");
            double y = event.getAttribute("avg_Y");
            if (!Double.isNaN(x) && !Double.isNaN(y)) {
                sumX -= x;
                sumY -= y;
                count--;
            }
        }

        @Override
        public GenericEvent getAggregatedResult() {
            if (count == 0 || lastEvent == null) {
                return GenericEvent.createEmptyEvent(this.startTimestamp);
            }
            if (lastEvent.getTimestamp() == lastOutputTs) {
                return GenericEvent.createEmptyEvent(this.startTimestamp);
            }
            lastOutputTs = lastEvent.getTimestamp();

            GenericEvent resultEvent = new GenericEvent(lastEvent);
            resultEvent.setAttribute("avg_X", sumX / count);
            resultEvent.setAttribute("avg_Y", sumY / count);
            return resultEvent;
        }

        @Override
        public TimeWindowAddRemove<GenericEvent, GenericEvent> factory() {
            return new AggregateWindow();
        }
    }
}
