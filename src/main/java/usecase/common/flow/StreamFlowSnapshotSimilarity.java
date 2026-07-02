package usecase.common.flow;

import java.util.Arrays;
import java.util.List;

public final class StreamFlowSnapshotSimilarity {

    private static final double TUPLE_WORST_CASE_ERROR = 2.0;
    private static final double KEY_WORST_CASE_ERROR = 1.0;

    private final StreamFlowInstrumentation.Snapshot originalProfile;
    private final double dMax;

    public StreamFlowSnapshotSimilarity(StreamFlowInstrumentation.Snapshot originalProfile) {
        if (originalProfile == null) {
            throw new IllegalArgumentException("Original profile cannot be null");
        }
        this.originalProfile = copyOf(originalProfile);
        this.dMax = calculateDMax(this.originalProfile);
    }

    public double apply(StreamFlowInstrumentation.Snapshot modifiedProfile) {
        if (modifiedProfile == null) {
            return 0.0;
        }
        validateCompatible(originalProfile, modifiedProfile);
        double distance = Math.sqrt(
                sumOfSquaredRelativeErrors(originalProfile.tupleCounts(), modifiedProfile.tupleCounts())
                        + sumOfSquaredRelativeErrors(originalProfile.keyCounts(), modifiedProfile.keyCounts()));
        if (dMax == 0.0) {
            return distance == 0.0 ? 1.0 : 0.0;
        }
        return Math.max(0.0, 1.0 - distance / dMax);
    }

    public static StreamFlowInstrumentation.Snapshot emptyLike(StreamFlowInstrumentation.Snapshot snapshot) {
        return new StreamFlowInstrumentation.Snapshot(
                snapshot.streamNames(),
                snapshot.minTimestamp(),
                snapshot.maxTimestamp(),
                snapshot.timeBins(),
                new long[snapshot.tupleCounts().length][snapshot.timeBins()],
                new long[snapshot.keyCounts().length][snapshot.timeBins()]);
    }

    private static StreamFlowInstrumentation.Snapshot copyOf(StreamFlowInstrumentation.Snapshot snapshot) {
        return new StreamFlowInstrumentation.Snapshot(
                List.copyOf(snapshot.streamNames()),
                snapshot.minTimestamp(),
                snapshot.maxTimestamp(),
                snapshot.timeBins(),
                copyMatrix(snapshot.tupleCounts()),
                copyMatrix(snapshot.keyCounts()));
    }

    private static long[][] copyMatrix(long[][] matrix) {
        long[][] copy = new long[matrix.length][];
        for (int row = 0; row < matrix.length; row++) {
            copy[row] = Arrays.copyOf(matrix[row], matrix[row].length);
        }
        return copy;
    }

    private static double calculateDMax(StreamFlowInstrumentation.Snapshot originalProfile) {
        double sum = 0.0;
        sum += worstCaseSum(originalProfile.tupleCounts(), TUPLE_WORST_CASE_ERROR);
        sum += worstCaseSum(originalProfile.keyCounts(), KEY_WORST_CASE_ERROR);
        return Math.sqrt(sum);
    }

    private static double worstCaseSum(long[][] matrix, double worstCaseError) {
        double sum = 0.0;
        for (long[] row : matrix) {
            for (long value : row) {
                if (value > 0L) {
                    sum += worstCaseError * worstCaseError;
                }
            }
        }
        return sum;
    }

    private static double sumOfSquaredRelativeErrors(long[][] original, long[][] modified) {
        double sum = 0.0;
        for (int row = 0; row < original.length; row++) {
            for (int col = 0; col < original[row].length; col++) {
                sum += squaredRelativeError(original[row][col], modified[row][col]);
            }
        }
        return sum;
    }

    private static double squaredRelativeError(long original, long modified) {
        if (original == 0L) {
            return modified == 0L ? 0.0 : 1.0;
        }
        double relativeError = Math.abs((double) original - modified) / original;
        return relativeError * relativeError;
    }

    private static void validateCompatible(
            StreamFlowInstrumentation.Snapshot originalProfile,
            StreamFlowInstrumentation.Snapshot modifiedProfile) {
        if (originalProfile == null) {
            throw new IllegalArgumentException("Original profile cannot be null");
        }
        if (originalProfile.minTimestamp() != modifiedProfile.minTimestamp()
                || originalProfile.maxTimestamp() != modifiedProfile.maxTimestamp()
                || originalProfile.timeBins() != modifiedProfile.timeBins()
                || originalProfile.streamNames().size() != modifiedProfile.streamNames().size()) {
            throw new IllegalArgumentException("Flow snapshots are not compatible");
        }
        validateMatrixShape(originalProfile.streamNames(), originalProfile.tupleCounts(), originalProfile.timeBins());
        validateMatrixShape(originalProfile.streamNames(), originalProfile.keyCounts(), originalProfile.timeBins());
        validateMatrixShape(modifiedProfile.streamNames(), modifiedProfile.tupleCounts(), modifiedProfile.timeBins());
        validateMatrixShape(modifiedProfile.streamNames(), modifiedProfile.keyCounts(), modifiedProfile.timeBins());
    }

    private static void validateMatrixShape(List<String> streamNames, long[][] matrix, int timeBins) {
        if (matrix.length != streamNames.size()) {
            throw new IllegalArgumentException("Matrix row count does not match stream names");
        }
        for (long[] row : matrix) {
            if (row.length != timeBins) {
                throw new IllegalArgumentException("Matrix column count does not match time bins");
            }
        }
    }
}
