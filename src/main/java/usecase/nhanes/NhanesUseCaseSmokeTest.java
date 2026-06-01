package usecase.nhanes;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.tree.Tree;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import metrics.privacy.KAnonymityPrivacyCardinality;
import metrics.privacy.LinkageAttackPrivacy;
import query.LiebreAnonymizationQueryFromGraph;
import query.utils.CategoricalRIRMap;
import usecase.common.Tuple;
import usecase.common.TupleFieldValueSampler;
import usecase.common.TupleMatchingScore;
import usecase.common.TupleMatchingScore.DistanceMode;
import usecase.common.TupleMatchingScore.Key;

public final class NhanesUseCaseSmokeTest {

    private static final double SCORE_TOLERANCE = 1e-9;

    private NhanesUseCaseSmokeTest() {
    }

    public static void main(String[] args) {
        printPrivacyScore();
        testF1Scores();
        testLinkageScores();
        testTypedMapperFilters();
        testTypedMapperSampledValues();
        testTypedMapperOperators();
        testTypedConditionalFork();
        testNonFiniteValuesAreRejected();
        runMainQuery();
        System.exit(0);
    }

    private static void printPrivacyScore() {
        List<Tuple> tuples = NhanesTupleLoader.load();
        List<String> attributes = List.of(Tuple.getFieldNames(tuples.getFirst().getNumFields()));
        for (int k=2; k<=100; k++) {
            KAnonymityPrivacyCardinality privacyMetricCalculator =
                    new KAnonymityPrivacyCardinality(tuples, k, attributes);
            System.out.printf("privacy@k=%d: %f (99) %f (max)%n", k,
                    privacyMetricCalculator.applyWithQuantile99(tuples, tuples),
                    privacyMetricCalculator.applyWithMax(tuples, tuples));
        }
        LinkageAttackPrivacy linkageAttackPrivacy = new LinkageAttackPrivacy(
                tuples,
                50,
                NhanesStreamAnonymizationProblem.LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES);
        System.out.printf("linkage@k=50 quasiIds=%s: %f (expected) %f (top-k)%n",
                NhanesStreamAnonymizationProblem.LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES,
                linkageAttackPrivacy.applyExpectedSuccess(tuples),
                linkageAttackPrivacy.applyTopKContainment(tuples));
    }

    private static void testF1Scores() {
        checkF1("perfect match",
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0)),
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0)),
                0.0,
                1.0);

        checkF1("one missing tuple",
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0)),
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "b", 30.0)),
                0.0,
                0.8);

        checkF1("one extra tuple",
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0)),
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "a", 20.0),
                        new Tuple(0L, "b", 30.0),
                        new Tuple(0L, "b", 99.0)),
                0.0,
                6.0 / 7.0);

        checkF1("one value outside threshold",
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "b", 20.0)),
                outliers(
                        new Tuple(0L, "a", 10.0),
                        new Tuple(0L, "b", 25.0)),
                0.1,
                0.5);
    }

    private static void runMainQuery() {
        List<Tuple> tuples = NhanesTupleLoader.load();
        MainQuery mainQuery = new MainQuery();
        MainQuery.QueryResult result = mainQuery.process(tuples, "smoke");
        int aggregateStatsCount = result.outputAggregatedStats().size();
        int outlierCount = result.outputOutliers().size();
        System.out.printf("mainQueryInput=%d%n", tuples.size());
        System.out.printf("mainQueryAggregateStats=%d%n", aggregateStatsCount);
        System.out.printf("mainQueryOutliers=%d%n", outlierCount);
        System.out.printf("mainQueryTotalOutput=%d%n", aggregateStatsCount + outlierCount);
    }

    private static void testTypedMapperFilters() {
        List<Tuple> input = List.of(
                new Tuple(0L, "", 1.0, 10.0, 1.0),
                new Tuple(0L, "", 1.0, 20.0, 2.0),
                new Tuple(0L, "", 2.0, 30.0, 3.0),
                new Tuple(0L, "", 1.0, 40.0, 4.0));
        Tree<String> pipeline = pipeline(
                tree("<filter_nominal>",
                        tree("<nominal_attribute>", tree("f1")),
                        tree("<nominal_condition>", tree("eq")),
                        tree("<f1_value>", tree("1"))),
                tree("<filter_discrete_numeric>",
                        tree("<discrete_numeric_attribute>", tree("f2")),
                        tree("<numeric_condition>", tree("gt")),
                        tree("<numeric_value>", tree("15"))),
                tree("<filter_continuous_numeric>",
                        tree("<continuous_numeric_attribute>", tree("f3")),
                        tree("<numeric_condition>", tree("lt")),
                        tree("<numeric_value>", tree("3.0"))));

        List<Tuple> output = runTypedPipeline("typed filters", new QueryMapper(), pipeline, input);
        checkInt("typed filters output count", output.size(), 1);
    }

    private static void testTypedMapperSampledValues() {
        List<Tuple> input = List.of(
                new Tuple(0L, "", 1.0, 10.0),
                new Tuple(0L, "", 2.0, 20.0),
                new Tuple(0L, "", 2.0, 30.0));
        Tree<String> pipeline = pipeline(
                tree("<filter_nominal>",
                        tree("<nominal_attribute>", tree("f1")),
                        tree("<nominal_condition>", tree("eq")),
                        tree("<nominal_value>", tree("ucr"))));

        expectThrows("typed sampled value without sampler",
                IllegalStateException.class,
                () -> new QueryMapper().mapperFor(null).apply(pipeline));

        QueryMapper mapper = new QueryMapper(new TupleFieldValueSampler(input, 0L));
        List<Tuple> output = runTypedPipeline("typed sampled value with sampler", mapper, pipeline, input);
        if (output.size() < 1 || output.size() > input.size()) {
            throw new IllegalStateException(
                    "Unexpected typed sampled value output count: " + output.size());
        }
        System.out.printf("typed sampled value output count=%d%n", output.size());
    }

    private static void testTypedMapperOperators() {
        List<Tuple> input = List.of(
                new Tuple(0L, "", 1.0, 10.0, 100.0),
                new Tuple(0L, "", 2.0, 20.0, 200.0),
                new Tuple(0L, "", 1.0, 30.0, 300.0));
        Tree<String> pipeline = pipeline(
                tree("<map_noise_nominal>",
                        tree("<nominal_attribute>", tree("f1")),
                        tree("<probability>", tree("1.0"))),
                tree("<map_rir_discrete_numeric>",
                        tree("<discrete_numeric_attribute>", tree("f2"))),
                tree("<map_noise_continuous_numeric>",
                        tree("<continuous_numeric_attribute>", tree("f3")),
                        tree("<percentage>", tree("0.0"))),
                tree("<map_aggregate_continuous_numeric>",
                        tree("<continuous_numeric_attribute>", tree("f3")),
                        tree("<numeric_agg_fun>", tree("avg")),
                        tree("<window_size>", tree("2"))));

        List<Tuple> output = runTypedPipeline("typed map operators", new QueryMapper(), pipeline, input);
        checkInt("typed map operators output count", output.size(), input.size());
        assertAllFieldsFinite("typed map operators", output);
    }

    private static void testTypedConditionalFork() {
        List<Tuple> input = List.of(
                new Tuple(0L, "", 1.0),
                new Tuple(0L, "", 2.0),
                new Tuple(0L, "", 1.0));
        Tree<String> pipeline = pipeline(
                tree("<fork_nominal>",
                        tree("<nominal_attribute>", tree("f1")),
                        tree("<nominal_condition>", tree("eq")),
                        tree("<f1_value>", tree("1")),
                        pipeline(tree("<map_duplicate>", tree("<probability>", tree("0.0")))),
                        pipeline(tree("<map_duplicate>", tree("<probability>", tree("0.0"))))));

        Graph<OperatorRepresentation, ArcType> graph = new QueryMapper().mapperFor(null).apply(pipeline);
        boolean allDefaultArcs = graph.arcs().stream()
                .allMatch(arc -> graph.getArcValue(arc) == ArcType.DEFAULT_ARC);
        if (!allDefaultArcs) {
            throw new IllegalStateException("Typed conditional fork produced a non-default arc");
        }

        List<Tuple> output = runGraph("typed conditional fork", graph, input);
        checkInt("typed conditional fork output count", output.size(), input.size());
    }

    private static void testNonFiniteValuesAreRejected() {
        expectThrows("non-finite categorical RIR",
                IllegalArgumentException.class,
                () -> new CategoricalRIRMap("f1").apply(new Tuple(0L, "", Double.NaN)));
        expectThrows("non-finite tuple sampler",
                IllegalArgumentException.class,
                () -> new TupleFieldValueSampler(List.of(new Tuple(0L, "", Double.POSITIVE_INFINITY))));
    }

    private static Map<Key, List<Tuple>> outliers(Tuple... tuples) {
        return TupleMatchingScore.groupByTimestampAndKey(List.of(tuples));
    }

    private static void checkF1(
            String name,
            Map<Key, List<Tuple>> original,
            Map<Key, List<Tuple>> modified,
            double threshold,
            double expected) {
        double actual = TupleMatchingScore.f1(original, modified, threshold, DistanceMode.ABSOLUTE);
        if (Math.abs(actual - expected) > SCORE_TOLERANCE) {
            throw new IllegalStateException(
                    "Unexpected F1 score for " + name + ": expected " + expected + ", got " + actual);
        }
        System.out.printf("f1[%s]=%f%n", name, actual);
    }

    private static List<Tuple> runTypedPipeline(
            String name,
            QueryMapper mapper,
            Tree<String> pipeline,
            List<Tuple> input) {
        Graph<OperatorRepresentation, ArcType> graph = mapper.mapperFor(null).apply(pipeline);
        return runGraph(name, graph, input);
    }

    private static List<Tuple> runGraph(
            String name,
            Graph<OperatorRepresentation, ArcType> graph,
            List<Tuple> input) {
        try {
            return new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(graph, input);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run " + name, e);
        }
    }

    @SafeVarargs
    private static Tree<String> pipeline(Tree<String>... operators) {
        if (operators.length == 0) {
            throw new IllegalArgumentException("Pipeline must contain at least one operator");
        }
        Tree<String> current = tree("<pipeline>", tree("<operator>", operators[operators.length - 1]));
        for (int i = operators.length - 2; i >= 0; i--) {
            current = tree("<pipeline>", tree("<operator>", operators[i]), current);
        }
        return current;
    }

    @SafeVarargs
    private static Tree<String> tree(String content, Tree<String>... children) {
        return Tree.of(content, List.of(children));
    }

    private static void assertAllFieldsFinite(String name, List<Tuple> tuples) {
        for (Tuple tuple : tuples) {
            for (double field : tuple.getFields()) {
                if (!Double.isFinite(field)) {
                    throw new IllegalStateException(name + " produced non-finite field: " + field);
                }
            }
        }
        System.out.printf("%s finite fields=true%n", name);
    }

    private static void checkInt(String name, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Unexpected value for " + name + ": expected " + expected + ", got " + actual);
        }
        System.out.printf("%s=%d%n", name, actual);
    }

    private static void expectThrows(
            String name,
            Class<? extends Throwable> expectedType,
            ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            if (!expectedType.isInstance(t)) {
                throw new IllegalStateException(
                        "Unexpected exception for " + name + ": " + t.getClass().getSimpleName(), t);
            }
            System.out.printf("%s threw %s%n", name, t.getClass().getSimpleName());
            return;
        }
        throw new IllegalStateException("Expected exception for " + name);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void testLinkageScores() {
        List<Tuple> original = List.of(
                linkedTuple(0L, 0.0),
                linkedTuple(1L, 10.0),
                linkedTuple(2L, 20.0));

        LinkageAttackPrivacy k1 = new LinkageAttackPrivacy(original, 1, List.of("f1"));
        checkScore("linkage top-k original k=1", k1.applyTopKContainment(original), 0.0);
        checkScore("linkage expected original k=1", k1.applyExpectedSuccess(original), 0.0);
        checkScore("linkage top-k empty", k1.applyTopKContainment(List.of()), 1.0);
        checkScore("linkage expected empty", k1.applyExpectedSuccess(List.of()), 1.0);

        LinkageAttackPrivacy k2 = new LinkageAttackPrivacy(original, 2, List.of("f1"));
        checkScore("linkage top-k original k=2", k2.applyTopKContainment(original), 0.0);
        checkScore("linkage expected original k=2", k2.applyExpectedSuccess(original), 0.5);

        List<Tuple> modifiedWithDuplicate = List.of(
                linkedTuple(0L, 0.0),
                linkedTuple(0L, 100.0),
                linkedTuple(1L, 100.0));
        checkScore("linkage top-k ignores duplicate ids",
                k1.applyTopKContainment(modifiedWithDuplicate), 0.5);
        checkScore("linkage expected ignores duplicate ids",
                k1.applyExpectedSuccess(modifiedWithDuplicate), 0.5);
    }

    private static Tuple linkedTuple(long linkageId, double... fields) {
        return new Tuple(0L, "", fields).withLinkageId(linkageId);
    }

    private static void checkScore(String name, double actual, double expected) {
        if (Math.abs(actual - expected) > SCORE_TOLERANCE) {
            throw new IllegalStateException(
                    "Unexpected score for " + name + ": expected " + expected + ", got " + actual);
        }
        System.out.printf("%s=%f%n", name, actual);
    }
}
