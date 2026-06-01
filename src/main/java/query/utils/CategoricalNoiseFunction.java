package query.utils;

import component.operator.in1.map.MapFunction;
import java.util.Random;
import usecase.common.Tuple;

import static query.utils.OperatorUtils.requireFinite;

/**
 * Randomized-response style map function for nominal categorical tuple fields.
 *
 * <p>Finite values observed in the stream define the category domain. With the configured
 * probability, the function replaces the field with a uniformly sampled observed category,
 * preferring a different category when more than one has been observed.
 */
public class CategoricalNoiseFunction implements MapFunction<Tuple, Tuple> {

    private static final long DEFAULT_RANDOM_SEED = 0x5EED_DA7A_CA7EL;

    private final String field;
    private final double probability;
    private final Random random;
    private final ObservedCategoricalSampler sampler = new ObservedCategoricalSampler();

    public CategoricalNoiseFunction(String field, double probability) {
        this(field, probability, DEFAULT_RANDOM_SEED);
    }

    public CategoricalNoiseFunction(String field, double probability, long seed) {
        this.field = field;
        this.probability = probability;
        this.random = new Random(seed);
    }

    @Override
    public Tuple apply(Tuple in) {
        if (in == null) {
            return null;
        }

        double value = requireFinite(field, in.lookup(field));

        sampler.observe(value);
        if (random.nextDouble() > probability) {
            return in;
        }

        Tuple out = new Tuple(in);
        out.set(field, sampler.sampleDifferentIfPossible(random, value));
        return out;
    }
}
