package usecase.lcl;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.graph.LinkedHashGraph;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import problem.utils.PrivacyMetricChoice;
import query.LiebreAnonymizationQueryFromGraph;
import query.utils.TimestampPairwiseFieldSwapFunction;
import usecase.common.Tuple;

public final class TimestampPairwiseSwapSmokeTest {

    private static final double FIDELITY_TOLERANCE = 1e-9;

    private TimestampPairwiseSwapSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        testFunctionPairingAndFlush();
        testLclSingleSwapCandidates();
        System.exit(0);
    }

    private static void testFunctionPairingAndFlush() {
        List<Tuple> input = List.of(
                new Tuple(1L, "a", 1.0, 10.0),
                new Tuple(1L, "b", 2.0, 20.0),
                new Tuple(2L, "c", 3.0, 30.0),
                new Tuple(3L, "d", 4.0, 40.0));

        TimestampPairwiseFieldSwapFunction function = new TimestampPairwiseFieldSwapFunction("f2");
        List<Tuple> output = new ArrayList<>();
        for (Tuple tuple : input) {
            output.addAll(function.apply(tuple));
        }
        output.addAll(function.flush());

        require(output.size() == input.size(), "Expected one output per input tuple");
        for (int i = 0; i < input.size(); i++) {
            require(output.get(i).getTimestamp() == input.get(i).getTimestamp(), "Timestamp order changed at " + i);
            require(output.get(i).getKey().equals(input.get(i).getKey()), "Key order changed at " + i);
        }
        require(output.get(0).lookup("f2") == 20.0, "First same-timestamp tuple was not swapped");
        require(output.get(1).lookup("f2") == 10.0, "Second same-timestamp tuple was not swapped");
        require(output.get(2).lookup("f2") == 30.0, "Timestamp-boundary tuple should be unchanged");
        require(output.get(3).lookup("f2") == 40.0, "Final flushed tuple should be unchanged");
    }

    private static void testLclSingleSwapCandidates() throws IOException {
        List<Tuple> input = LclTupleLoader.load();
        LclStreamAnonymizationProblem problem = new LclStreamAnonymizationProblem(
                "datasets/lcl_10days_100keys.csv",
                PrivacyMetricChoice.LINKAGE_ATTACK_TOP_K_CONTAINMENT,
                0.05,
                0.05,
                10);

        for (String field : List.of("f1", "f2", "f3")) {
            Graph<QueryMapper.OperatorRepresentation, ArcType> graph = singleSwapGraph(field);
            List<Tuple> modified = new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(graph, input);
            assertSameCardinalityAndOrder(input, modified, field);

            SequencedMap<String, Double> scores = problem.qualityFunction().apply(graph);
            double fidelity = scores.get("fidelity");
            require(Math.abs(1.0 - fidelity) <= FIDELITY_TOLERANCE,
                    "Expected fidelity 1.0 for " + field + ", got " + fidelity);
            System.out.printf("timestampPairwiseSwap field=%s privacy=%f semantics=%f fidelity=%f%n",
                    field,
                    scores.get("privacy"),
                    scores.get("semantics"),
                    fidelity);
        }
    }

    private static Graph<QueryMapper.OperatorRepresentation, ArcType> singleSwapGraph(String field) {
        Graph<QueryMapper.OperatorRepresentation, ArcType> graph = new LinkedHashGraph<>();
        QueryMapper.Source source = new QueryMapper.Source("source");
        QueryMapper.MapTimestampPairwiseSwap swap = new QueryMapper.MapTimestampPairwiseSwap("MTS1", field);
        QueryMapper.Sink sink = new QueryMapper.Sink("sink");
        graph.addNode(source);
        graph.addNode(swap);
        graph.addNode(sink);
        graph.setArcValue(source, swap, ArcType.DEFAULT_ARC);
        graph.setArcValue(swap, sink, ArcType.DEFAULT_ARC);
        return graph;
    }

    private static void assertSameCardinalityAndOrder(List<Tuple> input, List<Tuple> modified, String field) {
        require(modified.size() == input.size(),
                "Modified stream size changed for " + field + ": " + modified.size() + " vs " + input.size());
        for (int i = 0; i < input.size(); i++) {
            Tuple original = input.get(i);
            Tuple rewritten = modified.get(i);
            require(original.getTimestamp() == rewritten.getTimestamp(),
                    "Timestamp changed at index " + i + " for " + field);
            require(original.getKey().equals(rewritten.getKey()),
                    "Key changed at index " + i + " for " + field);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
