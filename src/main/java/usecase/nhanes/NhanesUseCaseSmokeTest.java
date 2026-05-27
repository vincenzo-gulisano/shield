package usecase.nhanes;

import java.util.List;
import java.util.Map;
import metrics.privacy.KAnonymityPrivacyCardinality;
import usecase.common.Tuple;
import usecase.common.TupleMatchingScore;
import usecase.common.TupleMatchingScore.DistanceMode;
import usecase.common.TupleMatchingScore.Key;

public final class NhanesUseCaseSmokeTest {

    private static final double SCORE_TOLERANCE = 1e-9;

    private NhanesUseCaseSmokeTest() {
    }

    public static void main(String[] args) {
        printPrivacyScore();
        testF1Scores();
    }

    private static void printPrivacyScore() {
        List<Tuple> tuples = NhanesTupleLoader.load();
        List<String> attributes = List.of(Tuple.getFieldNames(tuples.getFirst().getNumFields()));
        KAnonymityPrivacyCardinality privacyMetricCalculator =
                new KAnonymityPrivacyCardinality(tuples, 50, attributes);

        System.out.printf("privacy=%f%n",
                privacyMetricCalculator.applyWithQuantile99(tuples, tuples));
    }

    private static void testF1Scores() {
        checkF1("perfect match",
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0)),
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0)),
                0.0,
                1.0);

        checkF1("one missing tuple",
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0)),
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "b", 30.0)),
                0.0,
                0.8);

        checkF1("one extra tuple",
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0)),
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0),
                        new Tuple(0L, "b", 99.0)),
                0.0,
                6.0 / 7.0);

        checkF1("one value outside threshold",
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "b", 20.0)),
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "b", 25.0)),
                0.1,
                0.5);
    }

    private static Map<Key, List<Tuple>> outliers(Tuple... tuples) {
        return TupleMatchingScore.groupByTimestampAndKey(List.of(tuples));
    }

    private static void checkF1(
            String name,
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            double threshold,
            double expected) {
        double actual = TupleMatchingScore.f1(original, modified, threshold, DistanceMode.ABSOLUTE);
        if (Math.abs(actual - expected) > SCORE_TOLERANCE) {
            throw new IllegalStateException(
                    "Unexpected F1 score for " + name + ": expected " + expected + ", got " + actual);
        }
        System.out.printf("f1[%s]=%f%n", name, actual);
    }
}
