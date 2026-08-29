package usecase.analysis.performance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class SecondStatsRecorder {

    private final Map<Long, Bucket> buckets = new TreeMap<>();
    private final long warmUpMillis;
    private final long coolDownMillis;
    private final Bucket[] pendingSummaryBuckets;
    private final long[] pendingSummaryMillis;
    private final Bucket summaryBucket = new Bucket();
    private long startMillis;
    private long endMillis;
    private long lastFlushedSummaryMillis = -1L;

    SecondStatsRecorder() {
        this(0L, 0L);
    }

    SecondStatsRecorder(long warmUpMillis, long coolDownMillis) {
        if (warmUpMillis < 0L) {
            throw new IllegalArgumentException("warmUpMillis cannot be negative");
        }
        if (coolDownMillis < 0L) {
            throw new IllegalArgumentException("coolDownMillis cannot be negative");
        }
        if (coolDownMillis > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("coolDownMillis is too large: " + coolDownMillis);
        }
        this.warmUpMillis = warmUpMillis;
        this.coolDownMillis = coolDownMillis;
        int pendingSize = (int) coolDownMillis + 1;
        this.pendingSummaryBuckets = new Bucket[pendingSize];
        this.pendingSummaryMillis = new long[pendingSize];
        for (int i = 0; i < pendingSize; i++) {
            pendingSummaryBuckets[i] = new Bucket();
            pendingSummaryMillis[i] = -1L;
        }
    }

    synchronized void start() {
        startMillis = System.currentTimeMillis();
        endMillis = startMillis;
        lastFlushedSummaryMillis = warmUpMillis - 1L;
    }

    synchronized void stop() {
        endMillis = System.currentTimeMillis();
        flushPendingSummaryBuckets(relativeMillis(endMillis) - coolDownMillis);
    }

    synchronized long elapsedMillis(long nowMillis) {
        if (startMillis == 0L) {
            return 0L;
        }
        return Math.max(0L, nowMillis - startMillis);
    }

    synchronized void recordInput(long nowMillis) {
        bucket(nowMillis).inputCount++;
        recordSummaryInput(nowMillis);
    }

    synchronized void recordOutput(long nowMillis, long latencyMillis) {
        Bucket bucket = bucket(nowMillis);
        bucket.outputCount++;
        bucket.latencyCount++;
        bucket.latencySumMillis += latencyMillis;
        bucket.minLatencyMillis = Math.min(bucket.minLatencyMillis, latencyMillis);
        bucket.maxLatencyMillis = Math.max(bucket.maxLatencyMillis, latencyMillis);
        recordSummaryOutput(nowMillis, latencyMillis);
    }

    synchronized RunSummary summary() {
        long durationMillis = Math.max(0L, endMillis - startMillis - warmUpMillis - coolDownMillis);
        return new RunSummary(
                durationMillis,
                warmUpMillis,
                coolDownMillis,
                summaryBucket.inputCount,
                summaryBucket.outputCount,
                averagePerSecond(summaryBucket.inputCount, durationMillis),
                averagePerSecond(summaryBucket.outputCount, durationMillis),
                summaryBucket.averageLatencyMillis(),
                summaryBucket.latencyCount == 0L ? "" : Long.toString(summaryBucket.minLatencyMillis),
                summaryBucket.latencyCount == 0L ? "" : Long.toString(summaryBucket.maxLatencyMillis));
    }

    synchronized void writePerSecondCsv(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long lastSecond = Math.max(relativeSecond(endMillis), buckets.isEmpty() ? 0L : buckets.keySet().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L));
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("second,input_count,output_count,avg_latency_ms,min_latency_ms,max_latency_ms");
            writer.newLine();
            for (long second = 0L; second <= lastSecond; second++) {
                Bucket bucket = buckets.getOrDefault(second, new Bucket());
                writer.write(Long.toString(second));
                writer.write(',');
                writer.write(Long.toString(bucket.inputCount));
                writer.write(',');
                writer.write(Long.toString(bucket.outputCount));
                writer.write(',');
                writer.write(bucket.averageLatencyMillis());
                writer.write(',');
                writer.write(bucket.latencyCount == 0L ? "" : Long.toString(bucket.minLatencyMillis));
                writer.write(',');
                writer.write(bucket.latencyCount == 0L ? "" : Long.toString(bucket.maxLatencyMillis));
                writer.newLine();
            }
        }
    }

    private Bucket bucket(long nowMillis) {
        return buckets.computeIfAbsent(relativeSecond(nowMillis), ignored -> new Bucket());
    }

    private void recordSummaryInput(long nowMillis) {
        long elapsedMillis = relativeMillis(nowMillis);
        if (elapsedMillis < warmUpMillis) {
            return;
        }
        if (coolDownMillis == 0L) {
            summaryBucket.inputCount++;
            return;
        }
        flushPendingSummaryBuckets(elapsedMillis - coolDownMillis);
        pendingBucket(elapsedMillis).inputCount++;
    }

    private void recordSummaryOutput(long nowMillis, long latencyMillis) {
        long elapsedMillis = relativeMillis(nowMillis);
        if (elapsedMillis < warmUpMillis) {
            return;
        }
        if (coolDownMillis == 0L) {
            summaryBucket.addOutput(latencyMillis);
            return;
        }
        flushPendingSummaryBuckets(elapsedMillis - coolDownMillis);
        pendingBucket(elapsedMillis).addOutput(latencyMillis);
    }

    private Bucket pendingBucket(long elapsedMillis) {
        int slot = (int) (elapsedMillis % pendingSummaryBuckets.length);
        if (pendingSummaryMillis[slot] != elapsedMillis) {
            if (pendingSummaryMillis[slot] >= 0L) {
                summaryBucket.add(pendingSummaryBuckets[slot]);
                pendingSummaryBuckets[slot].clear();
            }
            pendingSummaryMillis[slot] = elapsedMillis;
        }
        return pendingSummaryBuckets[slot];
    }

    private void flushPendingSummaryBuckets(long throughMillis) {
        if (coolDownMillis == 0L || throughMillis <= lastFlushedSummaryMillis) {
            return;
        }
        for (long millis = lastFlushedSummaryMillis + 1L; millis <= throughMillis; millis++) {
            int slot = (int) (millis % pendingSummaryBuckets.length);
            if (pendingSummaryMillis[slot] == millis) {
                summaryBucket.add(pendingSummaryBuckets[slot]);
                pendingSummaryBuckets[slot].clear();
                pendingSummaryMillis[slot] = -1L;
            }
        }
        lastFlushedSummaryMillis = throughMillis;
    }

    private long relativeMillis(long nowMillis) {
        if (startMillis == 0L) {
            return 0L;
        }
        return Math.max(0L, nowMillis - startMillis);
    }

    private long relativeSecond(long nowMillis) {
        if (startMillis == 0L) {
            return 0L;
        }
        return Math.max(0L, (nowMillis - startMillis) / 1000L);
    }

    private static String averagePerSecond(long count, long durationMillis) {
        if (durationMillis <= 0L) {
            return "";
        }
        return formatDouble(1000.0d * count / durationMillis);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    record RunSummary(
            long durationMillis,
            long warmUpMillis,
            long coolDownMillis,
            long inputCount,
            long outputCount,
            String inputThroughputPerSecond,
            String outputThroughputPerSecond,
            String averageLatencyMillis,
            String minLatencyMillis,
            String maxLatencyMillis) {
    }

    private static final class Bucket {

        private long inputCount;
        private long outputCount;
        private long latencyCount;
        private long latencySumMillis;
        private long minLatencyMillis = Long.MAX_VALUE;
        private long maxLatencyMillis = Long.MIN_VALUE;

        private void addOutput(long latencyMillis) {
            outputCount++;
            latencyCount++;
            latencySumMillis += latencyMillis;
            minLatencyMillis = Math.min(minLatencyMillis, latencyMillis);
            maxLatencyMillis = Math.max(maxLatencyMillis, latencyMillis);
        }

        private void add(Bucket other) {
            inputCount += other.inputCount;
            outputCount += other.outputCount;
            latencyCount += other.latencyCount;
            latencySumMillis += other.latencySumMillis;
            minLatencyMillis = Math.min(minLatencyMillis, other.minLatencyMillis);
            maxLatencyMillis = Math.max(maxLatencyMillis, other.maxLatencyMillis);
        }

        private void clear() {
            inputCount = 0L;
            outputCount = 0L;
            latencyCount = 0L;
            latencySumMillis = 0L;
            minLatencyMillis = Long.MAX_VALUE;
            maxLatencyMillis = Long.MIN_VALUE;
        }

        private String averageLatencyMillis() {
            if (latencyCount == 0L) {
                return "";
            }
            return formatDouble(latencySumMillis / (double) latencyCount);
        }
    }
}
