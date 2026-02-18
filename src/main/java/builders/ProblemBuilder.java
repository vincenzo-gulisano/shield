package builders;

import io.github.ericmedvet.jnb.core.Cacheable;
import io.github.ericmedvet.jnb.core.Discoverable;
import io.github.ericmedvet.jnb.core.Param;
import problem.StreamAnonymizationProblem;
import problem.StreamAnonymizationProblem_2ObjectivesPerf;
import problem.StreamAnonymizationProblem_2ObjectivesRes;
import problem.utils.PrivacyMetricChoice;

@Discoverable(prefixTemplate = "silvia.problem")
public class ProblemBuilder {

    private ProblemBuilder() {
    }

    // Create a problem with 3 objectives
    @SuppressWarnings("unused")
    @Cacheable
    public static StreamAnonymizationProblem anonymizationProblem(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("grammarPath") String grammarPath,
            @Param(value = "privacyMetric", dS = "K_ANONYMITY_CARDINALITY") PrivacyMetricChoice privacyMetric,
            @Param(value = "keyColumn", dS = "") String keyColumn,
            @Param(value = "name", iS = "{inputCsvPath}") String name
    ) throws Exception {
        boolean isFilterOnly = grammarPath.toLowerCase().contains("filters");
        return new StreamAnonymizationProblem(inputCsvPath, keyColumn, privacyMetric, isFilterOnly);
    }

    // Create a problem with 2 objectives: results similarity and privacy
    @SuppressWarnings("unused")
    @Cacheable
    public static StreamAnonymizationProblem_2ObjectivesRes anonymizationProblem2O(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("grammarPath") String grammarPath,
            @Param(value = "privacyMetric", dS = "K_ANONYMITY_CARDINALITY") PrivacyMetricChoice privacyMetric,
            @Param(value = "keyColumn", dS = "") String keyColumn,
            @Param(value = "name", iS = "{inputCsvPath}") String name
    ) throws Exception {
        return new StreamAnonymizationProblem_2ObjectivesRes(inputCsvPath, keyColumn, privacyMetric);
    }

    // Create a problem with 2 objectives: performance similarity and privacy
    @SuppressWarnings("unused")
    @Cacheable
    public static StreamAnonymizationProblem_2ObjectivesPerf anonymizationProblem2OPerf(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("grammarPath") String grammarPath,
            @Param(value = "privacyMetric", dS = "K_ANONYMITY_CARDINALITY") PrivacyMetricChoice privacyMetric,
            @Param(value = "keyColumn", dS = "") String keyColumn,
            @Param(value = "name", iS = "{inputCsvPath}") String name
    ) throws Exception {
        boolean isFilterOnly = grammarPath.toLowerCase().contains("filters");
        return new StreamAnonymizationProblem_2ObjectivesPerf(inputCsvPath, keyColumn, privacyMetric, isFilterOnly);
    }
}
