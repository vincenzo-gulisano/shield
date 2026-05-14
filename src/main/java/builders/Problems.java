/*
 * Copyright 2026 eric
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package builders;

import io.github.ericmedvet.jnb.core.Cacheable;
import io.github.ericmedvet.jnb.core.Discoverable;
import io.github.ericmedvet.jnb.core.Param;
import problem.EnhancedStreamAnonymizationProblem;
import problem.StreamAnonymizationProblem;
import problem.StreamAnonymizationProblem_2ObjectivesPerf;
import problem.StreamAnonymizationProblem_2ObjectivesRes;
import problem.utils.PrivacyMetricChoice;

@Discoverable(prefixTemplate = "anonym.problem")
public class Problems {

    private Problems() {
    }

    // Create a problem with 3 objectives
    @SuppressWarnings("unused")
    @Cacheable
    public static StreamAnonymizationProblem anonymizationProblem(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("grammarPath") String grammarPath,
            @Param(value = "privacyMetric", dS = "K_ANONYMITY_CARDINALITY") PrivacyMetricChoice privacyMetric,
            @Param(value = "keyColumn", dS = "") String keyColumn,
            @Param(value = "name", iS = "{inputCsvPath}") String name) throws Exception {
        boolean isFilterOnly = grammarPath.toLowerCase().contains("filters");
        return new StreamAnonymizationProblem(inputCsvPath, keyColumn, privacyMetric, isFilterOnly);
    }

    @Cacheable
    public static EnhancedStreamAnonymizationProblem enhancedStreamAnonymizationProblem(
            @Param("inputCsvPath") String inputCsvPath,
            @Param(value = "privacyMetric", dS = "K_ANONYMITY_CARDINALITY") PrivacyMetricChoice privacyMetric,
            @Param(value = "name", iS = "{inputCsvPath}") String name) {

        // TODO add more parameters, e.g. to specify which ops to use without changing grammar
        return new EnhancedStreamAnonymizationProblem(inputCsvPath, privacyMetric);
    }

    // Create a problem with 3 objectives
    @SuppressWarnings("unused")
    @Cacheable
    public static StreamAnonymizationProblem medicalDataAnonymizationProblem(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("grammarPath") String grammarPath,
            @Param(value = "privacyMetric", dS = "K_ANONYMITY_CARDINALITY") PrivacyMetricChoice privacyMetric,
            @Param(value = "keyColumn", dS = "") String keyColumn,
            @Param(value = "name", iS = "{inputCsvPath}") String name) throws Exception {
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
            @Param(value = "name", iS = "{inputCsvPath}") String name) throws Exception {
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
            @Param(value = "name", iS = "{inputCsvPath}") String name) throws Exception {
        boolean isFilterOnly = grammarPath.toLowerCase().contains("filters");
        return new StreamAnonymizationProblem_2ObjectivesPerf(inputCsvPath, keyColumn, privacyMetric, isFilterOnly);
    }
}