package grammar.generator;

/**
 * Semantic type of a tuple field for type-aware grammar generation.
 *
 * <p>The type is used only to decide which anonymization operators may be generated for a field.
 * It does not change mapper behavior by itself; typed grammar nodes still need to be wired into
 * the mapper before they can be executed.
 */
public enum FieldType {

    /**
     * Label-like values with no numeric order.
     *
     * <p>Examples: gender, race, country, diagnosis code. Valid transformations should preserve
     * the field as one of the known categories.
     */
    NOMINAL_CATEGORICAL,

    /**
     * Ordered numeric values whose valid values are discrete.
     *
     * <p>Examples: age in years, number of visits, counts. Numeric comparisons are meaningful, but
     * generated values should be rounded or sampled from valid discrete values.
     */
    DISCRETE_NUMERIC,

    /**
     * Ordered numeric values on a continuous scale.
     *
     * <p>Examples: weight, height, laboratory values, income ratio. Numeric perturbation,
     * smoothing, and range sampling are meaningful if values remain in a valid range.
     */
    CONTINUOUS_NUMERIC
}
