package usecase.lcl;

import java.util.List;
import metrics.privacy.KAnonymityPrivacyCardinality;
import problem.utils.PrivacyMetricChoice;
import usecase.common.Tuple;
import usecase.common.analysis.QueryResult;

public final class LclUseCaseSmokeTest {

    private LclUseCaseSmokeTest() {
    }

    public static void main(String[] args) {
        List<Tuple> tuples = LclTupleLoader.load();
        if (tuples.isEmpty()) {
            throw new IllegalStateException("LCL loader returned no tuples");
        }
        if (tuples.get(0).getNumFields() != 3) {
            throw new IllegalStateException("Expected 3 LCL fields, got " + tuples.get(0).getNumFields());
        }

        QueryResult result = MainQuery.process(tuples, "smoke");
        if (result.outputAggregatedStats().isEmpty()) {
            throw new IllegalStateException("LCL main query produced no aggregate stats");
        }
        if (result.outputOutliers().isEmpty()) {
            throw new IllegalStateException("LCL main query produced no outliers");
        }

        List<String> attributes = List.of(Tuple.getFieldNames(tuples.get(0).getNumFields()));
        KAnonymityPrivacyCardinality privacyMetric = new KAnonymityPrivacyCardinality(tuples, 3, attributes);
        new LclStreamAnonymizationProblem(
                "datasets/lcl_10days_100keys.csv",
                PrivacyMetricChoice.K_ANONYMITY_CARDINALITY_MAX,
                0.05,
                0.05,
                50);

        System.out.printf("lclInput=%d%n", tuples.size());
        System.out.printf("lclAggregateStats=%d%n", result.outputAggregatedStats().size());
        System.out.printf("lclOutliers=%d%n", result.outputOutliers().size());
        System.out.printf("lclPrivacyK3Max=%f%n", privacyMetric.applyWithMax(tuples, tuples));
        System.out.println("lclProblemInitialized=true");
        System.exit(0);
    }
}
