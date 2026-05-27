package usecase.common;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for comparing two main-query outputs represented as keyed tuples.
 *
 * <p>The key is the pair {@code (timestamp, tuple key)}. Some outputs may have at most one tuple
 * per key, while others may have several tuples with the same timestamp and key.
 *
 * <p>The class never mutates the input maps or their lists. Matching is computed as a one-to-one
 * maximum bipartite matching inside each key group, so one modified tuple can match at most one
 * original tuple.
 */
public final class TupleMatchingScore {

    private static final double EPSILON = 1e-9;

    /**
     * Defines how numeric tuple fields are compared against the provided threshold.
     *
     * <p>Use {@link #ABSOLUTE} when the fields already share a meaningful absolute scale. Use
     * {@link #RELATIVE} when a percentage-like tolerance is more appropriate.
     */
    public enum DistanceMode {
        ABSOLUTE,
        RELATIVE
    }

    /**
     * Grouping key used by the scorer.
     *
     * <p>It is built from the tuple timestamp and tuple key, so callers do not need a separate
     * domain-specific identifier such as a cohort field.
     */
    public record Key(long timestamp, String key) {
    }

    /**
     * Static utility class; instances are not needed.
     */
    private TupleMatchingScore() {
    }

    /**
     * Build grouped output from a flat tuple list using each tuple's timestamp and key.
     *
     * <p>Use this when a main query naturally returns a {@code List<Tuple>} and the scorer needs
     * the grouped representation {@code Map<Key, List<Tuple>>}. Multiple tuples with the same
     * timestamp/key are preserved in the list for that key.
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
     * Recall-style score: matched original tuples divided by original tuples.
     *
     * <p>Use this when missing expected tuples should reduce the score, but extra tuples in the
     * modified output should not be penalized. This is suitable for outputs where false positives
     * are not important for the objective.
     */
    public static double recall(
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            double threshold,
            DistanceMode distanceMode) {
        validateInputs(original, modified, threshold, distanceMode);

        int originalCount = countTuples(original);
        if (originalCount == 0) {
            return 1.0;
        }

        int truePositives = countMatches(original, modified, threshold, distanceMode);
        return (double) truePositives / originalCount;
    }

    /**
     * F1-style score over matched tuples.
     *
     * <p>This penalizes both missing original tuples and extra unmatched tuples in the modified
     * output. Use this when false positives and false negatives should both reduce the score, for
     * example when comparing outlier outputs.
     */
    public static double f1(
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            double threshold,
            DistanceMode distanceMode) {
        validateInputs(original, modified, threshold, distanceMode);

        int originalCount = countTuples(original);
        int modifiedCount = countTuples(modified);
        if (originalCount == 0 || modifiedCount == 0) {
            return originalCount == modifiedCount ? 1.0 : 0.0;
        }

        int truePositives = countMatches(original, modified, threshold, distanceMode);
        int falseNegatives = originalCount - truePositives;
        int falsePositives = modifiedCount - truePositives;

        double precision = (double) truePositives / (truePositives + falsePositives);
        double recall = (double) truePositives / (truePositives + falseNegatives);
        if (precision + recall == 0.0) {
            return 0.0;
        }
        return 2.0 * precision * recall / (precision + recall);
    }

    /**
     * Count all one-to-one tuple matches across all keys.
     *
     * <p>Used internally by both {@link #recall(Map, Map, double, DistanceMode)} and
     * {@link #f1(Map, Map, double, DistanceMode)}. Each key group is matched independently, so a
     * tuple can only match another tuple with the same timestamp/key.
     */
    private static int countMatches(
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            double threshold,
            DistanceMode distanceMode) {
        int matches = 0;
        for (Map.Entry<Key, List<Tuple>> entry : original.entrySet()) {
            List<Tuple> modifiedTuples = modified.getOrDefault(entry.getKey(), List.of());
            matches += countMaxMatches(entry.getValue(), modifiedTuples, threshold, distanceMode);
        }
        return matches;
    }

    /**
     * Count the maximum number of one-to-one matches inside a single key group.
     *
     * <p>Use this internally when several tuples share the same key. It finds the best pairing
     * between original and modified tuples instead of greedily consuming the first acceptable
     * modified tuple.
     */
    private static int countMaxMatches(
            List<Tuple> original,
            List<Tuple> modified,
            double threshold,
            DistanceMode distanceMode) {
        int[] matchedOriginalByModified = new int[modified.size()];
        Arrays.fill(matchedOriginalByModified, -1);

        int matches = 0;
        for (int i = 0; i < original.size(); i++) {
            boolean[] seenModified = new boolean[modified.size()];
            if (tryMatch(i, original, modified, threshold, distanceMode,
                    matchedOriginalByModified, seenModified)) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * Recursive augmenting-path step used by the per-key maximum matching.
     *
     * <p>This is the standard small bipartite-matching routine behind {@link #countMaxMatches}.
     * It tries to assign one original tuple to a compatible modified tuple, reassigning previous
     * matches when that increases the total number of matches.
     */
    private static boolean tryMatch(
            int originalIndex,
            List<Tuple> original,
            List<Tuple> modified,
            double threshold,
            DistanceMode distanceMode,
            int[] matchedOriginalByModified,
            boolean[] seenModified) {
        for (int modifiedIndex = 0; modifiedIndex < modified.size(); modifiedIndex++) {
            if (seenModified[modifiedIndex]
                    || !tuplesMatch(original.get(originalIndex), modified.get(modifiedIndex),
                    threshold, distanceMode)) {
                continue;
            }
            seenModified[modifiedIndex] = true;
            if (matchedOriginalByModified[modifiedIndex] == -1
                    || tryMatch(matchedOriginalByModified[modifiedIndex], original, modified,
                    threshold, distanceMode, matchedOriginalByModified, seenModified)) {
                matchedOriginalByModified[modifiedIndex] = originalIndex;
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether two tuples match under the configured distance rule.
     *
     * <p>Used by the matching routine. It compares all tuple fields {@code f1..fN}; if the tuples
     * do not have the same number of fields, comparison is invalid and an exception is thrown.
     */
    private static boolean tuplesMatch(
            Tuple original,
            Tuple modified,
            double threshold,
            DistanceMode distanceMode) {
        if (original.getNumFields() != modified.getNumFields()) {
            throw new IllegalArgumentException(
                    "Cannot compare tuples with different field counts: "
                            + original.getNumFields() + " and " + modified.getNumFields());
        }
        for (int i = 1; i <= original.getNumFields(); i++) {
            String field = "f" + i;
            if (!valuesMatch(original.lookup(field), modified.lookup(field), threshold, distanceMode)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check whether two numeric field values are close enough.
     *
     * <p>Used by {@link #tuplesMatch(Tuple, Tuple, double, DistanceMode)} for each field. NaN
     * values only match other NaN values.
     */
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

    /**
     * Count the total number of tuples stored in a grouped output map.
     *
     * <p>Used to compute recall, precision, and the empty-output edge cases.
     */
    private static int countTuples(Map<Key, List<Tuple>> tuplesByKey) {
        int count = 0;
        for (List<Tuple> tuples : tuplesByKey.values()) {
            count += tuples.size();
        }
        return count;
    }

    /**
     * Validate common scorer inputs before matching starts.
     *
     * <p>Used by the public scoring methods to fail early on null maps, invalid thresholds, or a
     * missing distance mode.
     */
    private static void validateInputs(
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            double threshold,
            DistanceMode distanceMode) {
        if (original == null || modified == null) {
            throw new IllegalArgumentException("Input maps cannot be null");
        }
        if (threshold < 0.0 || Double.isNaN(threshold)) {
            throw new IllegalArgumentException("Threshold must be a non-negative number");
        }
        if (distanceMode == null) {
            throw new IllegalArgumentException("Distance mode cannot be null");
        }
    }
}
