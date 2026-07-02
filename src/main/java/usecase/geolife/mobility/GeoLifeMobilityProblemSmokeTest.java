package usecase.geolife.mobility;

import problem.utils.PrivacyMetricChoice;
import usecase.common.TupleMatchingScore;
import usecase.common.TupleMatchingScore.DistanceMode;
import usecase.common.flow.StreamFlowSnapshotSimilarity;

public final class GeoLifeMobilityProblemSmokeTest {

    private GeoLifeMobilityProblemSmokeTest() {
    }

    public static void main(String[] args) {
        GeoLifeMobilityStreamAnonymizationProblem problem = new GeoLifeMobilityStreamAnonymizationProblem(
                GeoLifeTupleReader.DEFAULT_RESOURCE,
                PrivacyMetricChoice.K_ANONYMITY_CARDINALITY_Q99,
                0.02,
                20);
        GeoLifeMobilityMainQuery.QueryResult baseline = problem.baselineOutcome();
        require(!baseline.outputTuples().isEmpty(), "Expected non-empty GeoLife hotspot output");
        require(!baseline.flow().streamNames().isEmpty(), "Expected non-empty GeoLife flow snapshot");

        double identitySemantics = TupleMatchingScore.f1(
                TupleMatchingScore.groupByTimestampAndKey(baseline.outputTuples()),
                TupleMatchingScore.groupByTimestampAndKey(baseline.outputTuples()),
                0.02,
                DistanceMode.RELATIVE);
        double identityFidelity = new StreamFlowSnapshotSimilarity(baseline.flow()).apply(baseline.flow());

        require(identitySemantics == 1.0d, "Expected identity semantics to be 1.0");
        require(identityFidelity == 1.0d, "Expected identity fidelity to be 1.0");
        System.out.printf(
                "GeoLife mobility problem smoke passed: outputs=%d streams=%d%n",
                baseline.outputTuples().size(),
                baseline.flow().streamNames().size());
        System.exit(0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
