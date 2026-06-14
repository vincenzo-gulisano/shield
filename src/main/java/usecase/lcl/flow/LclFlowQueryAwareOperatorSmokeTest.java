package usecase.lcl.flow;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.graph.LinkedHashGraph;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import query.LiebreAnonymizationQueryFromGraph;
import usecase.common.Tuple;

import java.io.IOException;
import java.util.List;

public final class LclFlowQueryAwareOperatorSmokeTest {

    private LclFlowQueryAwareOperatorSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        Graph<QueryMapper.OperatorRepresentation, ArcType> graph = graph();
        List<Tuple> input = input();
        List<Tuple> output = new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(graph, input);
        require(output.size() == input.size(), "Query-aware graph changed tuple count");

        Graph<QueryMapper.OperatorRepresentation, ArcType> parsedGraph = QueryMapper.parseGraphFromString(graph.toString());
        List<Tuple> parsedOutput = new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(parsedGraph, input);
        require(parsedOutput.size() == input.size(), "Parsed query-aware graph changed tuple count");

        System.exit(0);
    }

    private static List<Tuple> input() {
        return List.of(
                tuple(1L, "a", 0, 10, 0.9, 0.2, 0.4, 0.2, 0.4, 0.1, 0.4, 35, 2),
                tuple(1L, "b", 0, 11, 1.0, 0.2, 0.5, 0.2, 0.5, 0.1, 0.4, 36, 3),
                tuple(1L, "c", 1, 12, 1.1, 0.2, 0.6, 0.2, 0.6, 0.1, 0.4, 20, 4),
                tuple(1L, "d", 1, 13, 1.2, 0.2, 0.7, 0.2, 0.7, 0.1, 0.4, 21, 5),
                tuple(2L, "a", 0, 14, 1.3, 0.2, 0.8, 0.2, 0.8, 0.1, 0.4, 37, 6),
                tuple(2L, "b", 1, 15, 1.4, 0.2, 0.9, 0.2, 0.9, 0.1, 0.4, 22, 7));
    }

    private static Tuple tuple(long timestamp, String key, double... fields) {
        return new Tuple(timestamp, key, fields);
    }

    private static Graph<QueryMapper.OperatorRepresentation, ArcType> graph() {
        Graph<QueryMapper.OperatorRepresentation, ArcType> graph = new LinkedHashGraph<>();

        QueryMapper.Source source = new QueryMapper.Source("source");
        QueryMapper.QueryConditionFork fork = new QueryMapper.QueryConditionFork("QCF1", "c_f1_eq_0");
        QueryMapper.MapConditionPartitionShuffle leftShuffle =
                new QueryMapper.MapConditionPartitionShuffle("MCGS1", "f10", "c_f10_between_34_45");
        QueryMapper.MapConditionPreservingRIR leftRir =
                new QueryMapper.MapConditionPreservingRIR(
                        "MCR1", "f8", "c_f1_eq_0", QueryMapper.FieldSemanticType.CONTINUOUS_NUMERIC);
        QueryMapper.MapConditionPairwiseSwap rightSwap =
                new QueryMapper.MapConditionPairwiseSwap("MCPS1", "f11", "c_f10_between_34_45");
        QueryMapper.MapConditionPreservingNoise rightNoise =
                new QueryMapper.MapConditionPreservingNoise(
                        "MCN1", "f9", 0.05, "c_f2_ge_1_0", QueryMapper.FieldSemanticType.CONTINUOUS_NUMERIC);
        QueryMapper.Union union = new QueryMapper.Union("U1");
        QueryMapper.FilterQueryCondition filter = new QueryMapper.FilterQueryCondition("FQ1", "c_f2_ge_1_0", true);
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
