package query.utils;

import component.operator.in1.map.MapFunction;
import java.util.Random;
import usecase.common.Tuple;

import static query.utils.OperatorUtils.requireFinite;

/**
 * Random replacement map for nominal categorical tuple fields.
 *
 * <p>The replacement domain is the set of finite values observed so far in the stream. When more
 * than one category has been observed, the sampled value is preferably different from the current
 * value.
 */
public class CategoricalRIRMap implements MapFunction<Tuple, Tuple> {

    private static final long DEFAULT_RANDOM_SEED = 0x5EED_DA7A_C471L;

    private final String field;
    private final Random random;
    private final ObservedCategoricalSampler sampler = new ObservedCategoricalSampler();

    public CategoricalRIRMap(String field) {
        this(field, DEFAULT_RANDOM_SEED);
    }

    public CategoricalRIRMap(String field, long seed) {
        this.field = field;
        this.random = new Random(seed);
    }

    @Override
    public Tuple apply(Tuple in) {
        if (in == null) {
            return null;
        }

        double value = requireFinite(field, in.lookup(field));

        sampler.observe(value);
        Tuple out = new Tuple(in);
        out.set(field, sampler.sampleDifferentIfPossible(random, value));
        return out;
    }
}
