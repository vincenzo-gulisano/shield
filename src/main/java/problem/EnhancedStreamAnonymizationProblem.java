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

package problem;

import io.github.ericmedvet.jgea.core.problem.SimpleMOProblem;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import java.util.Comparator;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.function.Function;
import mappers.QueryMapper.Arc;
import mappers.QueryMapper.OperatorRepresentation;
import problem.utils.PrivacyMetricChoice;

public class EnhancedStreamAnonymizationProblem implements
    SimpleMOProblem<Graph<OperatorRepresentation, Arc>, Double> {

  public EnhancedStreamAnonymizationProblem(String inputCsvPath, PrivacyMetricChoice privacyMetric) {
    // TODO implement the constructor

  }

  private final static SequencedMap<String, Comparator<Double>> OBJECTIVES = new TreeMap<>(
      Map.ofEntries(
          Map.entry("privacy", ((Comparator<Double>) Double::compareTo).reversed()),
          Map.entry("results-similarity", ((Comparator<Double>) Double::compareTo).reversed()),
          Map.entry("performance-similarity", ((Comparator<Double>) Double::compareTo).reversed())));

  @Override
  public SequencedMap<String, Comparator<Double>> comparators() {
    return OBJECTIVES;
  }

  @Override
  public Function<Graph<OperatorRepresentation, Arc>, SequencedMap<String, Double>> qualityFunction() {
    return g -> {
      // TODO 1. transform the graph in a liebre query
      // TODO 2. load the data and start the engine
      // TODO 3. do stuff and return metrics
      return new TreeMap<>(Map.of());
    };
  }
}