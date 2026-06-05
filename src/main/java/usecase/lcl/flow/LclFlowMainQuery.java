package usecase.lcl.flow;

import common.util.backoff.InactiveBackoff;
import component.operator.Operator;
import component.sink.Sink;
import component.source.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import query.Query;
import query.LiebreContext;
import query.utils.FlushableFlatMapOperator;
import query.utils.ConditionalTupleRouterOperator;
import scheduling.basic.BasicLiebreScheduler;
import stream.BackoffStreamFactory;
import usecase.common.CollectionSourceFactory;
import usecase.common.Tuple;

public final class LclFlowMainQuery {

    static {
        LiebreContext.setSingleQueryExecution(false);
    }

    private LclFlowMainQuery() {
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

        List<Tuple> outputTuples = Collections.synchronizedList(new ArrayList<>());

        Source<Tuple> source = query.addBaseSource(
                "source-" + queryId,
                CollectionSourceFactory.fromList(inputStream, 0L));
        Operator<Tuple, Tuple> activeFilter = query.addFilterOperator(
                "active-day-" + queryId,
                tuple -> tuple.getField("f2") >= settings.minDailyKwh()
                        && tuple.getField("f11") < settings.maxZeroHalfHours());

        ConditionalTupleRouterOperator tariffRouter = new ConditionalTupleRouterOperator(
                "tariff-router-" + queryId,
                tuple -> tuple.getField("f1") == 0d);
        query.addOperator(tariffRouter);

        Operator<Tuple, Tuple> stdAlertFilter = query.addFilterOperator(
                "std-alert-filter-" + queryId,
                tuple -> isLoadShapeAlert(tuple, settings));
        Operator<Tuple, Tuple> touAlertFilter = query.addFilterOperator(
                "tou-alert-filter-" + queryId,
                tuple -> isLoadShapeAlert(tuple, settings));

        Operator<Tuple, Tuple> stdBranchKey = query.addMapOperator(
                "std-branch-key-" + queryId,
                tuple -> withKey(tuple, "tariff_0"));
        Operator<Tuple, Tuple> touBranchKey = query.addMapOperator(
                "tou-branch-key-" + queryId,
                tuple -> withKey(tuple, "tariff_1"));

        Operator<Tuple, Tuple> stdAlertSummary = query.addOperator(new FlushableFlatMapOperator<>(
                "std-alert-summary-" + queryId,
                new SameTimestampAlertSummaryFunction()));
        Operator<Tuple, Tuple> touAlertSummary = query.addOperator(new FlushableFlatMapOperator<>(
                "tou-alert-summary-" + queryId,
                new SameTimestampAlertSummaryFunction()));

        Operator<Tuple, Tuple> stdCountFilter = query.addFilterOperator(
                "std-count-filter-" + queryId,
                tuple -> tuple.getField("f2") >= settings.minAlertHouseholds());
        Operator<Tuple, Tuple> touCountFilter = query.addFilterOperator(
                "tou-count-filter-" + queryId,
                tuple -> tuple.getField("f2") >= settings.minAlertHouseholds());

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

        query.activate();
        long deadlineMillis = settings.maxWaitMillis() <= 0L
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + settings.maxWaitMillis();
        while (stdSink.isEnabled() || touSink.isEnabled()) {
            if (System.currentTimeMillis() > deadlineMillis) {
                query.deActivate();
                throw new IllegalStateException(
                        "LCL flow query did not finish within " + settings.maxWaitMillis() + " ms");
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        query.deActivate();

        return new QueryResult(List.copyOf(outputTuples), instrumentation.snapshot());
    }

    private static boolean isLoadShapeAlert(Tuple tuple, Settings settings) {
        boolean highDailyLoad = tuple.getField("f2") >= settings.highDailyKwh();
        boolean highPeakLoad = tuple.getField("f3") >= settings.highMax30MinKwh();
        boolean eveningHeavy = tuple.getField("f7") >= settings.highEveningShare();
        boolean peakInEvening = tuple.getField("f10") >= settings.eveningPeakStartSlot()
                && tuple.getField("f10") <= settings.eveningPeakEndSlot();
        return highDailyLoad && (highPeakLoad || eveningHeavy || peakInEvening);
    }

    private static Tuple withKey(Tuple tuple, String key) {
        return new Tuple(tuple.getStimulus(), tuple.getTimestamp(), key, tuple.getFields());
    }

    public record Settings(
            double minDailyKwh,
            double highDailyKwh,
            double highMax30MinKwh,
            double highEveningShare,
            double minAlertHouseholds,
            double maxZeroHalfHours,
            int eveningPeakStartSlot,
            int eveningPeakEndSlot,
            int timeBins,
            int streamCapacity,
            long maxWaitMillis,
            Long instrumentationMinTimestamp,
            Long instrumentationMaxTimestamp) {

        public Settings {
            if (minDailyKwh < 0d || highDailyKwh < 0d || highMax30MinKwh < 0d
                    || highEveningShare < 0d || minAlertHouseholds < 0d || maxZeroHalfHours < 0d) {
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
                    highDailyKwh,
                    highMax30MinKwh,
                    highEveningShare,
                    minAlertHouseholds,
                    maxZeroHalfHours,
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
                    12.0d,
                    1.0d,
                    0.40d,
                    4d,
                    45d,
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
}
