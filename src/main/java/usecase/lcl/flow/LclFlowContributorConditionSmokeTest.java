package usecase.lcl.flow;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.graph.LinkedHashGraph;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import query.LiebreAnonymizationQueryFromGraph;
import usecase.common.Tuple;

public final class LclFlowContributorConditionSmokeTest {

    private LclFlowContributorConditionSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        List<Tuple> input = LclFlowTupleReader.loadUnchecked(LclFlowTupleReader.DEFAULT_RESOURCE);
        int contributorCount = LclFlowContributorCondition.contributorCount();
        require(contributorCount > 0, "Expected at least one contributor tuple");
        require(contributorCount < input.size(), "Contributor condition should not match every tuple");

        Graph<QueryMapper.OperatorRepresentation, ArcType> graph = graph();
        assertContributorBranchIsUnchanged(input,
                new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(graph, input));

        Graph<QueryMapper.OperatorRepresentation, ArcType> parsedGraph = QueryMapper.parseGraphFromString("{nodes=[Source[id=source], QueryConditionFork[id=QCF1, conditionId=c_lcl_flow_contributor], MapConditionPartitionShuffle[id=MCGS1, field=f4, conditionId=c_f1_eq_0], MapConditionPairwiseSwap[id=MCPS1, field=f10, conditionId=c_f2_ge_1_0], MapConditionPartitionShuffle[id=MCGS2, field=f6, conditionId=c_f1_eq_0], MapConditionPairwiseSwap[id=MCPS2, field=f7, conditionId=c_f11_lt_45], Union[id=U1], Sink[id=sink]], arcs={Source[id=source]->QueryConditionFork[id=QCF1, conditionId=c_lcl_flow_contributor]=DEFAULT_ARC, QueryConditionFork[id=QCF1, conditionId=c_lcl_flow_contributor]->MapConditionPartitionShuffle[id=MCGS1, field=f4, conditionId=c_f1_eq_0]=DEFAULT_ARC, MapConditionPartitionShuffle[id=MCGS1, field=f4, conditionId=c_f1_eq_0]->MapConditionPairwiseSwap[id=MCPS1, field=f10, conditionId=c_f2_ge_1_0]=DEFAULT_ARC, MapConditionPairwiseSwap[id=MCPS1, field=f10, conditionId=c_f2_ge_1_0]->MapConditionPartitionShuffle[id=MCGS2, field=f6, conditionId=c_f1_eq_0]=DEFAULT_ARC, MapConditionPartitionShuffle[id=MCGS2, field=f6, conditionId=c_f1_eq_0]->MapConditionPairwiseSwap[id=MCPS2, field=f7, conditionId=c_f11_lt_45]=DEFAULT_ARC, QueryConditionFork[id=QCF1, conditionId=c_lcl_flow_contributor]->Union[id=U1]=DEFAULT_ARC, MapConditionPairwiseSwap[id=MCPS2, field=f7, conditionId=c_f11_lt_45]->Union[id=U1]=DEFAULT_ARC, Union[id=U1]->Sink[id=sink]=DEFAULT_ARC}}");
        assertContributorBranchIsUnchanged(input,
                new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(parsedGraph, input));

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
}
