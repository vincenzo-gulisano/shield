package usecase.common;

/**
 * Samples replacement values for tuple fields.
 *
 * <p>Sampling is stateful/random and is not guaranteed deterministic across calls.
 */
public interface FieldValueSampler {

    /**
     * Uniform random in range for the given field.
     */
    double urir(String field);

    /**
     * Distribution-based random in range for the given field.
     */
    double drir(String field);

    /**
     * Uniform categorical random for the given field.
     */
    double ucr(String field);

    /**
     * Distribution-based categorical random for the given field.
     */
    double dcr(String field);
}
