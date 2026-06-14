package query.utils;

import component.operator.in1.map.MapFunction;
import usecase.common.Tuple;

import java.util.Objects;
import java.util.Random;

import static query.utils.OperatorUtils.requireFinite;

public class ConditionPreservingRIRMap implements MapFunction<Tuple, Tuple> {

    private static final int MAX_ATTEMPTS = 64;

    private final String field;
    private final TupleConditionSpec condition;
    private final TupleFieldType fieldType;
    private final Random random;
    private final ObservedCategoricalSampler categoricalSampler = new ObservedCategoricalSampler();
    private double continuousMin = Double.POSITIVE_INFINITY;
    private double continuousMax = Double.NEGATIVE_INFINITY;
    private long discreteMin = Long.MAX_VALUE;
    private long discreteMax = Long.MIN_VALUE;

    public ConditionPreservingRIRMap(
            String field,
            TupleConditionSpec condition,
            TupleFieldType fieldType,
            long seed) {
        this.field = Objects.requireNonNull(field, "field");
        this.condition = Objects.requireNonNull(condition, "condition");
        this.fieldType = Objects.requireNonNull(fieldType, "fieldType");
        this.random = new Random(seed);
    }

    @Override
    public Tuple apply(Tuple in) {
        if (in == null) {
            return null;
        }

        double currentValue = requireFinite(field, in.lookup(field));
        observe(currentValue);
        boolean originalConditionValue = condition.test(in);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            Tuple candidate = new Tuple(in);
            candidate.set(field, candidateValue(currentValue));
            if (condition.test(candidate) == originalConditionValue) {
                return candidate;
            }
        }
        return in;
    }

    private void observe(double value) {
        switch (fieldType) {
            case CONTINUOUS_NUMERIC -> {
                continuousMin = Math.min(continuousMin, value);
                continuousMax = Math.max(continuousMax, value);
            }
            case DISCRETE_NUMERIC -> {
                long roundedValue = Math.round(value);
                discreteMin = Math.min(discreteMin, roundedValue);
                discreteMax = Math.max(discreteMax, roundedValue);
            }
            case NOMINAL_CATEGORICAL -> categoricalSampler.observe(value);
        }
    }

    private double candidateValue(double currentValue) {
        return switch (fieldType) {
            case CONTINUOUS_NUMERIC -> continuousMax > continuousMin
                    ? continuousMin + random.nextDouble() * (continuousMax - continuousMin)
                    : currentValue;
            case DISCRETE_NUMERIC -> discreteMax > discreteMin
                    ? discreteMin + random.nextLong(discreteMax - discreteMin + 1L)
                    : discreteMin;
            case NOMINAL_CATEGORICAL -> categoricalSampler.sampleDifferentIfPossible(random, currentValue);
        };
    }
}
