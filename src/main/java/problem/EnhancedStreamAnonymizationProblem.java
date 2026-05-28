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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import metrics.performance.PerformanceSimilarity;
import metrics.performance.utils.StreamStatsWindow;
import metrics.privacy.KAnonymityPrivacyCardinality;
import metrics.results.F1Score;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import problem.utils.PrivacyMetricChoice;
import query.LiebreAnonymizationQueryFromGraph;
import query.LiebreContext;
import usecase.common.Tuple;
import usecase.forkjoin.synthetic.MainQuery;
import usecase.forkjoin.synthetic.MainQuery.QueryResult;

public class EnhancedStreamAnonymizationProblem implements
    SimpleMOProblem<Graph<OperatorRepresentation, ArcType>, Double> {

  private static final Logger logger = LoggerFactory.getLogger(EnhancedStreamAnonymizationProblem.class);

  // Define a static counter for unique query ID
  private static final AtomicLong queryCounter = new AtomicLong(0);

  static {
    // Notify the Terminator not to end after the first query has completed
    LiebreContext.setSingleQueryExecution(false);
  }

  private final long minTs, maxTs;
  private final PrivacyMetricChoice privacyMetricChoice;
  private final QueryResult mainQueryResults;
  private final KAnonymityPrivacyCardinality privacyMetricCalculator;
  private final Distance<StreamStatsWindow> fidelityMetricCalculator;
  private final F1Score semanticsMetricCalculator;

  private final List<Tuple> inputTuples;

  public EnhancedStreamAnonymizationProblem(String inputCsvPath,
      PrivacyMetricChoice privacyMetric) {

    logger.info(
        "Initializing the EnhancedStreamAnonymizationProblem with input CSV: {}, and privacy metric: {}",
        inputCsvPath, privacyMetric);

    logger.info("Loading input tuples from {}", inputCsvPath);
    inputTuples = loadTuples(inputCsvPath);

    logger.info("Executing the main query to get the original results and performance metrics");
    this.minTs = inputTuples.getFirst().getTimestamp();
    this.maxTs = inputTuples.getLast().getTimestamp();
    this.mainQueryResults = MainQuery.process(inputTuples, "main", minTs, maxTs);
    logger.info(
        "Main query executed successfully, returning {} results, and the following metrics:\n{}",
        mainQueryResults.events().size(), mainQueryResults.statsWindow());

    this.privacyMetricChoice = privacyMetric;
    List<String> attributes = List.of(Tuple.getFieldNames(inputTuples.get(0).getNumFields()));
    this.privacyMetricCalculator = new KAnonymityPrivacyCardinality(inputTuples, 50, attributes);
    this.fidelityMetricCalculator = new PerformanceSimilarity(mainQueryResults.statsWindow(), true);
    this.semanticsMetricCalculator = new F1Score(0.1, attributes);

    logger.info("Empty query privacy, fidelity, and semantics scores: {}, {}, and {}",
        privacyMetricCalculator.applyWithQuantile99(inputTuples, inputTuples),
        fidelityMetricCalculator.apply(mainQueryResults.statsWindow(),
            mainQueryResults.statsWindow()),
        semanticsMetricCalculator.apply(mainQueryResults.events(), mainQueryResults.events()));

  }

  private final static SequencedMap<String, Comparator<Double>> OBJECTIVES = new TreeMap<>(
      Map.ofEntries(
          Map.entry("privacy", ((Comparator<Double>) Double::compareTo).reversed()),
          Map.entry("semantics", ((Comparator<Double>) Double::compareTo).reversed()),
          Map.entry("fidelity", ((Comparator<Double>) Double::compareTo).reversed())));

  @Override
  public SequencedMap<String, Comparator<Double>> comparators() {
    return OBJECTIVES;
  }

  private List<Tuple> loadTuples(String inputCsvPath) {
    List<Tuple> tuples = new ArrayList<>();
    try (BufferedReader reader = openTupleCsv(inputCsvPath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }

        String[] parts = line.split(",");
        if (parts.length != 3) {
          throw new IllegalArgumentException(
              "Expected 3 CSV columns at line " + line + " in " + inputCsvPath + ", found "
                  + parts.length);
        }

        try {
          long timestamp = Long.parseLong(parts[0].trim());
          double f1 = Double.parseDouble(parts[1].trim());
          double f2 = Double.parseDouble(parts[2].trim());
          tuples.add(new Tuple(timestamp, f1, f2));
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              "Invalid tuple values at line " + line + " in " + inputCsvPath, e);
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read input CSV: " + inputCsvPath, e);
    }
    return tuples;
  }

  private BufferedReader openTupleCsv(String inputCsvPath) throws IOException {
    Path path = Path.of(inputCsvPath);
    if (Files.exists(path)) {
      return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }

    InputStream is = getClass().getClassLoader().getResourceAsStream(inputCsvPath);
    if (is == null) {
      throw new IOException("Input CSV not found as file or classpath resource: " + inputCsvPath);
    }
    return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
  }

  /*
   * The remaining part contains only helper functions
   */

  @Override
  public Function<Graph<OperatorRepresentation, ArcType>, SequencedMap<String, Double>> qualityFunction() {
    return g -> {
      SequencedMap<String, Double> qualities = new TreeMap<>();
      Long counter = queryCounter.getAndIncrement();

      String queryId = String.valueOf(counter);
      try {
        // Create an executable Liebre query and execute this anonymization query
        LiebreAnonymizationQueryFromGraph liebreExecutor = new LiebreAnonymizationQueryFromGraph();

        logger.info("Starting the processing of anonimization query #{}", counter);
        long startTime = System.currentTimeMillis();
        List<Tuple> modifiedEvents = liebreExecutor.processAnonymizationQuery(g, inputTuples);
        logger.info(
            "Finished processing anonymization query #{}, input tuples: {}, output tuples: {}, total time: {}s",
            counter,
            inputTuples.size(), modifiedEvents.size(),
            (System.currentTimeMillis() - startTime) / 1000.0);

        double privacyScore;
        // Based on the user choice, calculate the correct privacy metric
        switch (privacyMetricChoice) {
          case K_ANONYMITY_CARDINALITY_MAX ->
              privacyScore = privacyMetricCalculator.applyWithMax(inputTuples, modifiedEvents);
          case K_ANONYMITY_CARDINALITY_Q99 ->
              privacyScore = privacyMetricCalculator.applyWithQuantile99(inputTuples,
                  modifiedEvents);
          case K_ANONYMITY_CARDINALITY ->
              privacyScore = privacyMetricCalculator.apply(inputTuples, modifiedEvents);
          default -> privacyScore = privacyMetricCalculator.apply(inputTuples, modifiedEvents);
        }
        qualities.put("privacy", privacyScore);

        // Case with empty modified datastream
        if (modifiedEvents.isEmpty()) {
          qualities.put("semantics", 0.0);
          StreamStatsWindow emptyStats = new StreamStatsWindow(
              mainQueryResults.statsWindow().streamNames(),
              mainQueryResults.statsWindow().minTimestamp(),
              mainQueryResults.statsWindow().maxTimestamp(),
              mainQueryResults.statsWindow().getResolutionMillis());
          qualities.put("fidelity",
              fidelityMetricCalculator.apply(mainQueryResults.statsWindow(), emptyStats));

        } else {

          logger.info("Starting the processing of modified data on main query #{}", counter);
          startTime = System.currentTimeMillis();
          QueryResult modifiedOutcome = MainQuery.process(modifiedEvents, queryId, minTs, maxTs);
          logger.info(
              "Finished processing modified data on main query #{}, input tuples: {}, output tuples: {}, total time: {}s",
              counter, modifiedEvents.size(), modifiedOutcome.events().size(),
              (System.currentTimeMillis() - startTime) / 1000.0);

          StreamStatsWindow modifiedStats = modifiedOutcome.statsWindow();
          qualities.put("fidelity",
              fidelityMetricCalculator.apply(mainQueryResults.statsWindow(), modifiedStats));
          qualities.put("semantics",
              semanticsMetricCalculator.apply(mainQueryResults.events(), modifiedOutcome.events()));

        }
        return qualities;

      } catch (Exception e) {
        throw new RuntimeException("Error executing query " + queryId + " for graph " + g, e);
      }
    };
  }

}
