package query.utils;

import component.operator.in1.map.MapFunction;
import java.util.Random;
import usecase.common.Tuple;

import static query.utils.OperatorUtils.requireFinite;

/**
 * Random-in-range map for discrete numeric fields.
 *
 * <p>The observed finite values define an integer range. Each finite input value is replaced with
 * a uniformly sampled integer-valued double in that observed range.
 */
public class DiscreteNumericRIRMap implements MapFunction<Tuple, Tuple> {

    private static final long DEFAULT_RANDOM_SEED = 0x5EED_DA7A_D121L;

    private final String field;
    private final Random random;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;

    public DiscreteNumericRIRMap(String field) {
        this(field, DEFAULT_RANDOM_SEED);
    }

    public DiscreteNumericRIRMap(String field, long seed) {
        this.field = field;
        this.random = new Random(seed);
    }

    @Override
    public Tuple apply(Tuple in) {
        if (in == null) {
            return null;
        }

        double value = requireFinite(field, in.lookup(field));

        long roundedValue = Math.round(value);
        min = Math.min(min, roundedValue);
        max = Math.max(max, roundedValue);

        long sampledValue = min == max ? min : min + random.nextLong(max - min + 1L);
        Tuple out = new Tuple(in);
        out.set(field, sampledValue);
        return out;
    }
}
