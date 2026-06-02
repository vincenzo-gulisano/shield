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

package usecase.nhanes;

import grammar.generator.FieldType;
import java.util.List;
import java.util.Map;
import problem.utils.PrivacyMetricChoice;
import usecase.common.analysis.TupleStreamAnonymizationProblem;

public class NhanesStreamAnonymizationProblem extends TupleStreamAnonymizationProblem {

  static final List<String> LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES =
      List.of("f1", "f2", "f3");
  static final Map<String, FieldType> LINKAGE_ATTACK_QUASI_IDENTIFIER_TYPES = Map.of(
      "f1", FieldType.NOMINAL_CATEGORICAL,
      "f2", FieldType.DISCRETE_NUMERIC,
      "f3", FieldType.NOMINAL_CATEGORICAL);

  public NhanesStreamAnonymizationProblem(
      String inputCsvPath,
      PrivacyMetricChoice privacyMetric,
      double fidelityF1Threshold,
      double semanticsF1Threshold,
      int k) {

    super(
        "NHANES",
        inputCsvPath,
        NhanesTupleLoader.load(inputCsvPath),
        MainQuery::process,
        privacyMetric,
        fidelityF1Threshold,
        semanticsF1Threshold,
        k,
        LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES,
        LINKAGE_ATTACK_QUASI_IDENTIFIER_TYPES);
  }
}
