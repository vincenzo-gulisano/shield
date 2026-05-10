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

import io.github.ericmedvet.jgea.core.distance.Distance;
import io.github.ericmedvet.jgea.core.problem.SimpleMOProblem;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import metrics.performance.PerformanceSimilarity;
import metrics.performance.utils.StreamStatsWindow;
import metrics.privacy.KAnonymityPrivacyCardinality;
import metrics.results.F1Score;
import problem.utils.PrivacyMetricChoice;
import usecase.common.Tuple;
import usecase.forkjoin.synthetic.MainQuery;
import usecase.forkjoin.synthetic.MainQuery.QueryResult;

public class EnhancedStreamAnonymizationProblem implements
    SimpleMOProblem<Graph<OperatorRepresentation, ArcType>, Double> {

  private static final Logger logger = LoggerFactory.getLogger(StreamAnonymizationProblem.class);

  private final KAnonymityPrivacyCardinality privacyMetricCalculator;
  private final Distance<StreamStatsWindow> fidelityMetricCalculator;
  private final F1Score semanticsMetricCalculator;

  private final List<Tuple> inputTuples;

  public EnhancedStreamAnonymizationProblem(String inputCsvPath, PrivacyMetricChoice privacyMetric) {

    logger.info("Loading input tuples from {}", inputCsvPath);
    inputTuples = loadTuples(inputCsvPath);

    logger.info("Executing the main query to get the original results and performance metrics");
    long minTs = inputTuples.getFirst().getTimestamp();
    long maxTs = inputTuples.getLast().getTimestamp();
    QueryResult mainQueryResults = MainQuery.process(inputTuples, "main", minTs, maxTs);
    logger.info("Main query executed successfully, returning {} results, and the following metrics:\n{}",
        mainQueryResults.events().size(), mainQueryResults.statsWindow());

    List<String> attributes = List.of(Tuple.getFieldNames(inputTuples.get(0).getNumFields()));
    this.privacyMetricCalculator = new KAnonymityPrivacyCardinality(inputTuples, 50, attributes);
    this.fidelityMetricCalculator = new PerformanceSimilarity(mainQueryResults.statsWindow(), true);
    this.semanticsMetricCalculator = new F1Score(0.1, attributes);
    
    logger.info("Empty query privacy, fidelity, and semantics scores: {}, {}, and {}",
        privacyMetricCalculator.applyWithQuantile99(inputTuples, inputTuples),
        fidelityMetricCalculator.apply(mainQueryResults.statsWindow(), mainQueryResults.statsWindow()),
        semanticsMetricCalculator.apply(mainQueryResults.events(), mainQueryResults.events()));

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
  public Function<Graph<OperatorRepresentation, ArcType>, SequencedMap<String, Double>> qualityFunction() {
    return g -> {
      // TODO 1. transform the graph in a liebre query
      // TODO 2. load the data and start the engine
      // TODO 3. do stuff and return metrics
      return new TreeMap<>(Map.of());
    };
  }

  /*
   * The remaining part contains only helper functions
   */

  private List<Tuple> loadTuples(String inputCsvPath) {
    List<Tuple> tuples = new ArrayList<>();
    InputStream is = getClass().getClassLoader().getResourceAsStream(inputCsvPath);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }

        String[] parts = line.split(",");
        if (parts.length != 3) {
          throw new IllegalArgumentException(
              "Expected 3 CSV columns at line " + line + " in " + inputCsvPath + ", found " + parts.length);
        }

        try {
          long timestamp = Long.parseLong(parts[0].trim());
          double f1 = Double.parseDouble(parts[1].trim());
          double f2 = Double.parseDouble(parts[2].trim());
          tuples.add(new Tuple(timestamp, f1, f2));
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Invalid tuple values at line " + line + " in " + inputCsvPath, e);
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read input CSV: " + inputCsvPath, e);
    }
    return tuples;
  }

}
