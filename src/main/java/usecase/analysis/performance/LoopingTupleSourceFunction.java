package usecase.analysis.performance;

import component.source.SourceFunction;
import java.util.List;
import usecase.common.Tuple;

final class LoopingTupleSourceFunction implements SourceFunction<Tuple> {

    private final List<Tuple> baseTuples;
    private final long minRunMillis;
    private final long timestampPeriod;
    private final SecondStatsRecorder stats;
    private int index;
    private long loop;
    private boolean enabled;
    private boolean finished;

    LoopingTupleSourceFunction(List<Tuple> baseTuples, long minRunMillis, SecondStatsRecorder stats) {
        if (baseTuples == null || baseTuples.isEmpty()) {
            throw new IllegalArgumentException("baseTuples cannot be null or empty");
        }
        if (minRunMillis <= 0L) {
            throw new IllegalArgumentException("minRunMillis must be positive");
        }
        this.baseTuples = baseTuples;
        this.minRunMillis = minRunMillis;
        this.timestampPeriod = timestampPeriod(baseTuples);
        this.stats = stats;
    }

    @Override
    public Tuple get() {
        if (finished) {
            return null;
        }
        long nowMillis = System.currentTimeMillis();
        if (index == 0 && loop > 0L && stats.elapsedMillis(nowMillis) >= minRunMillis) {
            finished = true;
            return null;
        }

        Tuple original = baseTuples.get(index);
        Tuple copy = copyForLoop(original, nowMillis, original.getTimestamp() + loop * timestampPeriod);
        stats.recordInput(nowMillis);
        index++;
        if (index == baseTuples.size()) {
            index = 0;
            loop++;
        }
        return copy;
    }

    @Override
    public boolean isInputFinished() {
        return finished;
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void disable() {
        enabled = false;
    }

    @Override
    public boolean canRun() {
        return enabled && !finished;
    }

    private static Tuple copyForLoop(Tuple original, long stimulusMillis, long timestamp) {
        Tuple copy = new Tuple(stimulusMillis, timestamp, original.getKey(), original.getFields());
        if (original.hasLinkageId()) {
            return copy.withLinkageId(original.getLinkageId());
        }
        return copy;
    }

    private static long timestampPeriod(List<Tuple> tuples) {
        long minTimestamp = tuples.stream().mapToLong(Tuple::getTimestamp).min().orElseThrow();
        long maxTimestamp = tuples.stream().mapToLong(Tuple::getTimestamp).max().orElseThrow();
        return Math.max(1L, maxTimestamp - minTimestamp + 1L);
    }
}
