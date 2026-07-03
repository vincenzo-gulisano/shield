package query.utils;

import java.util.Objects;
import java.util.function.Predicate;
import usecase.common.Tuple;

/**
 * Named tuple predicate used by query-condition-aware anonymization operators.
 *
 * <p>The spec is intentionally independent of any concrete use case. A use case registers one or
 * more specs in {@link TupleConditionRegistry}, and grammar-mapped operators refer to them only by
 * {@link #id()}. For example, LCL-flow and GeoLife can both define contributor conditions while
 * sharing the same mapper/operator implementation.
 *
 * @param id stable identifier used in serialized operators and generated mapper output
 * @param field optional tuple field associated with the condition, or {@code null} when the
 *              condition is not tied to a single field
 * @param predicate predicate that evaluates the condition for one tuple
 */
public record TupleConditionSpec(
        String id,
        String field,
        Predicate<Tuple> predicate) {

    /**
     * Creates a condition spec.
     *
     * <p>The identifier and predicate are required. The field is optional because some conditions,
     * such as provenance contributor membership, are properties of the tuple as a whole rather than
     * of one field.
     */
    public TupleConditionSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(predicate, "predicate");
    }

    /**
     * Returns the condition registered with the given identifier.
     *
     * @param id condition identifier used by an anonymization operator
     * @return the registered condition spec
     */
    public static TupleConditionSpec fromId(String id) {
        return TupleConditionRegistry.fromId(id);
    }

    /**
     * Evaluates this condition for a tuple.
     *
     * @param tuple tuple to test
     * @return {@code true} if the tuple satisfies this condition
     */
    public boolean test(Tuple tuple) {
        return predicate.test(tuple);
    }
}
