package query.utils;

import component.operator.in1.map.FlatMapFunction;
import component.operator.in1.map.MapFunction;
import event.GenericEvent;
import mappers.QueryRepresentation;
import usecase.common.Tuple;

import static query.utils.OperatorUtils.setAttributeValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * A stateful MapFunction that replaces an attribute's value with a moving
 * average
 * The average is calculated over the last N valid (non-NaN) values encountered
 * in the stream
 *
 * This class is stateful and maintains an internal buffer of recent values
 */
public class MapNoiseFunction implements MapFunction<Tuple, Tuple> {

    private static final long DEFAULT_RANDOM_SEED = 0x5EED_DA7A_5015E1L;

    private final Random random;
    private final String field;
    private final double percentage;

    public MapNoiseFunction(String field, double percentage) {
        this(field, percentage, DEFAULT_RANDOM_SEED);
    }

    public MapNoiseFunction(String field, double percentage, long seed) {
        this.random = new Random(seed);
        this.field = field;
        this.percentage = percentage;
    }

    @Override
    public Tuple apply(Tuple in) {
        if (in == null)
            return null;
        double v = in.lookup(field);
        if (Double.isNaN(v))
            return in;
        Tuple out = new Tuple(in);
        out.set(field, v + random.nextGaussian() * percentage * Math.abs(v));
        return out;
    }

}
