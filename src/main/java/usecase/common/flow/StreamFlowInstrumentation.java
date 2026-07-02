package usecase.common.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import usecase.common.Tuple;

/**
 * Shared event-time recorder for instrumented Liebre streams.
 *
 * <p>Rows are concrete streams, registered by the instrumented stream factory in connection order.
 * Columns are event-time bins over the configured timestamp range.
 */
public final class StreamFlowInstrumentation {

    private final long minTimestamp;
    private final long maxTimestamp;
    private final int timeBins;
    private final List<String> streamNames = new ArrayList<>();
    private final List<long[]> tupleCounts = new ArrayList<>();
    private final List<long[]> keyCounts = new ArrayList<>();
    private final List<Set<String>> currentKeys = new ArrayList<>();
    private final List<Long> lastTimestamps = new ArrayList<>();
    private final List<Integer> lastBins = new ArrayList<>();

    public StreamFlowInstrumentation(long minTimestamp, long maxTimestamp, int timeBins) {
        if (maxTimestamp < minTimestamp) {
            throw new IllegalArgumentException("maxTimestamp cannot be lower than minTimestamp");
        }
        if (timeBins <= 0) {
            throw new IllegalArgumentException("timeBins must be positive");
        }
        this.minTimestamp = minTimestamp;
        this.maxTimestamp = maxTimestamp;
        this.timeBins = timeBins;
    }

    public synchronized int registerStream(String streamName) {
        int row = streamNames.size();
        streamNames.add(streamName);
        tupleCounts.add(new long[timeBins]);
        keyCounts.add(new long[timeBins]);
        currentKeys.add(new HashSet<>());
        lastTimestamps.add(null);
        lastBins.add(null);
        return row;
    }

    public synchronized void record(int row, Object event) {
        if (!(event instanceof Tuple tuple)) {
            return;
        }
        if (row < 0 || row >= streamNames.size()) {
            throw new IllegalArgumentException("Unknown stream row: " + row);
        }
        long timestamp = tuple.getTimestamp();
        Long lastTimestamp = lastTimestamps.get(row);
        if (lastTimestamp != null && timestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "Timestamp decreased for stream row " + row + " (" + streamNames.get(row) + "): "
                            + timestamp + " < " + lastTimestamp);
        }
        int bin = timeBin(timestamp);
        Integer lastBin = lastBins.get(row);
        if (lastBin != null && bin < lastBin) {
            throw new IllegalStateException(
                    "Event-time bin decreased for stream row " + row + " (" + streamNames.get(row) + "): "
                            + bin + " < " + lastBin);
        }
        if (lastBin == null || bin > lastBin) {
            currentKeys.get(row).clear();
        }

        tupleCounts.get(row)[bin]++;
        if (currentKeys.get(row).add(tuple.getKey())) {
            keyCounts.get(row)[bin]++;
        }
        lastTimestamps.set(row, timestamp);
        lastBins.set(row, bin);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                List.copyOf(streamNames),
                minTimestamp,
                maxTimestamp,
                timeBins,
                deepCopy(tupleCounts),
                deepCopy(keyCounts));
    }

    private int timeBin(long timestamp) {
        if (timestamp < minTimestamp || timestamp > maxTimestamp) {
            throw new IllegalArgumentException(
                    "Timestamp " + timestamp + " is outside instrumentation range ["
                            + minTimestamp + ", " + maxTimestamp + "]");
        }
        if (minTimestamp == maxTimestamp) {
            return 0;
        }
        long span = maxTimestamp - minTimestamp + 1L;
        long offset = timestamp - minTimestamp;
        int bin = (int) ((offset * timeBins) / span);
        return Math.min(timeBins - 1, Math.max(0, bin));
    }

    private static long[][] deepCopy(List<long[]> rows) {
        long[][] copy = new long[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            copy[i] = Arrays.copyOf(rows.get(i), rows.get(i).length);
        }
        return copy;
    }

    public record Snapshot(
            List<String> streamNames,
            long minTimestamp,
            long maxTimestamp,
            int timeBins,
            long[][] tupleCounts,
            long[][] keyCounts) {
    }
}
