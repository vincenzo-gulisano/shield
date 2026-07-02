package usecase.lcl.flow;

import common.util.backoff.InactiveBackoff;
import component.operator.Operator;
import component.sink.Sink;
import component.source.Source;
import experimental.provenance.ProvenanceQueryTransformer;
import experimental.provenance.UIDFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import query.LiebreContext;
import query.Query;
import query.utils.ConditionalTupleRouterOperator;
import query.utils.FlushableFlatMapOperator;
import scheduling.basic.BasicLiebreScheduler;
import stream.BackoffStreamFactory;
import usecase.common.CollectionSourceFactory;
import usecase.common.Tuple;
import usecase.common.flow.InstrumentedStreamFactory;
import usecase.common.flow.StreamFlowInstrumentation;

/**
 * Branch-sensitive LCL flow query that uses all daily load-profile fields.
 *
 * <p>The alert scores in this query are synthetic benchmark rules, not validated operational
 * utility alarms. They are intended to mimic utility-style daily load screening: the Std branch
 * approximates broad high-load, inefficient, and spiky usage, while the ToU branch emphasizes
 * evening-peak and tariff-sensitive load stress.
 *
 * <p>Every privacy-relevant input field {@code f1..f11} affects routing, alert selection, or the
 * semantic output. This makes the benchmark less permissive than a query where privacy can be
 * improved by modifying fields that the main query largely ignores.
 */
public final class LclFlowAllFieldsMainQuery {

    private static final double STD_DAILY_KWH_REF = 14.8175d;
    private static final double STD_MAX_30_MIN_REF = 1.26225d;
    private static final double STD_MEDIAN_30_MIN_REF = 0.2165d;
    private static final double STD_P90_30_MIN_REF = 0.51825d;
    private static final double STD_STDEV_30_MIN_REF = 0.228967d;
    private static final double STD_EVENING_SHARE_REF = 0.40467d;
    private static final double STD_NIGHT_SHARE_REF = 0.189102d;
    private static final double STD_LOAD_FACTOR_REF = 0.381963d;
    private static final double STD_ZERO_HALF_HOUR_REF = 6.0d;

    private static final double TOU_DAILY_KWH_REF = 12.542d;
    private static final double TOU_MAX_30_MIN_REF = 1.055d;
    private static final double TOU_MEDIAN_30_MIN_REF = 0.1995d;
    private static final double TOU_P90_30_MIN_REF = 0.526d;
    private static final double TOU_STDEV_30_MIN_REF = 0.214701d;
    private static final double TOU_EVENING_SHARE_REF = 0.412775d;
    private static final double TOU_NIGHT_SHARE_REF = 0.09819d;
    private static final double TOU_LOAD_FACTOR_REF = 0.343726d;
    private static final double TOU_ZERO_HALF_HOUR_REF = 3.0d;

    static {
        LiebreContext.setSingleQueryExecution(false);
    }

    private LclFlowAllFieldsMainQuery() {
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
        long instrumentationMinTimestamp = settings.instrumentationMinTimestamp() == null
                ? minTimestamp
                : settings.instrumentationMinTimestamp();
        long instrumentationMaxTimestamp = settings.instrumentationMaxTimestamp() == null
                ? maxTimestamp
                : settings.instrumentationMaxTimestamp();

        StreamFlowInstrumentation instrumentation =
                new StreamFlowInstrumentation(instrumentationMinTimestamp, instrumentationMaxTimestamp, settings.timeBins());
        Query query = new Query(
                new BasicLiebreScheduler(),
                new InstrumentedStreamFactory(new BackoffStreamFactory(), instrumentation),
                settings.streamCapacity());
        query.setBackoff(1, 1, 1);
        BuiltQuery builtQuery = buildQuery(query, inputStream, queryId, settings);

        runUntilSinksFinish(query, builtQuery.sinks(), settings.maxWaitMillis(), "LCL all-fields flow query");

        return new QueryResult(List.copyOf(builtQuery.outputTuples()), instrumentation.snapshot());
    }

    public static ProvenanceQueryResult processWithProvenance(List<Tuple> inputStream, String queryId) {
        return processWithProvenance(inputStream, queryId, Settings.defaults());
    }

    public static ProvenanceQueryResult processWithProvenance(
            List<Tuple> inputStream,
            String queryId,
            Settings settings) {
        if (inputStream == null || inputStream.isEmpty()) {
            throw new IllegalArgumentException("inputStream cannot be null or empty");
        }
        Query original = new Query(settings.streamCapacity());
        original.setBackoff(1, 1, 1);
        BuiltQuery builtQuery = buildQuery(original, inputStream, queryId, settings);
        boolean uidsWereEnabled = UIDFactory.INSTANCE.isUIDsEnabled();
        if (!uidsWereEnabled) {
            UIDFactory.INSTANCE.enableUIDs();
        }
        try {
            Query provenanceQuery = new ProvenanceQueryTransformer().transform(original);
            runUntilSinksFinish(
                    provenanceQuery,
                    provenanceQuery.sinks(),
                    settings.maxWaitMillis(),
                    "LCL all-fields provenance query");
            return new ProvenanceQueryResult(List.copyOf(builtQuery.outputTuples()));
        } finally {
            if (!uidsWereEnabled) {
                UIDFactory.INSTANCE.disableUIDs();
            }
        }
    }

    private static BuiltQuery buildQuery(
            Query query,
            List<Tuple> inputStream,
            String queryId,
            Settings settings) {
        List<Tuple> outputTuples = Collections.synchronizedList(new ArrayList<>());

        // Source: replay one daily tuple per household/day from the prepared LCL flow dataset.
        Source<Tuple> source = query.addBaseSource(
                "source-" + queryId,
                CollectionSourceFactory.fromList(inputStream, 0L));
        // Active-day filter: remove inactive or nearly empty records before branch scoring.
        Operator<Tuple, Tuple> activeFilter = query.addFilterOperator(
                "active-day-" + queryId,
                tuple -> tuple.getField("f2") >= settings.minDailyKwh()
                        && tuple.getField("f11") < settings.maxZeroHalfHours());

        // Tariff router: Std records use high-load screening; ToU records use evening-peak screening.
        ConditionalTupleRouterOperator tariffRouter = new ConditionalTupleRouterOperator(
                "tariff-router-" + queryId,
                tuple -> tuple.getField("f1") == 0d);
        query.addOperator(tariffRouter);

        // Std alert filter: synthetic broad daily-load risk over all load-profile features.
        Operator<Tuple, Tuple> stdAlertFilter = query.addFilterOperator(
                "std-alert-filter-" + queryId,
                tuple -> stdDailyLoadRisk(tuple, settings) >= settings.stdAlertThreshold());
        // ToU alert filter: synthetic tariff-sensitive evening-peak risk over all load-profile features.
        Operator<Tuple, Tuple> touAlertFilter = query.addFilterOperator(
                "tou-alert-filter-" + queryId,
                tuple -> touEveningPeakRisk(tuple, settings) >= settings.touAlertThreshold());

        // Branch key maps: group all alerting households by day and tariff branch.
        Operator<Tuple, Tuple> stdBranchKey = query.addMapOperator(
                "std-branch-key-" + queryId,
                tuple -> withKey(tuple, "tariff_0"));
        Operator<Tuple, Tuple> touBranchKey = query.addMapOperator(
                "tou-branch-key-" + queryId,
                tuple -> withKey(tuple, "tariff_1"));

        // Summary flat maps: emit one day/tariff alert tuple containing averages for f2..f11 and
        // the branch-specific risk score.
        Operator<Tuple, Tuple> stdAlertSummary = query.addOperator(new FlushableFlatMapOperator<>(
                "std-alert-summary-" + queryId,
                new AllFieldsAlertSummaryFunction(tuple -> stdDailyLoadRisk(tuple, settings))));
        Operator<Tuple, Tuple> touAlertSummary = query.addOperator(new FlushableFlatMapOperator<>(
                "tou-alert-summary-" + queryId,
                new AllFieldsAlertSummaryFunction(tuple -> touEveningPeakRisk(tuple, settings))));

        // Count filters: suppress branch/day summaries with too few alerting households.
        Operator<Tuple, Tuple> stdCountFilter = query.addFilterOperator(
                "std-count-filter-" + queryId,
                tuple -> tuple.getField("f2") >= settings.minAlertHouseholds());
        Operator<Tuple, Tuple> touCountFilter = query.addFilterOperator(
                "tou-count-filter-" + queryId,
                tuple -> tuple.getField("f2") >= settings.minAlertHouseholds());

        // Sinks: collect final semantic outputs from independent Std and ToU branches.
        Sink<Tuple> stdSink = query.addBaseSink("std-sink-" + queryId, event -> {
            if (event != null) {
                outputTuples.add(event);
            }
        });
        Sink<Tuple> touSink = query.addBaseSink("tou-sink-" + queryId, event -> {
            if (event != null) {
                outputTuples.add(event);
            }
        });

        query.connect(source, activeFilter)
                .connect(activeFilter, tariffRouter)
                .connect(tariffRouter, stdAlertFilter)
                .connect(tariffRouter, touAlertFilter)
                .connect(stdAlertFilter, stdBranchKey)
                .connect(stdBranchKey, stdAlertSummary)
                .connect(stdAlertSummary, stdCountFilter)
                .connect(stdCountFilter, stdSink, InactiveBackoff.INSTANCE)
                .connect(touAlertFilter, touBranchKey)
                .connect(touBranchKey, touAlertSummary)
                .connect(touAlertSummary, touCountFilter)
                .connect(touCountFilter, touSink, InactiveBackoff.INSTANCE);

        return new BuiltQuery(outputTuples, List.of(stdSink, touSink));
    }

    private static void runUntilSinksFinish(
            Query query,
            Collection<? extends Sink<?>> sinks,
            long maxWaitMillis,
            String description) {
        query.activate();
        long deadlineMillis = maxWaitMillis <= 0L
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + maxWaitMillis;
        while (sinks.stream().anyMatch(Sink::isEnabled)) {
            if (System.currentTimeMillis() > deadlineMillis) {
                query.deActivate();
                throw new IllegalStateException(description + " did not finish within " + maxWaitMillis + " ms");
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

    static double stdDailyLoadRisk(Tuple tuple, Settings settings) {
        return 0.22d * capRatio(tuple.getField("f2"), STD_DAILY_KWH_REF)
                + 0.18d * capRatio(tuple.getField("f3"), STD_MAX_30_MIN_REF)
                + 0.12d * capRatio(tuple.getField("f4"), STD_MEDIAN_30_MIN_REF)
                + 0.12d * capRatio(tuple.getField("f5"), STD_P90_30_MIN_REF)
                + 0.12d * capRatio(tuple.getField("f6"), STD_STDEV_30_MIN_REF)
                + 0.08d * capRatio(tuple.getField("f7"), STD_EVENING_SHARE_REF)
                + 0.06d * capRatio(tuple.getField("f8"), STD_NIGHT_SHARE_REF)
                + 0.06d * lowRatio(tuple.getField("f9"), STD_LOAD_FACTOR_REF)
                + 0.04d * eveningSlotScore(tuple.getField("f10"), settings)
                + 0.04d * capRatio(tuple.getField("f11"), STD_ZERO_HALF_HOUR_REF);
    }

    static double touEveningPeakRisk(Tuple tuple, Settings settings) {
        return 0.16d * capRatio(tuple.getField("f2"), TOU_DAILY_KWH_REF)
                + 0.12d * capRatio(tuple.getField("f3"), TOU_MAX_30_MIN_REF)
                + 0.08d * capRatio(tuple.getField("f4"), TOU_MEDIAN_30_MIN_REF)
                + 0.14d * capRatio(tuple.getField("f5"), TOU_P90_30_MIN_REF)
                + 0.14d * capRatio(tuple.getField("f6"), TOU_STDEV_30_MIN_REF)
                + 0.20d * capRatio(tuple.getField("f7"), TOU_EVENING_SHARE_REF)
                + 0.08d * lowRatio(tuple.getField("f8"), TOU_NIGHT_SHARE_REF)
                + 0.04d * lowRatio(tuple.getField("f9"), TOU_LOAD_FACTOR_REF)
                + 0.10d * eveningSlotScore(tuple.getField("f10"), settings)
                + 0.04d * capRatio(tuple.getField("f11"), TOU_ZERO_HALF_HOUR_REF);
    }

    private static double capRatio(double value, double reference) {
        return clamp(value / reference, 0d, 2d);
    }

    private static double lowRatio(double value, double reference) {
        return clamp((reference - value) / reference, 0d, 1d);
    }

    private static double eveningSlotScore(double slot, Settings settings) {
        return slot >= settings.eveningPeakStartSlot() && slot <= settings.eveningPeakEndSlot() ? 1d : 0d;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Tuple withKey(Tuple tuple, String key) {
        return new Tuple(tuple.getStimulus(), tuple.getTimestamp(), key, tuple.getFields());
    }

    private record BuiltQuery(List<Tuple> outputTuples, List<Sink<Tuple>> sinks) {
    }

    public record Settings(
            double minDailyKwh,
            double maxZeroHalfHours,
            double stdAlertThreshold,
            double touAlertThreshold,
            double minAlertHouseholds,
            int eveningPeakStartSlot,
            int eveningPeakEndSlot,
            int timeBins,
            int streamCapacity,
            long maxWaitMillis,
            Long instrumentationMinTimestamp,
            Long instrumentationMaxTimestamp) {

        public Settings {
            if (minDailyKwh < 0d || maxZeroHalfHours < 0d || stdAlertThreshold < 0d
                    || touAlertThreshold < 0d || minAlertHouseholds < 0d) {
                throw new IllegalArgumentException("Thresholds cannot be negative");
            }
            if (eveningPeakStartSlot < 0 || eveningPeakEndSlot > 47
                    || eveningPeakStartSlot > eveningPeakEndSlot) {
                throw new IllegalArgumentException("Peak half-hour slot bounds must be in [0, 47]");
            }
            if (timeBins <= 0 || streamCapacity <= 0) {
                throw new IllegalArgumentException("timeBins and streamCapacity must be positive");
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
                    minDailyKwh,
                    maxZeroHalfHours,
                    stdAlertThreshold,
                    touAlertThreshold,
                    minAlertHouseholds,
                    eveningPeakStartSlot,
                    eveningPeakEndSlot,
                    timeBins,
                    streamCapacity,
                    maxWaitMillis,
                    minTimestamp,
                    maxTimestamp);
        }

        public static Settings defaults() {
            return new Settings(
                    1.0d,
                    45d,
                    1.0d,
                    1.05d,
                    4d,
                    34,
                    45,
                    10,
                    10_000,
                    10_000L,
                    null,
                    null);
        }
    }

    public record QueryResult(List<Tuple> outputTuples, StreamFlowInstrumentation.Snapshot flow) {
    }

    public record ProvenanceQueryResult(List<Tuple> outputTuples) {
    }
}
