package query.utils;

import component.operator.in1.map.MapFunction;
import usecase.common.Tuple;

import java.util.Objects;
import java.util.Random;

import static query.utils.OperatorUtils.requireFinite;

public class ConditionPreservingNoiseFunction implements MapFunction<Tuple, Tuple> {

    private static final int MAX_ATTEMPTS = 32;

    private final String field;
    private final double probability;
    private final TupleConditionSpec condition;
    private final TupleFieldType fieldType;
    private final Random random;
    private final ObservedCategoricalSampler categoricalSampler = new ObservedCategoricalSampler();

    public ConditionPreservingNoiseFunction(
            String field,
            double probability,
            TupleConditionSpec condition,
            TupleFieldType fieldType,
            long seed) {
        this.field = Objects.requireNonNull(field, "field");
        this.probability = probability;
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
        boolean originalConditionValue = condition.test(in);
        if (fieldType == TupleFieldType.NOMINAL_CATEGORICAL) {
            categoricalSampler.observe(currentValue);
            if (random.nextDouble() > probability) {
                return in;
            }
        }

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            Tuple candidate = new Tuple(in);
            candidate.set(field, candidateValue(currentValue));
            if (condition.test(candidate) == originalConditionValue) {
                return candidate;
            }
        }
        return in;
    }

    private double candidateValue(double currentValue) {
        return switch (fieldType) {
            case CONTINUOUS_NUMERIC -> currentValue + random.nextGaussian() * probability * Math.abs(currentValue);
            case DISCRETE_NUMERIC -> Math.rint(currentValue + random.nextGaussian() * probability * Math.abs(currentValue));
            case NOMINAL_CATEGORICAL -> categoricalSampler.sampleDifferentIfPossible(random, currentValue);
        };
    }
}
