package usecase.nhanes;

import java.util.List;
import java.util.Map;
import metrics.privacy.KAnonymityPrivacyCardinality;
import metrics.privacy.LinkageAttackPrivacy;
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
        testLinkageScores();
        runMainQuery();
    }

    private static void printPrivacyScore() {
        List<Tuple> tuples = NhanesTupleLoader.load();
        List<String> attributes = List.of(Tuple.getFieldNames(tuples.getFirst().getNumFields()));
        for (int k=2; k<=100; k++) {
            KAnonymityPrivacyCardinality privacyMetricCalculator =
                    new KAnonymityPrivacyCardinality(tuples, k, attributes);
            System.out.printf("privacy@k=%d: %f (99) %f (max)%n", k,
                    privacyMetricCalculator.applyWithQuantile99(tuples, tuples),
                    privacyMetricCalculator.applyWithMax(tuples, tuples));
        }
        LinkageAttackPrivacy linkageAttackPrivacy = new LinkageAttackPrivacy(tuples, 50, attributes);
        System.out.printf("linkage@k=50: %f (expected) %f (top-k)%n",
                linkageAttackPrivacy.applyExpectedSuccess(tuples),
                linkageAttackPrivacy.applyTopKContainment(tuples));
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

    private static void runMainQuery() {
        List<Tuple> tuples = NhanesTupleLoader.load();
        MainQuery mainQuery = new MainQuery();
        MainQuery.QueryResult result = mainQuery.process(tuples, "smoke");
        int aggregateStatsCount = result.outputAggregatedStats().size();
        int outlierCount = result.outputOutliers().size();
        System.out.printf("mainQueryInput=%d%n", tuples.size());
        System.out.printf("mainQueryAggregateStats=%d%n", aggregateStatsCount);
        System.out.printf("mainQueryOutliers=%d%n", outlierCount);
        System.out.printf("mainQueryTotalOutput=%d%n", aggregateStatsCount + outlierCount);
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

    private static void testLinkageScores() {
        List<Tuple> original = List.of(
                linkedTuple(0L, 0.0),
                linkedTuple(1L, 10.0),
                linkedTuple(2L, 20.0));

        LinkageAttackPrivacy k1 = new LinkageAttackPrivacy(original, 1, List.of("f1"));
        checkScore("linkage top-k original k=1", k1.applyTopKContainment(original), 0.0);
        checkScore("linkage expected original k=1", k1.applyExpectedSuccess(original), 0.0);
        checkScore("linkage top-k empty", k1.applyTopKContainment(List.of()), 1.0);
        checkScore("linkage expected empty", k1.applyExpectedSuccess(List.of()), 1.0);

        LinkageAttackPrivacy k2 = new LinkageAttackPrivacy(original, 2, List.of("f1"));
        checkScore("linkage top-k original k=2", k2.applyTopKContainment(original), 0.0);
        checkScore("linkage expected original k=2", k2.applyExpectedSuccess(original), 0.5);

        List<Tuple> modifiedWithDuplicate = List.of(
                linkedTuple(0L, 0.0),
                linkedTuple(0L, 100.0),
                linkedTuple(1L, 100.0));
        checkScore("linkage top-k ignores duplicate ids",
                k1.applyTopKContainment(modifiedWithDuplicate), 0.5);
        checkScore("linkage expected ignores duplicate ids",
                k1.applyExpectedSuccess(modifiedWithDuplicate), 0.5);
    }

    private static Tuple linkedTuple(long linkageId, double... fields) {
        return new Tuple(0L, "", fields).withLinkageId(linkageId);
    }

    private static void checkScore(String name, double actual, double expected) {
        if (Math.abs(actual - expected) > SCORE_TOLERANCE) {
            throw new IllegalStateException(
                    "Unexpected score for " + name + ": expected " + expected + ", got " + actual);
        }
        System.out.printf("%s=%f%n", name, actual);
    }
}
