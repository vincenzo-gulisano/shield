package usecase.forkjoin.synthetic;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import java.util.SequencedMap;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import problem.EnhancedStreamAnonymizationProblem;
import problem.utils.PrivacyMetricChoice;
import query.LiebreContext;

public class QueryTester {

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: QueryTester '<graph-string>' <input-csv-path-or-resource>");
        }

        Graph<OperatorRepresentation, ArcType> graph = QueryMapper.parseGraphFromString(args[0]);
        EnhancedStreamAnonymizationProblem problem = new EnhancedStreamAnonymizationProblem(
                args[1],
                PrivacyMetricChoice.K_ANONYMITY_CARDINALITY_Q99);

        SequencedMap<String, Double> scores = problem.qualityFunction().apply(graph);
        System.out.printf("privacy=%f%n", scores.get("privacy"));
        System.out.printf("semantics=%f%n", scores.get("semantics"));
        System.out.printf("fidelity=%f%n", scores.get("fidelity"));

        LiebreContext.interruptTerminator();
    }
}
