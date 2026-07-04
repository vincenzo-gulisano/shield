package metrics.privacy;

import grammar.generator.FieldType;
import java.util.List;
import java.util.Map;
import usecase.common.Tuple;

public final class LinkageAttackTrueRankSmokeTest {

    private LinkageAttackTrueRankSmokeTest() {
    }

    public static void main(String[] args) {
        smokeContinuousKdTreePath();
        smokeNominalScanPath();
        System.out.println("Linkage attack true-rank smoke passed");
        System.exit(0);
    }

    private static void smokeContinuousKdTreePath() {
        List<Tuple> originals = List.of(
                tuple(0, 0d),
                tuple(1, 1d),
                tuple(2, 2d));
        LinkageAttackPrivacy privacy = new LinkageAttackPrivacy(originals, 2, List.of("f1"));

        assertClose(0.0, privacy.applyTrueRankScore(originals), "identity continuous score");
        assertClose(2d / 3d, privacy.applyTrueRankScore(List.of(tuple(0, 2d))), "rank-three continuous score");
    }

    private static void smokeNominalScanPath() {
        List<Tuple> originals = List.of(
                tuple(0, 0d),
                tuple(1, 1d),
                tuple(2, 2d));
        LinkageAttackPrivacy privacy = new LinkageAttackPrivacy(
                originals,
                2,
                List.of("f1"),
                Map.of("f1", FieldType.NOMINAL_CATEGORICAL));

        assertClose(0.0, privacy.applyTrueRankScore(originals), "identity nominal score");
        assertClose(2d / 3d, privacy.applyTrueRankScore(List.of(tuple(0, 2d))), "rank-three nominal score");
    }

    private static Tuple tuple(long linkageId, double value) {
        return new Tuple(0L, "k" + linkageId, value).withLinkageId(linkageId);
    }

    private static void assertClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 1e-12) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
