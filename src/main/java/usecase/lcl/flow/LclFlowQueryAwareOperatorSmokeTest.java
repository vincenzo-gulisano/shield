package usecase.lcl.flow;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.graph.LinkedHashGraph;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import problem.utils.PrivacyMetricChoice;
import query.LiebreAnonymizationQueryFromGraph;
import usecase.common.Tuple;

import java.io.IOException;
import java.util.List;

public final class LclFlowQueryAwareOperatorSmokeTest {

    private LclFlowQueryAwareOperatorSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        new LclFlowStreamAnonymizationProblem(
                LclFlowTupleReader.DEFAULT_RESOURCE,
                PrivacyMetricChoice.LINKAGE_ATTACK_TOP_K_CONTAINMENT,
                0.02d,
                20);
        Graph<QueryMapper.OperatorRepresentation, ArcType> graph = graph();
        List<Tuple> input = input();
        List<Tuple> output = new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(graph, input);
        require(output.size() == LclFlowContributorCondition.contributorCount(),
                "Query-aware graph did not keep exactly the contributor tuples");

        Graph<QueryMapper.OperatorRepresentation, ArcType> parsedGraph = QueryMapper.parseGraphFromString(graph.toString());
        List<Tuple> parsedOutput = new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(parsedGraph, input());
        require(parsedOutput.size() == output.size(), "Parsed query-aware graph changed tuple count");

        System.exit(0);
    }

    private static List<Tuple> input() {
        return LclFlowTupleReader.loadUnchecked(LclFlowTupleReader.DEFAULT_RESOURCE);
    }

    private static Graph<QueryMapper.OperatorRepresentation, ArcType> graph() {
        Graph<QueryMapper.OperatorRepresentation, ArcType> graph = new LinkedHashGraph<>();

        QueryMapper.Source source = new QueryMapper.Source("source");
        QueryMapper.QueryConditionFork fork =
                new QueryMapper.QueryConditionFork("QCF1", LclFlowContributorCondition.CONDITION_ID);
        QueryMapper.MapConditionPartitionShuffle leftShuffle =
                new QueryMapper.MapConditionPartitionShuffle("MCGS1", "f10", LclFlowContributorCondition.CONDITION_ID);
        QueryMapper.MapConditionPreservingRIR leftRir =
                new QueryMapper.MapConditionPreservingRIR(
                        "MCR1", "f8", LclFlowContributorCondition.CONDITION_ID,
                        QueryMapper.FieldSemanticType.CONTINUOUS_NUMERIC);
        QueryMapper.MapConditionPairwiseSwap rightSwap =
                new QueryMapper.MapConditionPairwiseSwap("MCPS1", "f11", LclFlowContributorCondition.CONDITION_ID);
        QueryMapper.MapConditionPreservingNoise rightNoise =
                new QueryMapper.MapConditionPreservingNoise(
                        "MCN1", "f9", 0.05, LclFlowContributorCondition.CONDITION_ID,
                        QueryMapper.FieldSemanticType.CONTINUOUS_NUMERIC);
        QueryMapper.Union union = new QueryMapper.Union("U1");
        QueryMapper.FilterQueryCondition filter =
                new QueryMapper.FilterQueryCondition("FQ1", LclFlowContributorCondition.CONDITION_ID, true);
        QueryMapper.Sink sink = new QueryMapper.Sink("sink");

        graph.addNode(source);
        graph.addNode(fork);
        graph.addNode(leftShuffle);
        graph.addNode(leftRir);
        graph.addNode(rightSwap);
        graph.addNode(rightNoise);
        graph.addNode(union);
        graph.addNode(filter);
        graph.addNode(sink);
        graph.setArcValue(source, fork, ArcType.DEFAULT_ARC);
        graph.setArcValue(fork, leftShuffle, ArcType.DEFAULT_ARC);
        graph.setArcValue(leftShuffle, leftRir, ArcType.DEFAULT_ARC);
        graph.setArcValue(leftRir, union, ArcType.DEFAULT_ARC);
        graph.setArcValue(fork, rightSwap, ArcType.DEFAULT_ARC);
        graph.setArcValue(rightSwap, rightNoise, ArcType.DEFAULT_ARC);
        graph.setArcValue(rightNoise, union, ArcType.DEFAULT_ARC);
        graph.setArcValue(union, filter, ArcType.DEFAULT_ARC);
        graph.setArcValue(filter, sink, ArcType.DEFAULT_ARC);
        return graph;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
