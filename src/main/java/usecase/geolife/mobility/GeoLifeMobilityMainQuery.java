package usecase.geolife.mobility;

import common.util.backoff.InactiveBackoff;
import component.operator.Operator;
import component.sink.Sink;
import component.source.Source;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import query.LiebreContext;
import query.Query;
import usecase.common.CollectionSourceFactory;
import usecase.common.Tuple;
import usecase.common.flow.StreamFlowInstrumentation;

public final class GeoLifeMobilityMainQuery {

    private static final long HOUR_MILLIS = 60L * 60L * 1000L;

    static {
        LiebreContext.setSingleQueryExecution(false);
    }

    private GeoLifeMobilityMainQuery() {
    }

    public static QueryResult process(List<Tuple> inputStream, String queryId) {
        return process(inputStream, queryId, Settings.defaults());
    }

    public static QueryResult process(List<Tuple> inputStream, String queryId, Settings settings) {
        if (inputStream == null || inputStream.isEmpty()) {
            throw new IllegalArgumentException("inputStream cannot be null or empty");
        }
        long minTimestamp = inputStream.stream().mapToLong(Tuple::getTimestamp).min().orElseThrow();
        long maxTimestamp = inputStream.stream().mapToLong(Tuple::getTimestamp).max().orElseThrow();
        long maxWindowSizeMillis = Math.max(settings.userWindowSizeMillis(), settings.cellWindowSizeMillis());
        /*
         * Liebre time aggregates emit output tuples at the window left boundary
         * (the window start timestamp). The first aggregate output can therefore
         * be earlier than the first source tuple by up to the largest window size.
         * If the aggregate convention changes to right-boundary timestamps, move
         * this padding to the upper bound instead: keep the input minimum here and
         * use maxTimestamp + maxWindowSizeMillis below.
         */
        long instrumentationMinTimestamp = settings.instrumentationMinTimestamp() == null
                ? minTimestamp - maxWindowSizeMillis
                : settings.instrumentationMinTimestamp();
        long instrumentationMaxTimestamp = settings.instrumentationMaxTimestamp() == null
                ? maxTimestamp
                : settings.instrumentationMaxTimestamp();

        StreamFlowInstrumentation instrumentation = new StreamFlowInstrumentation(
                instrumentationMinTimestamp,
                instrumentationMaxTimestamp,
                settings.timeBins());
        Query query = new Query(settings.streamCapacity());
        query.setBackoff(1, 1, 1);

        List<Tuple> outputTuples = java.util.Collections.synchronizedList(new ArrayList<>());
        Source<Tuple> source = query.addBaseSource(
                "source-" + queryId,
                CollectionSourceFactory.fromList(inputStream, 0L));
        Operator<Tuple, Tuple> sourceRecorder = addRecorder(
                query,
                "record-source-" + queryId,
                instrumentation,
                "source");

        Operator<Tuple, Tuple> userVisitAggregate = query.addTimeAggregateOperator(
                "user-visit-" + queryId,
                settings.userWindowSizeMillis(),
                settings.userWindowSlideMillis(),
                new GeoLifeUserVisitWindow());
        Operator<Tuple, Tuple> stationaryVisitFilter = query.addFilterOperator(
                "stationary-visit-" + queryId,
                tuple -> isStationaryVisit(tuple, settings));
        Operator<Tuple, Tuple> stationaryVisitRecorder = addRecorder(
                query,
                "record-stationary-visit-" + queryId,
                instrumentation,
                "stationaryVisit");
        Operator<Tuple, Tuple> cellKeyMapper = query.addMapOperator(
                "cell-key-" + queryId,
                tuple -> withCellKey(tuple, settings.gridSizeMeters()));
        Operator<Tuple, Tuple> cellKeyRecorder = addRecorder(
                query,
                "record-cell-key-" + queryId,
                instrumentation,
                "cellKey");
        Operator<Tuple, Tuple> cellHotspotAggregate = query.addTimeAggregateOperator(
                "cell-hotspot-" + queryId,
                settings.cellWindowSizeMillis(),
                settings.cellWindowSlideMillis(),
                new GeoLifeCellHotspotWindow());
        Operator<Tuple, Tuple> hotspotFilter = query.addFilterOperator(
                "hotspot-filter-" + queryId,
                tuple -> isHotspot(tuple, settings));
        Operator<Tuple, Tuple> hotspotRecorder = addRecorder(
                query,
                "record-hotspot-" + queryId,
                instrumentation,
                "hotspot");
        Sink<Tuple> sink = query.addBaseSink("sink-" + queryId, event -> {
            if (event != null) {
                outputTuples.add(event);
            }
        });

        query.connect(source, sourceRecorder)
                .connect(sourceRecorder, userVisitAggregate)
                .connect(userVisitAggregate, stationaryVisitFilter)
                .connect(stationaryVisitFilter, stationaryVisitRecorder)
                .connect(stationaryVisitRecorder, cellKeyMapper)
                .connect(cellKeyMapper, cellKeyRecorder)
                .connect(cellKeyRecorder, cellHotspotAggregate)
                .connect(cellHotspotAggregate, hotspotFilter)
                .connect(hotspotFilter, hotspotRecorder)
                .connect(hotspotRecorder, sink, InactiveBackoff.INSTANCE);

        runUntilSinkFinishes(query, sink, settings.maxWaitMillis());
        outputTuples.sort(java.util.Comparator.comparingLong(Tuple::getTimestamp).thenComparing(Tuple::getKey));
        return new QueryResult(List.copyOf(outputTuples), instrumentation.snapshot());
    }

    private static Operator<Tuple, Tuple> addRecorder(
            Query query,
            String operatorId,
            StreamFlowInstrumentation instrumentation,
            String streamName) {
        int row = instrumentation.registerStream(streamName);
        return query.addMapOperator(operatorId, tuple -> {
            instrumentation.record(row, tuple);
            return tuple;
        });
    }

    public static void main(String[] args) throws Exception {
        String resource = args.length > 0 ? args[0] : GeoLifeTupleReader.DEFAULT_RESOURCE;
        List<Tuple> input = GeoLifeTupleReader.readResource(resource);
        long start = System.nanoTime();
        QueryResult result = process(input, "smoke");
        long elapsedMillis = Math.round((System.nanoTime() - start) / 1_000_000.0);
        System.out.printf(
                "GeoLife mobility query: input=%d output=%d elapsedMs=%d resource=%s%n",
                input.size(),
                result.outputTuples().size(),
                elapsedMillis,
                resource);
        System.out.println("Output fields: f1=centroidX f2=centroidY f3=uniqueUsers f4=visitWindows "
                + "f5=avgSamplesPerVisit f6=avgVisitRadiusMeters");
        result.outputTuples().stream().limit(20).forEach(tuple -> System.out.printf(
                "timestamp=%d key=%s fields=%s%n",
                tuple.getTimestamp(),
                tuple.getKey(),
                Arrays.toString(tuple.getFields())));
        System.exit(0);
    }

    private static boolean isStationaryVisit(Tuple tuple, Settings settings) {
        return Double.isFinite(tuple.getField("f1"))
                && Double.isFinite(tuple.getField("f2"))
                && tuple.getField("f3") >= settings.minUserWindowSamples()
                && tuple.getField("f4") <= settings.maxUserWindowRadiusMeters();
    }

    private static Tuple withCellKey(Tuple tuple, double gridSizeMeters) {
        long cellX = (long) Math.floor(tuple.getField("f1") / gridSizeMeters);
        long cellY = (long) Math.floor(tuple.getField("f2") / gridSizeMeters);
        return new Tuple(tuple.getStimulus(), tuple.getTimestamp(), "cell_" + cellX + "_" + cellY, tuple.getFields());
    }

    private static boolean isHotspot(Tuple tuple, Settings settings) {
        return Double.isFinite(tuple.getField("f1"))
                && Double.isFinite(tuple.getField("f2"))
                && tuple.getField("f3") >= settings.minHotspotUsers()
                && tuple.getField("f4") >= settings.minHotspotVisits();
    }

    private static void runUntilSinkFinishes(Query query, Sink<Tuple> sink, long maxWaitMillis) {
        query.activate();
        long deadlineMillis = maxWaitMillis <= 0L
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + maxWaitMillis;
        while (sink.isEnabled()) {
            if (System.currentTimeMillis() > deadlineMillis) {
                query.deActivate();
                throw new IllegalStateException("GeoLife mobility query did not finish within " + maxWaitMillis + " ms");
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        query.deActivate();
    }

    public record Settings(
            long userWindowSizeMillis,
            long userWindowSlideMillis,
            double minUserWindowSamples,
            double maxUserWindowRadiusMeters,
            double gridSizeMeters,
            long cellWindowSizeMillis,
            long cellWindowSlideMillis,
            double minHotspotUsers,
            double minHotspotVisits,
            int timeBins,
            int streamCapacity,
            long maxWaitMillis,
            Long instrumentationMinTimestamp,
            Long instrumentationMaxTimestamp) {

        public Settings {
            if (userWindowSizeMillis <= 0L || userWindowSlideMillis <= 0L
                    || cellWindowSizeMillis <= 0L || cellWindowSlideMillis <= 0L) {
                throw new IllegalArgumentException("Window sizes and slides must be positive");
            }
            if (minUserWindowSamples < 1d || maxUserWindowRadiusMeters < 0d
                    || gridSizeMeters <= 0d || minHotspotUsers < 1d || minHotspotVisits < 1d) {
                throw new IllegalArgumentException("Thresholds and grid size must be positive");
            }
            if (streamCapacity <= 0) {
                throw new IllegalArgumentException("streamCapacity must be positive");
            }
            if (timeBins <= 0) {
                throw new IllegalArgumentException("timeBins must be positive");
            }
            if (maxWaitMillis < 0L) {
                throw new IllegalArgumentException("maxWaitMillis cannot be negative");
            }
            if (instrumentationMinTimestamp != null && instrumentationMaxTimestamp != null
                    && instrumentationMaxTimestamp < instrumentationMinTimestamp) {
                throw new IllegalArgumentException("Instrumentation max timestamp cannot be lower than min timestamp");
            }
        }

        public Settings withInstrumentationRange(long minTimestamp, long maxTimestamp) {
            return new Settings(
                    userWindowSizeMillis,
                    userWindowSlideMillis,
                    minUserWindowSamples,
                    maxUserWindowRadiusMeters,
                    gridSizeMeters,
                    cellWindowSizeMillis,
                    cellWindowSlideMillis,
                    minHotspotUsers,
                    minHotspotVisits,
                    timeBins,
                    streamCapacity,
                    maxWaitMillis,
                    minTimestamp,
                    maxTimestamp);
        }

        public static Settings defaults() {
            return new Settings(
                    6L * HOUR_MILLIS,
                    6L * HOUR_MILLIS,
                    2d,
                    1_000d,
                    2_000d,
                    24L * HOUR_MILLIS,
                    24L * HOUR_MILLIS,
                    3d,
                    3d,
                    10,
                    10_000,
                    20_000L,
                    null,
                    null);
        }
    }

    public record QueryResult(List<Tuple> outputTuples, StreamFlowInstrumentation.Snapshot flow) {
    }
}
