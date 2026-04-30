package mappers;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import mappers.QueryRepresentation.OperatorArguments;

// Query Representation (phenotype)
public record QueryRepresentation(
        // Contains a sequence of operators
        List<OperatorNode> operators
) implements Serializable {

    // Enumeration of all supported operator types
    public enum Operator {
        FILTER,
        MAP_DUPLICATE,
        MAP_NOISE,
        MAP_RIR,
        MAP_AGGREGATE
    }

    // Enumeration of supported filter conditions
    public enum Condition implements Serializable {
        LESS_THAN,
        GREATER_THAN;

        public static Condition fromString(String text) {
            return switch (text) {
                case "lt" -> LESS_THAN;
                case "gt" -> GREATER_THAN;
                default -> throw new IllegalArgumentException("Condition not valid: " + text);
            };
        }

        @Override
        public String toString() {
            return switch (this) {
                case LESS_THAN -> "<";
                case GREATER_THAN -> ">";
            };
        }
    }

    // Enumeration of supported aggregation functions
    public enum AggregationFunction implements Serializable {
        MIN,
        AVG,
        MAX;

        public static AggregationFunction fromString(String text) {
            return switch (text) {
                case "min" -> MIN;
                case "avg" -> AVG;
                case "max" -> MAX;
                default -> throw new IllegalArgumentException("Aggregation function not valid: " + text);
            };
        }
    }

    @Override
    public String toString() {
        if (operators == null || operators.isEmpty()) {
            return "Pipeline {}";
        }
        String ops = operators.stream()
                .map(OperatorNode::toString)
                .collect(Collectors.joining(" | "));
        return "Pipeline { " + ops + " }";
    }

    // Represents a single operator node in the pipeline
    public record OperatorNode(
            Operator type,
            OperatorArguments arguments
    ) implements Serializable {

        @Override
        public String toString() {
            return String.format("%s(%s)", type.name().toLowerCase(), arguments.toString());
        }
    }

    public interface OperatorArguments extends Serializable {}

    // Arguments for a filter operator
    public record FilterArgs(
            String variable,
            Condition condition,
            double value
    ) implements OperatorArguments {

        @Override
        public String toString() {
            return String.format(
                    "%s %s %.4f",
                    variable,
                    condition.toString(),
                    value
            );
        }
    }

    // Arguments for a map duplicate operator
    public record MapDuplicateArgs(
            double probability
    ) implements OperatorArguments {

        @Override
        public String toString() {
            return String.format("probability=%.2f", probability);
        }
    }

    // Arguments for a map duplicate operator
    public record MapRIRArgs(
            String attribute
    ) implements OperatorArguments {

        @Override
        public String toString() {
            return String.format("attribute=%s", attribute);
        }
    }

    // Arguments for a map noise operator
    public record MapNoiseArgs(
            String attribute,
            double percentage
    ) implements OperatorArguments {

        @Override
        public String toString() {
            return String.format("attribute=%s, percentage=%.2f", attribute, percentage);
        }
    }

    // Arguments for a map aggregate operator
    public record MapAggregateArgs(
            String attribute,
            AggregationFunction function,
            int windowSize
    ) implements OperatorArguments {

        @Override
        public String toString() {
            return String.format(
                    "attribute=%s, function=%s, window=%d",
                    attribute,
                    function.name().toLowerCase(),
                    windowSize
            );
        }
    }
}
