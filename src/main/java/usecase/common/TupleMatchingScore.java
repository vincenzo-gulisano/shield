package usecase.common;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for comparing two main-query outputs represented as keyed tuples.
 *
 * <p>The key is the pair {@code (timestamp, cohort)}. This fits both planned NHANES outputs:
 * aggregate-stat tuples, where each key is expected to identify one tuple, and outlier tuples,
 * where several tuples can share the same key because a cohort/window can contain many outliers.
 *
 * <p>The class never mutates the input maps or their lists. Matching is computed as a one-to-one
 * maximum bipartite matching inside each key group, so one modified tuple can match at most one
 * original tuple.
 */
public final class TupleMatchingScore {

    private static final double EPSILON = 1e-9;

    public enum DistanceMode {
        ABSOLUTE,
        RELATIVE
    }

    public record Key(long timestamp, String cohort) {
    }

    private TupleMatchingScore() {
    }

    /**
     * Build grouped output from a flat tuple list using each tuple's timestamp and key.
     */
    public static Map<Key, List<Tuple>> groupByTimestampAndKey(List<? extends Tuple> tuples) {
        Map<Key, List<Tuple>> grouped = new LinkedHashMap<>();
        for (Tuple tuple : tuples) {
            Key key = new Key(tuple.getTimestamp(), tuple.getKey());
            grouped.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(tuple);
        }
        return grouped;
    }

    /**
     * Convert an output with one tuple per key into the grouped form used by the scorer.
     */
    public static Map<Key, List<Tuple>> groupSingletons(Map<Key, ? extends Tuple> tuplesByKey) {
        Map<Key, List<Tuple>> grouped = new LinkedHashMap<>();
        for (Map.Entry<Key, ? extends Tuple> entry : tuplesByKey.entrySet()) {
            grouped.put(entry.getKey(), List.of(entry.getValue()));
        }
        return grouped;
    }

    /**
     * Recall-style score: matched original tuples divided by original tuples.
     *
     * <p>This is useful when extra tuples in the modified output should not be penalized.
     */
    public static double recall(
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            List<String> fields,
            double threshold,
            DistanceMode distanceMode) {
        validateInputs(original, modified, fields, threshold, distanceMode);

        int originalCount = countTuples(original);
        if (originalCount == 0) {
            return 1.0;
        }

        int truePositives = countMatches(original, modified, fields, threshold, distanceMode);
        return (double) truePositives / originalCount;
    }

    /**
     * F1-style score over matched tuples.
     *
     * <p>This penalizes both missing original tuples and extra unmatched tuples in the modified
     * output. It is usually the better choice for outlier semantics.
     */
    public static double f1(
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            List<String> fields,
            double threshold,
            DistanceMode distanceMode) {
        validateInputs(original, modified, fields, threshold, distanceMode);

        int originalCount = countTuples(original);
        int modifiedCount = countTuples(modified);
        if (originalCount == 0 || modifiedCount == 0) {
            return originalCount == modifiedCount ? 1.0 : 0.0;
        }

        int truePositives = countMatches(original, modified, fields, threshold, distanceMode);
        int falseNegatives = originalCount - truePositives;
        int falsePositives = modifiedCount - truePositives;

        double precision = (double) truePositives / (truePositives + falsePositives);
        double recall = (double) truePositives / (truePositives + falseNegatives);
        if (precision + recall == 0.0) {
            return 0.0;
        }
        return 2.0 * precision * recall / (precision + recall);
    }

    private static int countMatches(
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            List<String> fields,
            double threshold,
            DistanceMode distanceMode) {
        int matches = 0;
        for (Map.Entry<Key, List<Tuple>> entry : original.entrySet()) {
            List<Tuple> modifiedTuples = modified.getOrDefault(entry.getKey(), List.of());
            matches += countMaxMatches(entry.getValue(), modifiedTuples, fields, threshold, distanceMode);
        }
        return matches;
    }

    private static int countMaxMatches(
            List<Tuple> original,
            List<Tuple> modified,
            List<String> fields,
            double threshold,
            DistanceMode distanceMode) {
        int[] matchedOriginalByModified = new int[modified.size()];
        Arrays.fill(matchedOriginalByModified, -1);

        int matches = 0;
        for (int i = 0; i < original.size(); i++) {
            boolean[] seenModified = new boolean[modified.size()];
            if (tryMatch(i, original, modified, fields, threshold, distanceMode,
                    matchedOriginalByModified, seenModified)) {
                matches++;
            }
        }
        return matches;
    }

    private static boolean tryMatch(
            int originalIndex,
            List<Tuple> original,
            List<Tuple> modified,
            List<String> fields,
            double threshold,
            DistanceMode distanceMode,
            int[] matchedOriginalByModified,
            boolean[] seenModified) {
        for (int modifiedIndex = 0; modifiedIndex < modified.size(); modifiedIndex++) {
            if (seenModified[modifiedIndex]
                    || !tuplesMatch(original.get(originalIndex), modified.get(modifiedIndex),
                    fields, threshold, distanceMode)) {
                continue;
            }
            seenModified[modifiedIndex] = true;
            if (matchedOriginalByModified[modifiedIndex] == -1
                    || tryMatch(matchedOriginalByModified[modifiedIndex], original, modified, fields,
                    threshold, distanceMode, matchedOriginalByModified, seenModified)) {
                matchedOriginalByModified[modifiedIndex] = originalIndex;
                return true;
            }
        }
        return false;
    }

    private static boolean tuplesMatch(
            Tuple original,
            Tuple modified,
            List<String> fields,
            double threshold,
            DistanceMode distanceMode) {
        for (String field : fields) {
            if (!valuesMatch(original.lookup(field), modified.lookup(field), threshold, distanceMode)) {
                return false;
            }
        }
        return true;
    }

    private static boolean valuesMatch(
            double original,
            double modified,
            double threshold,
            DistanceMode distanceMode) {
        if (Double.isNaN(original) || Double.isNaN(modified)) {
            return Double.isNaN(original) && Double.isNaN(modified);
        }
        double absoluteError = Math.abs(original - modified);
        return switch (distanceMode) {
            case ABSOLUTE -> absoluteError <= threshold;
            case RELATIVE -> absoluteError / Math.max(Math.abs(original), EPSILON) <= threshold;
        };
    }

    private static int countTuples(Map<Key, List<Tuple>> tuplesByKey) {
        int count = 0;
        for (List<Tuple> tuples : tuplesByKey.values()) {
            count += tuples.size();
        }
        return count;
    }

    private static void validateInputs(
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            List<String> fields,
            double threshold,
            DistanceMode distanceMode) {
        if (original == null || modified == null) {
            throw new IllegalArgumentException("Input maps cannot be null");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Fields cannot be null or empty");
        }
        if (threshold < 0.0 || Double.isNaN(threshold)) {
            throw new IllegalArgumentException("Threshold must be a non-negative number");
        }
        if (distanceMode == null) {
            throw new IllegalArgumentException("Distance mode cannot be null");
        }
    }
}
