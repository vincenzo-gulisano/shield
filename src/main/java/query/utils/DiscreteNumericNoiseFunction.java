package query.utils;

import component.operator.in1.map.MapFunction;
import java.util.Random;
import usecase.common.Tuple;

import static query.utils.OperatorUtils.requireFinite;

/**
 * Gaussian percentage noise for discrete numeric fields.
 *
 * <p>The perturbation is computed like {@link MapNoiseFunction}, then rounded to the nearest
 * integer-valued double so discrete numeric fields do not become fractional.
 */
public class DiscreteNumericNoiseFunction implements MapFunction<Tuple, Tuple> {

    private static final long DEFAULT_RANDOM_SEED = 0x5EED_DA7A_D15CL;

    private final String field;
    private final double percentage;
    private final Random random;

    public DiscreteNumericNoiseFunction(String field, double percentage) {
        this(field, percentage, DEFAULT_RANDOM_SEED);
    }

    public DiscreteNumericNoiseFunction(String field, double percentage, long seed) {
        this.field = field;
        this.percentage = percentage;
        this.random = new Random(seed);
    }

    @Override
    public Tuple apply(Tuple in) {
        if (in == null) {
            return null;
        }

        double value = requireFinite(field, in.lookup(field));

        Tuple out = new Tuple(in);
        double noisyValue = value + random.nextGaussian() * percentage * Math.abs(value);
        out.set(field, Math.rint(noisyValue));
        return out;
    }
}
