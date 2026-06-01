package usecase.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Field-value sampler backed by the finite values found in a set of tuples.
 *
 * <p>Sampling is stateful/random and is not guaranteed deterministic across calls. Even when a seed
 * is supplied, results depend on call order. Numeric range sampling and categorical sampling use
 * the same random sequence.
 */
public final class TupleFieldValueSampler implements FieldValueSampler {

    private static final long DEFAULT_RANDOM_SEED = 0x5EED_DA7A_51A7L;

    private final Map<String, FieldValues> valuesByField;
    private final Random random;

    /**
     * Build a sampler using the default random seed.
     *
     * <p>The sampler scans all tuple fields named {@code f1..fN}, keeps only finite values, and
     * precomputes the range, empirical values, and unique category values used by
     * {@link #urir(String)}, {@link #drir(String)}, {@link #ucr(String)}, and {@link #dcr(String)}.
     */
    public TupleFieldValueSampler(List<? extends Tuple> tuples) {
        this(tuples, DEFAULT_RANDOM_SEED);
    }

    /**
     * Build a sampler using a caller-provided random seed.
     *
     * <p>The seed makes a single sequence reproducible, but values are still call-order dependent:
     * asking for fields in a different order can produce different sampled values.
     */
    public TupleFieldValueSampler(List<? extends Tuple> tuples, long seed) {
        if (tuples == null) {
            throw new IllegalArgumentException("tuples cannot be null");
        }
        this.valuesByField = buildValuesByField(tuples);
        this.random = new Random(seed);
    }

    /**
     * Sample uniformly from the observed finite range of {@code field}.
     *
     * <p>For a field with minimum {@code min} and maximum {@code max}, this returns a random value in
     * {@code [min, max]}. If all observed values are equal, that value is returned.
     */
    @Override
    public double urir(String field) {
        FieldValues values = valuesFor(field);
        if (values.max() == values.min()) {
            return values.min();
        }
        return values.min() + random.nextDouble() * (values.max() - values.min());
    }

    /**
     * Sample from the empirical distribution of {@code field}.
     *
     * <p>This draws one of the observed finite values for the field, with each observed tuple value
     * having equal probability. Repeated values therefore naturally have more weight.
     */
    @Override
    public double drir(String field) {
        FieldValues values = valuesFor(field);
        return values.values().get(random.nextInt(values.values().size()));
    }

    /**
     * Sample uniformly from the unique observed categories of {@code field}.
     *
     * <p>Each distinct finite value observed in the field has equal probability, regardless of how
     * often that value appeared in the input tuples.
     */
    @Override
    public double ucr(String field) {
        FieldValues values = valuesFor(field);
        return values.uniqueValues().get(random.nextInt(values.uniqueValues().size()));
    }

    /**
     * Sample from the empirical categorical distribution of {@code field}.
     *
     * <p>This currently has the same sampling mechanics as {@link #drir(String)}: each observed
     * tuple value has equal probability, so repeated categories naturally have more weight.
     */
    @Override
    public double dcr(String field) {
        return drir(field);
    }

    private FieldValues valuesFor(String field) {
        FieldValues values = valuesByField.get(field);
        if (values == null) {
            throw new IllegalArgumentException("Unknown field: " + field);
        }
        return values;
    }

    private static Map<String, FieldValues> buildValuesByField(List<? extends Tuple> tuples) {
        if (tuples.isEmpty()) {
            throw new IllegalArgumentException("tuples cannot be empty");
        }

        int numFields = tuples.get(0).getNumFields();
        // Keep raw finite values by field so drir preserves empirical frequencies.
        List<List<Double>> valuesByIndex = new ArrayList<>(numFields);
        for (int i = 0; i < numFields; i++) {
            valuesByIndex.add(new ArrayList<>());
        }

        for (Tuple tuple : tuples) {
            if (tuple.getNumFields() != numFields) {
                throw new IllegalArgumentException(
                        "Expected " + numFields + " fields, found " + tuple.getNumFields());
            }
            for (int i = 0; i < numFields; i++) {
                double value = tuple.getField("f" + (i + 1));
                if (Double.isFinite(value)) {
                    valuesByIndex.get(i).add(value);
                }
            }
        }

        Map<String, FieldValues> result = new HashMap<>();
        for (int i = 0; i < numFields; i++) {
            String field = "f" + (i + 1);
            List<Double> values = valuesByIndex.get(i);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Field has no finite values: " + field);
            }
            result.put(field, FieldValues.from(values));
        }
        return Map.copyOf(result);
    }

    private record FieldValues(List<Double> values, List<Double> uniqueValues, double min, double max) {
        private static FieldValues from(List<Double> values) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            Set<Double> uniqueValues = new LinkedHashSet<>();
            for (double value : values) {
                min = Math.min(min, value);
                max = Math.max(max, value);
                uniqueValues.add(value);
            }
            return new FieldValues(List.copyOf(values), List.copyOf(uniqueValues), min, max);
        }
    }
}
