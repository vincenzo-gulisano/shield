package query.utils;

import usecase.common.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static query.utils.OperatorUtils.requireFinite;

public record TupleConditionSpec(String id, String field, Operator operator, double value, double upperValue) {

    public enum Operator {
        GE,
        GT,
        LE,
        LT,
        EQ,
        NEQ,
        BETWEEN_CLOSED
    }

    public TupleConditionSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(operator, "operator");
        requireFinite(field, value);
        if (operator == Operator.BETWEEN_CLOSED) {
            requireFinite(field, upperValue);
            if (upperValue < value) {
                throw new IllegalArgumentException("upperValue cannot be lower than value");
            }
        }
    }

    public static TupleConditionSpec fromId(String id) {
        String[] parts = Objects.requireNonNull(id, "id").split("_");
        if (parts.length < 4 || !parts[0].equals("c")) {
            throw new IllegalArgumentException(
                    "Condition id must have format c_<field>_<op>_<value> or c_<field>_<op>_<value>_<value>: "
                            + id);
        }

        String field = parts[1];
        if (!field.matches("f\\d+")) {
            throw new IllegalArgumentException("Condition field must have format f<number>: " + id);
        }

        ParsedOperator parsedOperator = parseOperator(parts, id);
        if (parsedOperator.operator() == Operator.BETWEEN_CLOSED) {
            double[] values = parseTwoValues(parts, parsedOperator.nextIndex(), id);
            return new TupleConditionSpec(id, field, parsedOperator.operator(), values[0], values[1]);
        }

        double value = parseOneValue(parts, parsedOperator.nextIndex(), id);
        return new TupleConditionSpec(id, field, parsedOperator.operator(), value, Double.NaN);
    }

    public boolean test(Tuple tuple) {
        double tupleValue = requireFinite(field, tuple.lookup(field));
        return switch (operator) {
            case GE -> tupleValue >= value;
            case GT -> tupleValue > value;
            case LE -> tupleValue <= value;
            case LT -> tupleValue < value;
            case EQ -> Double.compare(tupleValue, value) == 0;
            case NEQ -> Double.compare(tupleValue, value) != 0;
            case BETWEEN_CLOSED -> tupleValue >= value && tupleValue <= upperValue;
        };
    }

    private static ParsedOperator parseOperator(String[] parts, String id) {
        return switch (parts[2]) {
            case "ge" -> new ParsedOperator(Operator.GE, 3);
            case "gt" -> new ParsedOperator(Operator.GT, 3);
            case "le" -> new ParsedOperator(Operator.LE, 3);
            case "lt" -> new ParsedOperator(Operator.LT, 3);
            case "eq" -> new ParsedOperator(Operator.EQ, 3);
            case "neq" -> new ParsedOperator(Operator.NEQ, 3);
            case "between" -> {
                if (parts.length > 3 && parts[3].equals("closed")) {
                    yield new ParsedOperator(Operator.BETWEEN_CLOSED, 4);
                }
                yield new ParsedOperator(Operator.BETWEEN_CLOSED, 3);
            }
            default -> throw new IllegalArgumentException("Unknown condition operator in id: " + id);
        };
    }

    private static double parseOneValue(String[] parts, int startIndex, String id) {
        if (startIndex >= parts.length) {
            throw new IllegalArgumentException("Missing condition value in id: " + id);
        }
        return parseValue(parts, startIndex, parts.length, id);
    }

    private static double[] parseTwoValues(String[] parts, int startIndex, String id) {
        if (parts.length - startIndex < 2) {
            throw new IllegalArgumentException("Between condition requires two values in id: " + id);
        }

        List<double[]> candidates = new ArrayList<>();
        for (int split = startIndex + 1; split < parts.length; split++) {
            try {
                candidates.add(new double[] {
                        parseValue(parts, startIndex, split, id),
                        parseValue(parts, split, parts.length, id)
                });
            } catch (IllegalArgumentException ignored) {
                // Try the next split; decimal values are encoded with underscores.
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(
                    "Between condition values are ambiguous or invalid in id: " + id);
        }
        return candidates.getFirst();
    }

    private static double parseValue(String[] parts, int startIndex, int endIndex, String id) {
        if (startIndex >= endIndex) {
            throw new IllegalArgumentException("Empty numeric value in id: " + id);
        }
        StringBuilder value = new StringBuilder();
        for (int i = startIndex; i < endIndex; i++) {
            if (i > startIndex) {
                value.append('.');
            }
            value.append(parts[i]);
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value in condition id: " + id, e);
        }
    }

    private record ParsedOperator(Operator operator, int nextIndex) {
    }
}
