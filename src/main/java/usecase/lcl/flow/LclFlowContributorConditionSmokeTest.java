package usecase.lcl.flow;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.graph.Graph.Arc;
import io.github.ericmedvet.jgea.core.representation.graph.LinkedHashGraph;
import io.github.ericmedvet.jgea.core.representation.tree.Tree;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import problem.utils.PrivacyMetricChoice;
import query.LiebreAnonymizationQueryFromGraph;
import usecase.common.Tuple;

public final class LclFlowContributorConditionSmokeTest {

    private LclFlowContributorConditionSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        List<Tuple> input = LclFlowTupleReader.loadUnchecked(LclFlowTupleReader.DEFAULT_RESOURCE);
        new LclFlowStreamAnonymizationProblem(
                LclFlowTupleReader.DEFAULT_RESOURCE,
                PrivacyMetricChoice.LINKAGE_ATTACK_TOP_K_CONTAINMENT,
                0.02d,
                20);
        int contributorCount = LclFlowContributorCondition.contributorCount();
        require(contributorCount > 0, "Expected at least one contributor tuple");
        require(contributorCount < input.size(), "Contributor condition should not match every tuple");

        Graph<QueryMapper.OperatorRepresentation, ArcType> graph = graph();
        assertContributorForkDirectBranchFirst(graph);
        assertContributorBranchIsUnchanged(input,
                new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(graph, input));

        Graph<QueryMapper.OperatorRepresentation, ArcType> treeMappedGraph =
                new QueryMapper().mapperFor(new LinkedHashGraph<>()).apply(contributorRootTree());
        assertContributorForkDirectBranchFirst(treeMappedGraph);
        assertContributorBranchIsUnchanged(input,
                new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(treeMappedGraph, input));

        System.out.printf("lclFlowContributorCondition contributors=%d total=%d%n", contributorCount, input.size());
        System.exit(0);
    }

    private static Graph<QueryMapper.OperatorRepresentation, ArcType> graph() {
        Graph<QueryMapper.OperatorRepresentation, ArcType> graph = new LinkedHashGraph<>();

        QueryMapper.Source source = new QueryMapper.Source("source");
        QueryMapper.QueryConditionFork fork =
                new QueryMapper.QueryConditionFork("QCF1", LclFlowContributorCondition.CONDITION_ID);
        QueryMapper.Union union = new QueryMapper.Union("U1");
        QueryMapper.MapNoise nonContributorNoise =
                new QueryMapper.MapNoise("MN1", "f8", 0.25, QueryMapper.FieldSemanticType.CONTINUOUS_NUMERIC);
        QueryMapper.Sink sink = new QueryMapper.Sink("sink");

        graph.addNode(source);
        graph.addNode(fork);
        graph.addNode(union);
        graph.addNode(nonContributorNoise);
        graph.addNode(sink);
        graph.setArcValue(source, fork, ArcType.DEFAULT_ARC);
        graph.setArcValue(fork, union, ArcType.DEFAULT_ARC);
        graph.setArcValue(fork, nonContributorNoise, ArcType.DEFAULT_ARC);
        graph.setArcValue(nonContributorNoise, union, ArcType.DEFAULT_ARC);
        graph.setArcValue(union, sink, ArcType.DEFAULT_ARC);
        return graph;
    }

    private static Tree<String> contributorRootTree() {
        return tree("<pipeline>",
                tree("<contributor_root>",
                        tree("<empty_pipeline>", tree("noop")),
                        tree("<sorted_pipeline>",
                                tree("<ordinary_operator>",
                                        tree("<map_condition_preserving_noise_continuous_numeric>",
                                                tree("f8"),
                                                tree("0.25"))))));
    }

    private static void assertContributorForkDirectBranchFirst(Graph<QueryMapper.OperatorRepresentation, ArcType> graph) {
        List<Arc<QueryMapper.OperatorRepresentation>> branchArcs = graph.arcs().stream()
                .filter(arc -> arc.source() instanceof QueryMapper.QueryConditionFork fork
                        && fork.conditionId().equals(LclFlowContributorCondition.CONDITION_ID))
                .toList();
        require(branchArcs.size() == 2, "Expected two outgoing arcs from the contributor fork");
        require(branchArcs.getFirst().target() instanceof QueryMapper.Union,
                "Contributor true branch should be the direct union branch");
    }

    private static void assertContributorBranchIsUnchanged(List<Tuple> input, List<Tuple> output) {
        require(output.size() == input.size(),
                "Contributor-fork graph changed tuple count: " + output.size() + " vs " + input.size());

        Map<Long, Tuple> originalByLinkageId = new HashMap<>();
        for (Tuple tuple : input) {
            require(tuple.hasLinkageId(), "Input tuple without linkage id");
            originalByLinkageId.put(tuple.getLinkageId(), tuple);
        }

        int outputContributors = 0;
        for (Tuple tuple : output) {
            require(tuple.hasLinkageId(), "Output tuple without linkage id");
            Tuple original = originalByLinkageId.get(tuple.getLinkageId());
            require(original != null, "Unknown output linkage id: " + tuple.getLinkageId());
            if (LclFlowContributorCondition.isContributor(tuple)) {
                outputContributors++;
                assertSameTupleFields(original, tuple);
            }
        }
        require(outputContributors == LclFlowContributorCondition.contributorCount(),
                "Contributor count changed in output: " + outputContributors);
    }

    private static void assertSameTupleFields(Tuple expected, Tuple actual) {
        require(expected.getTimestamp() == actual.getTimestamp(), "Contributor timestamp changed");
        require(expected.getKey().equals(actual.getKey()), "Contributor key changed");
        require(expected.getNumFields() == actual.getNumFields(), "Contributor field count changed");
        for (int i = 1; i <= expected.getNumFields(); i++) {
            String field = "f" + i;
            require(Double.compare(expected.getField(field), actual.getField(field)) == 0,
                    "Contributor field changed: " + field);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @SafeVarargs
    private static Tree<String> tree(String content, Tree<String>... children) {
        return Tree.of(content, List.of(children));
    }
}
