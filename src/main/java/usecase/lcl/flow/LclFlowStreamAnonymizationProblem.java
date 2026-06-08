package usecase.lcl.flow;

import grammar.generator.FieldType;
import io.github.ericmedvet.jgea.core.problem.SimpleMOProblem;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import metrics.privacy.KAnonymityPrivacyCardinality;
import metrics.privacy.LinkageAttackPrivacy;
import problem.utils.PrivacyMetricChoice;
import query.LiebreAnonymizationQueryFromGraph;
import query.LiebreContext;
import usecase.common.Tuple;
import usecase.common.TupleMatchingScore;
import usecase.common.TupleMatchingScore.DistanceMode;

public class LclFlowStreamAnonymizationProblem implements
        SimpleMOProblem<Graph<OperatorRepresentation, ArcType>, Double> {

    public static final List<String> LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES =
            List.of("f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11");
    public static final Map<String, FieldType> LINKAGE_ATTACK_QUASI_IDENTIFIER_TYPES = Map.ofEntries(
            Map.entry("f1", FieldType.NOMINAL_CATEGORICAL),
            Map.entry("f2", FieldType.CONTINUOUS_NUMERIC),
            Map.entry("f3", FieldType.CONTINUOUS_NUMERIC),
            Map.entry("f4", FieldType.CONTINUOUS_NUMERIC),
            Map.entry("f5", FieldType.CONTINUOUS_NUMERIC),
            Map.entry("f6", FieldType.CONTINUOUS_NUMERIC),
            Map.entry("f7", FieldType.CONTINUOUS_NUMERIC),
            Map.entry("f8", FieldType.CONTINUOUS_NUMERIC),
            Map.entry("f9", FieldType.CONTINUOUS_NUMERIC),
            Map.entry("f10", FieldType.DISCRETE_NUMERIC),
            Map.entry("f11", FieldType.DISCRETE_NUMERIC));

    private static final AtomicLong QUERY_COUNTER = new AtomicLong(0L);
    private static final SequencedMap<String, Comparator<Double>> OBJECTIVES = new TreeMap<>(
            Map.ofEntries(
                    Map.entry("privacy", ((Comparator<Double>) Double::compareTo).reversed()),
                    Map.entry("semantics", ((Comparator<Double>) Double::compareTo).reversed()),
                    Map.entry("fidelity", ((Comparator<Double>) Double::compareTo).reversed())));

    static {
        LiebreContext.setSingleQueryExecution(false);
    }

    private final String inputCsvPath;
    private final PrivacyMetricChoice privacyMetricChoice;
    private final double semanticsF1Threshold;
    private final List<Tuple> inputTuples;
    private final LclFlowAllFieldsMainQuery.Settings querySettings;
    private final LclFlowAllFieldsMainQuery.QueryResult baselineOutcome;
    private final KAnonymityPrivacyCardinality kAnonymityPrivacy;
    private final LinkageAttackPrivacy linkageAttackPrivacy;
    private final StreamFlowSnapshotSimilarity fidelitySimilarity;

    public LclFlowStreamAnonymizationProblem(
            String inputCsvPath,
            PrivacyMetricChoice privacyMetricChoice,
            double semanticsF1Threshold,
            int k) {
        this.inputCsvPath = inputCsvPath;
        this.privacyMetricChoice = privacyMetricChoice;
        this.semanticsF1Threshold = semanticsF1Threshold;
        this.inputTuples = LclFlowTupleReader.loadUnchecked(inputCsvPath);
        long minTimestamp = this.inputTuples.stream().mapToLong(Tuple::getTimestamp).min().orElseThrow();
        long maxTimestamp = this.inputTuples.stream().mapToLong(Tuple::getTimestamp).max().orElseThrow();
        this.querySettings = LclFlowAllFieldsMainQuery.Settings.defaults()
                .withInstrumentationRange(minTimestamp, maxTimestamp);
        this.baselineOutcome = LclFlowAllFieldsMainQuery.process(this.inputTuples, "main", querySettings);
        this.kAnonymityPrivacy = usesKAnonymityMetric(privacyMetricChoice)
                ? new KAnonymityPrivacyCardinality(
                        this.inputTuples,
                        k,
                        List.of(Tuple.getFieldNames(this.inputTuples.get(0).getNumFields())))
                : null;
        this.linkageAttackPrivacy = usesLinkageAttackMetric(privacyMetricChoice)
                ? new LinkageAttackPrivacy(
                        this.inputTuples,
                        k,
                        LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES,
                        LINKAGE_ATTACK_QUASI_IDENTIFIER_TYPES)
                : null;
        this.fidelitySimilarity = new StreamFlowSnapshotSimilarity(baselineOutcome.flow());
    }

    @Override
    public SequencedMap<String, Comparator<Double>> comparators() {
        return OBJECTIVES;
    }

    @Override
    public Function<Graph<OperatorRepresentation, ArcType>, SequencedMap<String, Double>> qualityFunction() {
        return graph -> {
            SequencedMap<String, Double> qualities = new TreeMap<>();
            String queryId = "LCL-FLOW-" + QUERY_COUNTER.getAndIncrement();
            try {
                LiebreAnonymizationQueryFromGraph executor = new LiebreAnonymizationQueryFromGraph();
                List<Tuple> modifiedEvents = sortedByTimestampAndKey(
                        executor.processAnonymizationQuery(graph, inputTuples));

                qualities.put("privacy", privacyScore(modifiedEvents));
                if (modifiedEvents.isEmpty()) {
                    qualities.put("fidelity", fidelitySimilarity.apply(
                            StreamFlowSnapshotSimilarity.emptyLike(baselineOutcome.flow())));
                    qualities.put("semantics", baselineOutcome.outputTuples().isEmpty() ? 1.0 : 0.0);
                    return qualities;
                }

                LclFlowAllFieldsMainQuery.QueryResult modifiedOutcome =
                        LclFlowAllFieldsMainQuery.process(modifiedEvents, queryId, querySettings);
                qualities.put("fidelity", fidelitySimilarity.apply(modifiedOutcome.flow()));
                qualities.put("semantics", TupleMatchingScore.f1(
                        TupleMatchingScore.groupByTimestampAndKey(baselineOutcome.outputTuples()),
                        TupleMatchingScore.groupByTimestampAndKey(modifiedOutcome.outputTuples()),
                        semanticsF1Threshold,
                        DistanceMode.RELATIVE));
                return qualities;
            } catch (Exception e) {
                throw new RuntimeException(
                        "Error executing LCL flow query " + queryId + " for graph " + graph
                                + " on " + inputCsvPath,
                        e);
            }
        };
    }

    public LclFlowAllFieldsMainQuery.QueryResult baselineOutcome() {
        return baselineOutcome;
    }

    private double privacyScore(List<Tuple> modifiedEvents) {
        return switch (privacyMetricChoice) {
            case K_ANONYMITY_CARDINALITY -> requireKAnonymityPrivacy().apply(inputTuples, modifiedEvents);
            case K_ANONYMITY_CARDINALITY_MAX -> requireKAnonymityPrivacy().applyWithMax(inputTuples, modifiedEvents);
            case K_ANONYMITY_CARDINALITY_Q99 -> requireKAnonymityPrivacy().applyWithQuantile99(
                    inputTuples,
                    modifiedEvents);
            case LINKAGE_ATTACK_EXPECTED_SUCCESS -> requireLinkageAttackPrivacy().applyExpectedSuccess(modifiedEvents);
            case LINKAGE_ATTACK_TOP_K_CONTAINMENT -> requireLinkageAttackPrivacy().applyTopKContainment(modifiedEvents);
        };
    }

    private static List<Tuple> sortedByTimestampAndKey(List<Tuple> tuples) {
        return tuples.stream()
                .sorted(Comparator.comparingLong(Tuple::getTimestamp).thenComparing(Tuple::getKey))
                .collect(Collectors.toList());
    }

    private static boolean usesLinkageAttackMetric(PrivacyMetricChoice privacyMetricChoice) {
        return switch (privacyMetricChoice) {
            case LINKAGE_ATTACK_EXPECTED_SUCCESS, LINKAGE_ATTACK_TOP_K_CONTAINMENT -> true;
            case K_ANONYMITY_CARDINALITY, K_ANONYMITY_CARDINALITY_MAX, K_ANONYMITY_CARDINALITY_Q99 -> false;
        };
    }

    private static boolean usesKAnonymityMetric(PrivacyMetricChoice privacyMetricChoice) {
        return switch (privacyMetricChoice) {
            case K_ANONYMITY_CARDINALITY, K_ANONYMITY_CARDINALITY_MAX, K_ANONYMITY_CARDINALITY_Q99 -> true;
            case LINKAGE_ATTACK_EXPECTED_SUCCESS, LINKAGE_ATTACK_TOP_K_CONTAINMENT -> false;
        };
    }

    private KAnonymityPrivacyCardinality requireKAnonymityPrivacy() {
        if (kAnonymityPrivacy == null) {
            throw new IllegalStateException("K-anonymity privacy is not initialized for " + privacyMetricChoice);
        }
        return kAnonymityPrivacy;
    }

    private LinkageAttackPrivacy requireLinkageAttackPrivacy() {
        if (linkageAttackPrivacy == null) {
            throw new IllegalStateException("Linkage-attack privacy is not initialized for " + privacyMetricChoice);
        }
        return linkageAttackPrivacy;
    }
}
