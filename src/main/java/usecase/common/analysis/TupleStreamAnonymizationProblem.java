package usecase.common.analysis;

import grammar.generator.FieldType;
import io.github.ericmedvet.jgea.core.problem.SimpleMOProblem;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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

public class TupleStreamAnonymizationProblem implements
        SimpleMOProblem<Graph<OperatorRepresentation, ArcType>, Double> {

    private static final Logger logger = LoggerFactory.getLogger(TupleStreamAnonymizationProblem.class);
    private static final AtomicLong queryCounter = new AtomicLong(0);

    static {
        LiebreContext.setSingleQueryExecution(false);
    }

    private static final SequencedMap<String, Comparator<Double>> OBJECTIVES = new TreeMap<>(
            Map.ofEntries(
                    Map.entry("privacy", ((Comparator<Double>) Double::compareTo).reversed()),
                    Map.entry("semantics", ((Comparator<Double>) Double::compareTo).reversed()),
                    Map.entry("fidelity", ((Comparator<Double>) Double::compareTo).reversed())));

    private final String useCaseName;
    private final String inputCsvPath;
    private final PrivacyMetricChoice privacyMetricChoice;
    private final TupleMainQuery mainQuery;
    private final QueryResult mainQueryResults;
    private final KAnonymityPrivacyCardinality privacyMetricCalculator;
    private final LinkageAttackPrivacy linkageAttackPrivacyCalculator;
    private final double fidelityF1Threshold;
    private final double semanticsF1Threshold;
    private final List<Tuple> inputTuples;

    public TupleStreamAnonymizationProblem(
            String useCaseName,
            String inputCsvPath,
            List<Tuple> inputTuples,
            TupleMainQuery mainQuery,
            PrivacyMetricChoice privacyMetric,
            double fidelityF1Threshold,
            double semanticsF1Threshold,
            int k,
            List<String> linkageAttackQuasiIdentifierAttributes,
            Map<String, FieldType> linkageAttackQuasiIdentifierTypes) {

        if (inputTuples == null || inputTuples.isEmpty()) {
            throw new IllegalArgumentException("Input tuples cannot be null or empty");
        }

        this.useCaseName = useCaseName;
        this.inputCsvPath = inputCsvPath;
        this.inputTuples = List.copyOf(inputTuples);
        this.mainQuery = mainQuery;
        this.privacyMetricChoice = privacyMetric;
        this.fidelityF1Threshold = fidelityF1Threshold;
        this.semanticsF1Threshold = semanticsF1Threshold;

        logger.info(
                "Initializing {} tuple stream problem with input CSV: {}, tuples: {}, privacy metric: {}, k: {}, fidelity F1 threshold: {}, semantics F1 threshold: {}",
                useCaseName, inputCsvPath, inputTuples.size(), privacyMetric, k, fidelityF1Threshold,
                semanticsF1Threshold);

        logger.info("Executing {} main query to get original results", useCaseName);
        this.mainQueryResults = mainQuery.process(this.inputTuples, "main");
        logger.info(
                "{} main query executed successfully, returning {} aggregate stats and {} outliers",
                useCaseName, mainQueryResults.outputAggregatedStats().size(), mainQueryResults.outputOutliers().size());

        List<String> allAttributes = List.of(Tuple.getFieldNames(this.inputTuples.get(0).getNumFields()));
        this.privacyMetricCalculator = usesKAnonymityMetric(privacyMetric)
                ? new KAnonymityPrivacyCardinality(this.inputTuples, k, allAttributes)
                : null;
        this.linkageAttackPrivacyCalculator = usesLinkageAttackMetric(privacyMetric)
                ? new LinkageAttackPrivacy(
                this.inputTuples,
                k,
                requireLinkageAttributes(linkageAttackQuasiIdentifierAttributes),
                normalizeLinkageTypes(linkageAttackQuasiIdentifierAttributes, linkageAttackQuasiIdentifierTypes))
                : null;

        logger.info("Original stream privacy, fidelity, and semantics scores: {}, {}, and {}",
                privacyScore(this.inputTuples),
                TupleMatchingScore.f1(
                        TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputAggregatedStats()),
                        TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputAggregatedStats()),
                        fidelityF1Threshold,
                        DistanceMode.RELATIVE),
                TupleMatchingScore.f1(
                        TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputOutliers()),
                        TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputOutliers()),
                        semanticsF1Threshold,
                        DistanceMode.RELATIVE));
    }

    @Override
    public SequencedMap<String, Comparator<Double>> comparators() {
        return OBJECTIVES;
    }

    @Override
    public Function<Graph<OperatorRepresentation, ArcType>, SequencedMap<String, Double>> qualityFunction() {
        return graph -> {
            SequencedMap<String, Double> qualities = new TreeMap<>();
            long counter = queryCounter.getAndIncrement();
            String queryId = useCaseName + "-" + counter;
            try {
                LiebreAnonymizationQueryFromGraph liebreExecutor = new LiebreAnonymizationQueryFromGraph();
                boolean debugEnabled = logger.isDebugEnabled();

                if (debugEnabled) {
                    logger.debug("Starting {} anonymization query #{}", useCaseName, counter);
                }
                long startTime = debugEnabled ? System.currentTimeMillis() : 0L;
                List<Tuple> modifiedEvents = liebreExecutor.processAnonymizationQuery(graph, inputTuples);
                modifiedEvents = sortedByTimestampAndKey(modifiedEvents);
                if (debugEnabled) {
                    logger.debug(
                            "Finished {} anonymization query #{}, input tuples: {}, output tuples: {}, total time: {}s",
                            useCaseName, counter, inputTuples.size(), modifiedEvents.size(),
                            (System.currentTimeMillis() - startTime) / 1000.0);
                }

                qualities.put("privacy", privacyScore(modifiedEvents));

                if (modifiedEvents.isEmpty()) {
                    qualities.put("semantics", 0.0);
                    qualities.put("fidelity", 0.0);
                    return qualities;
                }

                if (debugEnabled) {
                    logger.debug("Starting {} modified main query #{}", useCaseName, counter);
                }
                startTime = debugEnabled ? System.currentTimeMillis() : 0L;
                QueryResult modifiedOutcome = mainQuery.process(modifiedEvents, queryId);
                if (debugEnabled) {
                    logger.debug(
                            "Finished {} modified main query #{}, aggregate stats: {}, outliers: {}, total time: {}s",
                            useCaseName, counter, modifiedOutcome.outputAggregatedStats().size(),
                            modifiedOutcome.outputOutliers().size(),
                            (System.currentTimeMillis() - startTime) / 1000.0);
                }

                qualities.put("fidelity",
                        TupleMatchingScore.f1(
                                TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputAggregatedStats()),
                                TupleMatchingScore.groupByTimestampAndKey(modifiedOutcome.outputAggregatedStats()),
                                fidelityF1Threshold,
                                DistanceMode.RELATIVE));
                qualities.put("semantics",
                        TupleMatchingScore.f1(
                                TupleMatchingScore.groupByTimestampAndKey(mainQueryResults.outputOutliers()),
                                TupleMatchingScore.groupByTimestampAndKey(modifiedOutcome.outputOutliers()),
                                semanticsF1Threshold,
                                DistanceMode.RELATIVE));
                return qualities;

            } catch (Exception e) {
                throw new RuntimeException("Error executing " + useCaseName + " query " + queryId
                        + " for graph " + graph + " on " + inputCsvPath, e);
            }
        };
    }

    private double privacyScore(List<Tuple> modifiedEvents) {
        return switch (privacyMetricChoice) {
            case K_ANONYMITY_CARDINALITY_MAX -> requireKAnonymityCalculator().applyWithMax(inputTuples, modifiedEvents);
            case K_ANONYMITY_CARDINALITY_Q99 -> requireKAnonymityCalculator().applyWithQuantile99(inputTuples,
                    modifiedEvents);
            case K_ANONYMITY_CARDINALITY -> requireKAnonymityCalculator().apply(inputTuples, modifiedEvents);
            case LINKAGE_ATTACK_EXPECTED_SUCCESS -> requireLinkageAttackCalculator().applyExpectedSuccess(modifiedEvents);
            case LINKAGE_ATTACK_TOP_K_CONTAINMENT -> requireLinkageAttackCalculator().applyTopKContainment(modifiedEvents);
            case LINKAGE_ATTACK_TRUE_RANK_SCORE -> requireLinkageAttackCalculator().applyTrueRankScore(modifiedEvents);
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
            case LINKAGE_ATTACK_EXPECTED_SUCCESS, LINKAGE_ATTACK_TOP_K_CONTAINMENT,
                    LINKAGE_ATTACK_TRUE_RANK_SCORE -> false;
        };
    }

    private static boolean usesLinkageAttackMetric(PrivacyMetricChoice privacyMetricChoice) {
        return switch (privacyMetricChoice) {
            case LINKAGE_ATTACK_EXPECTED_SUCCESS, LINKAGE_ATTACK_TOP_K_CONTAINMENT,
                    LINKAGE_ATTACK_TRUE_RANK_SCORE -> true;
            case K_ANONYMITY_CARDINALITY, K_ANONYMITY_CARDINALITY_MAX, K_ANONYMITY_CARDINALITY_Q99 -> false;
        };
    }

    private static List<String> requireLinkageAttributes(List<String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException("Linkage-attack metrics require at least one quasi-identifier attribute");
        }
        return List.copyOf(attributes);
    }

    private static Map<String, FieldType> normalizeLinkageTypes(
            List<String> attributes,
            Map<String, FieldType> configuredTypes) {
        if (configuredTypes != null) {
            return Map.copyOf(configuredTypes);
        }
        return attributes.stream()
                .collect(Collectors.toMap(a -> a, ignored -> FieldType.CONTINUOUS_NUMERIC));
    }

    private static List<Tuple> sortedByTimestampAndKey(List<Tuple> tuples) {
        return tuples.stream()
                .sorted(Comparator
                        .comparingLong(Tuple::getTimestamp)
                        .thenComparing(Tuple::getKey))
                .toList();
    }
}
