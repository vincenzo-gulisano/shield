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

import io.github.ericmedvet.jgea.core.problem.SimpleMOProblem;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import metrics.privacy.KAnonymityPrivacyCardinality;
import metrics.privacy.LinkageAttackPrivacy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import problem.utils.PrivacyMetricChoice;
import query.LiebreAnonymizationQueryFromGraph;
import query.LiebreContext;
import usecase.common.Tuple;
import usecase.common.TupleMatchingScore;
import usecase.common.TupleMatchingScore.DistanceMode;
import usecase.nhanes.MainQuery.QueryResult;

public class NhanesStreamAnonymizationProblem implements
    SimpleMOProblem<Graph<OperatorRepresentation, ArcType>, Double> {

  private static final Logger logger = LoggerFactory.getLogger(NhanesStreamAnonymizationProblem.class);

  // Define a static counter for unique query ID
  private static final AtomicLong queryCounter = new AtomicLong(0);
  static final List<String> LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES =
      List.of("f1", "f2", "f3", "f4");

  static {
    // Notify the Terminator not to end after the first query has completed
    LiebreContext.setSingleQueryExecution(false);
  }

  private final PrivacyMetricChoice privacyMetricChoice;
  private final QueryResult mainQueryResults;
  private final KAnonymityPrivacyCardinality privacyMetricCalculator;
  private final LinkageAttackPrivacy linkageAttackPrivacyCalculator;
  private final double fidelityF1Threshold;
  private final double semanticsF1Threshold;

  private final List<Tuple> inputTuples;

  public NhanesStreamAnonymizationProblem(String inputCsvPath,
      PrivacyMetricChoice privacyMetric,
      double fidelityF1Threshold,
      double semanticsF1Threshold,
      int k) {

    logger.info(
        "Initializing the NhanesStreamAnonymizationProblem with input CSV: {}, privacy metric: {}, k: {}, fidelity F1 threshold: {}, and semantics F1 threshold: {}",
        inputCsvPath, privacyMetric, k, fidelityF1Threshold, semanticsF1Threshold);

    inputTuples = NhanesTupleLoader.load(inputCsvPath);
    logger.info("Loaded {} tuples from {}", inputTuples.size(), inputCsvPath);

    logger.info("Executing the main query to get the original results and performance metrics");
    this.mainQueryResults = MainQuery.process(inputTuples, "main");
    logger.info(
        "Main query executed successfully, returning {} aggregate stats and {} outliers",
        mainQueryResults.outputAggregatedStats().size(), mainQueryResults.outputOutliers().size());

    this.privacyMetricChoice = privacyMetric;
    this.fidelityF1Threshold = fidelityF1Threshold;
    this.semanticsF1Threshold = semanticsF1Threshold;
    List<String> allAttributes = List.of(Tuple.getFieldNames(inputTuples.get(0).getNumFields()));
    this.privacyMetricCalculator = usesKAnonymityMetric(privacyMetric)
        ? new KAnonymityPrivacyCardinality(inputTuples, k, allAttributes)
        : null;
    this.linkageAttackPrivacyCalculator = usesLinkageAttackMetric(privacyMetric)
        ? new LinkageAttackPrivacy(inputTuples, k, LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES)
        : null;

    logger.info("Empty query privacy, fidelity, and semantics scores: {}, {}, and {}",
        privacyScore(inputTuples),
        TupleMatchingScore.f1(TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputAggregatedStats()),
            TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputAggregatedStats()), fidelityF1Threshold,
            DistanceMode.RELATIVE),
        TupleMatchingScore.f1(TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputOutliers()),
            TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputOutliers()), semanticsF1Threshold,
            DistanceMode.RELATIVE));

  }

  private double privacyScore(List<Tuple> modifiedEvents) {
    return switch (privacyMetricChoice) {
      case K_ANONYMITY_CARDINALITY_MAX -> requireKAnonymityCalculator().applyWithMax(inputTuples, modifiedEvents);
      case K_ANONYMITY_CARDINALITY_Q99 -> requireKAnonymityCalculator().applyWithQuantile99(inputTuples,
          modifiedEvents);
      case K_ANONYMITY_CARDINALITY -> requireKAnonymityCalculator().apply(inputTuples, modifiedEvents);
      case LINKAGE_ATTACK_EXPECTED_SUCCESS -> requireLinkageAttackCalculator().applyExpectedSuccess(modifiedEvents);
      case LINKAGE_ATTACK_TOP_K_CONTAINMENT -> requireLinkageAttackCalculator().applyTopKContainment(modifiedEvents);
    };
  }

  private KAnonymityPrivacyCardinality requireKAnonymityCalculator() {
    if (privacyMetricCalculator == null) {
      throw new IllegalStateException("K-anonymity calculator is not initialized for " + privacyMetricChoice);
    }
    return privacyMetricCalculator;
  }

  private LinkageAttackPrivacy requireLinkageAttackCalculator() {
    if (linkageAttackPrivacyCalculator == null) {
      throw new IllegalStateException("Linkage attack calculator is not initialized for " + privacyMetricChoice);
    }
    return linkageAttackPrivacyCalculator;
  }

  private static boolean usesKAnonymityMetric(PrivacyMetricChoice privacyMetricChoice) {
    return switch (privacyMetricChoice) {
      case K_ANONYMITY_CARDINALITY, K_ANONYMITY_CARDINALITY_MAX, K_ANONYMITY_CARDINALITY_Q99 -> true;
      case LINKAGE_ATTACK_EXPECTED_SUCCESS, LINKAGE_ATTACK_TOP_K_CONTAINMENT -> false;
    };
  }

  private static boolean usesLinkageAttackMetric(PrivacyMetricChoice privacyMetricChoice) {
    return switch (privacyMetricChoice) {
      case LINKAGE_ATTACK_EXPECTED_SUCCESS, LINKAGE_ATTACK_TOP_K_CONTAINMENT -> true;
      case K_ANONYMITY_CARDINALITY, K_ANONYMITY_CARDINALITY_MAX, K_ANONYMITY_CARDINALITY_Q99 -> false;
    };
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

  @Override
  public Function<Graph<OperatorRepresentation, ArcType>, SequencedMap<String, Double>> qualityFunction() {
    return g -> {
      SequencedMap<String, Double> qualities = new TreeMap<>();
      Long counter = queryCounter.getAndIncrement();

      String queryId = String.valueOf(counter);
      try {
        // Create an executable Liebre query and execute this anonymization query
        LiebreAnonymizationQueryFromGraph liebreExecutor = new LiebreAnonymizationQueryFromGraph();
        boolean debugEnabled = logger.isDebugEnabled();

        if (debugEnabled) {
          logger.debug("Starting the processing of anonimization query #{}", counter);
        }
        long startTime = debugEnabled ? System.currentTimeMillis() : 0L;
        List<Tuple> modifiedEvents = liebreExecutor.processAnonymizationQuery(g, inputTuples);
        if (debugEnabled) {
          logger.debug(
              "Finished processing anonymization query #{}, input tuples: {}, output tuples: {}, total time: {}s",
              counter,
              inputTuples.size(), modifiedEvents.size(),
              (System.currentTimeMillis() - startTime) / 1000.0);
        }

        qualities.put("privacy", privacyScore(modifiedEvents));

        // Case with empty modified datastream
        if (modifiedEvents.isEmpty()) {
          qualities.put("semantics", 0.0);
          qualities.put("fidelity", 0.0);

        } else {

          if (debugEnabled) {
            logger.debug("Starting the processing of modified data on main query #{}", counter);
          }
          startTime = debugEnabled ? System.currentTimeMillis() : 0L;
          QueryResult modifiedOutcome = MainQuery.process(modifiedEvents, queryId);
          if (debugEnabled) {
            logger.debug(
                "Finished processing modified data on main query #{}, input tuples: {}, output tuples: aggregatestats {} and outliers {}, total time: {}s",
                counter, modifiedEvents.size(), modifiedOutcome.outputAggregatedStats().size(),
                modifiedOutcome.outputOutliers().size(),
                (System.currentTimeMillis() - startTime) / 1000.0);
          }

          qualities.put("fidelity",
              TupleMatchingScore.f1(TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputAggregatedStats()),
                  TupleMatchingScore.groupByTimestampAndKey(modifiedOutcome.outputAggregatedStats()),
                  fidelityF1Threshold,
                  DistanceMode.RELATIVE));
          qualities.put("semantics",
              TupleMatchingScore.f1(TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputOutliers()),
                  TupleMatchingScore.groupByTimestampAndKey(modifiedOutcome.outputOutliers()),
                  semanticsF1Threshold,
                  DistanceMode.RELATIVE));

        }
        return qualities;

      } catch (Exception e) {
        throw new RuntimeException("Error executing query " + queryId + " for graph " + g, e);
      }
    };
  }

}
