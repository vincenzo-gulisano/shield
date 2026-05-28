package usecase.nhanes;

import java.util.ArrayList;
import java.util.List;
import usecase.nhanes.TupleFeatureStats.FeatureStats;
import usecase.common.Tuple;

/**
 * Computes robust multivariate outlier scores for tuples in one window/group.
 *
 * <p>The score uses all tuple fields {@code f1..fN}. Each field is normalized as
 * {@code (value - median) / IQR}, where {@code IQR = p75 - p25}, and the final score is the
 * Euclidean norm of the normalized vector. Larger scores mean the tuple is farther from the
 * window/group center.
 */
public final class OutlierDetector {

    private static final double EPSILON = 1e-9;

    /**
     * A tuple paired with its multivariate outlier score.
     */
    public record ScoredTuple(Tuple tuple, double score) {
    }

    private OutlierDetector() {
    }

    /**
     * Score every tuple using medians and IQRs computed from the provided tuples.
     *
     * <p>Use this when the caller wants to keep all scores and decide later which ones should be
     * emitted as outliers.
     */
    public static List<ScoredTuple> scoreTuples(List<Tuple> tuples) {
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        TupleFeatureStats stats = TupleFeatureStats.compute(tuples);
        List<ScoredTuple> scoredTuples = new ArrayList<>(tuples.size());
        for (Tuple tuple : tuples) {
            validateNumFields(tuple, stats.features().size());
            scoredTuples.add(new ScoredTuple(tuple, score(tuple, stats.features())));
        }
        return scoredTuples;
    }

    /**
     * Find outliers whose score is greater than or equal to an absolute threshold.
     *
     * <p>Each returned tuple has {@code outputTimestamp}, the original tuple key, and one field:
     * {@code f1 = outlier_score}.
     */
    public static List<Tuple> findOutliersAboveScore(
            List<Tuple> tuples,
            long outputTimestamp,
            double minScore) {
        if (Double.isNaN(minScore)) {
            throw new IllegalArgumentException("minScore cannot be NaN");
        }
        List<Tuple> outliers = new ArrayList<>();
        for (ScoredTuple scoredTuple : scoreTuples(tuples)) {
            if (scoredTuple.score() >= minScore) {
                outliers.add(new Tuple(outputTimestamp, scoredTuple.tuple().getKey(), scoredTuple.score()));
            }
        }
        return outliers;
    }

    /**
     * Find outliers by thresholding scores at a quantile of the window/group score distribution.
     *
     * <p>For example, {@code scoreQuantile = 0.95} emits tuples whose score is at least the
     * within-window/group p95 score. Each returned tuple has {@code outputTimestamp}, the original
     * tuple key, and one field: {@code f1 = outlier_score}.
     */
    public static List<Tuple> findOutliersAtOrAboveScoreQuantile(
            List<Tuple> tuples,
            long outputTimestamp,
            double scoreQuantile) {
        if (scoreQuantile < 0.0 || scoreQuantile > 1.0 || Double.isNaN(scoreQuantile)) {
            throw new IllegalArgumentException("scoreQuantile must be in [0, 1]");
        }

        List<ScoredTuple> scoredTuples = scoreTuples(tuples);
        if (scoredTuples.isEmpty()) {
            return List.of();
        }

        List<Double> scores = new ArrayList<>(scoredTuples.size());
        for (ScoredTuple scoredTuple : scoredTuples) {
            scores.add(scoredTuple.score());
        }
        scores.sort(Double::compareTo);
        double minScore = TupleFeatureStats.percentile(scores, scoreQuantile);

        List<Tuple> outliers = new ArrayList<>();
        for (ScoredTuple scoredTuple : scoredTuples) {
            if (scoredTuple.score() >= minScore) {
                outliers.add(new Tuple(outputTimestamp, scoredTuple.tuple().getKey(), scoredTuple.score()));
            }
        }
        return outliers;
    }

    private static double score(Tuple tuple, List<FeatureStats> features) {
        double sumSquared = 0.0;
        int validDimensions = 0;
        for (int i = 0; i < features.size(); i++) {
            double value = tuple.getField("f" + (i + 1));
            FeatureStats feature = features.get(i);
            if (Double.isNaN(value) || Double.isNaN(feature.median())) {
                continue;
            }
            double denominator = Math.max(Math.abs(feature.iqr()), EPSILON);
            double robustZ = (value - feature.median()) / denominator;
            sumSquared += robustZ * robustZ;
            validDimensions++;
        }
        return validDimensions == 0 ? Double.NaN : Math.sqrt(sumSquared);
    }

    private static void validateNumFields(Tuple tuple, int expectedNumFields) {
        if (tuple.getNumFields() != expectedNumFields) {
            throw new IllegalArgumentException(
                    "Expected " + expectedNumFields + " fields, found " + tuple.getNumFields());
        }
    }
}
