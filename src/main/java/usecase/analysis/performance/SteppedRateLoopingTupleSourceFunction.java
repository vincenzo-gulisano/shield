package usecase.analysis.performance;

import component.source.SourceFunction;
import java.util.List;
import usecase.common.Tuple;

final class SteppedRateLoopingTupleSourceFunction implements SourceFunction<Tuple> {

    private final List<Tuple> baseTuples;
    private final double startRatePerSecond;
    private final double finalRatePerSecond;
    private final int steps;
    private final long stepMillis;
    private final long bucketMillis;
    private final long totalRunMillis;
    private final long timestampPeriod;
    private final SecondStatsRecorder stats;
    private int index;
    private long loop;
    private int remainingInBucket;
    private double tupleCredit;
    private boolean enabled;
    private boolean finished;
    private long nextBucketStartNanos;

    SteppedRateLoopingTupleSourceFunction(
            List<Tuple> baseTuples,
            double startRatePerSecond,
            double finalRatePerSecond,
            int steps,
            long stepMillis,
            long bucketMillis,
            SecondStatsRecorder stats) {
        if (baseTuples == null || baseTuples.isEmpty()) {
            throw new IllegalArgumentException("baseTuples cannot be null or empty");
        }
        if (startRatePerSecond <= 0.0d || finalRatePerSecond <= 0.0d) {
            throw new IllegalArgumentException("rates must be positive");
        }
        if (steps <= 0) {
            throw new IllegalArgumentException("steps must be positive");
        }
        if (stepMillis <= 0L) {
            throw new IllegalArgumentException("stepMillis must be positive");
        }
        if (bucketMillis <= 0L) {
            throw new IllegalArgumentException("bucketMillis must be positive");
        }
        this.baseTuples = baseTuples;
        this.startRatePerSecond = startRatePerSecond;
        this.finalRatePerSecond = finalRatePerSecond;
        this.steps = steps;
        this.stepMillis = stepMillis;
        this.bucketMillis = Math.min(bucketMillis, stepMillis);
        this.totalRunMillis = steps * stepMillis;
        this.timestampPeriod = timestampPeriod(baseTuples);
        this.stats = stats;
    }

    @Override
    public Tuple get() {
        if (finished) {
            return null;
        }
        while (remainingInBucket == 0 && !finished) {
            fillNextBucket();
        }
        if (finished) {
            return null;
        }
        long nowMillis = System.currentTimeMillis();
        if (stats.elapsedMillis(nowMillis) >= totalRunMillis) {
            finished = true;
            return null;
        }

        Tuple original = baseTuples.get(index);
        Tuple copy = copyForLoop(original, nowMillis, original.getTimestamp() + loop * timestampPeriod);
        stats.recordInput(nowMillis);
        remainingInBucket--;
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
        nextBucketStartNanos = System.nanoTime();
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

    private void fillNextBucket() {
        while (!finished) {
            long remainingNanos = nextBucketStartNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                long nowMillis = System.currentTimeMillis();
                long elapsedMillis = stats.elapsedMillis(nowMillis);
                if (elapsedMillis >= totalRunMillis) {
                    finished = true;
                    return;
                }
                long currentBucketMillis = Math.min(bucketMillis, totalRunMillis - elapsedMillis);
                tupleCredit += rateAt(elapsedMillis) * currentBucketMillis / 1000.0d;
                remainingInBucket = (int) Math.floor(tupleCredit);
                tupleCredit -= remainingInBucket;
                nextBucketStartNanos = System.nanoTime() + currentBucketMillis * 1_000_000L;
                if (remainingInBucket > 0) {
                    return;
                }
                continue;
            }
            try {
                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                Thread.sleep(millis, nanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = true;
            }
        }
    }

    private double rateAt(long elapsedMillis) {
        int step = (int) Math.min(steps - 1L, Math.max(0L, elapsedMillis / stepMillis));
        if (steps == 1) {
            return startRatePerSecond;
        }
        double ratio = step / (double) (steps - 1);
        return startRatePerSecond + ratio * (finalRatePerSecond - startRatePerSecond);
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
