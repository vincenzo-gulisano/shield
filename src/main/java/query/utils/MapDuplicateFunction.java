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
public class MapDuplicateFunction implements FlatMapFunction<Tuple, Tuple> {

    private static final long DEFAULT_RANDOM_SEED = 0x5EED_DA7A_D001_1E5L;

    private final Random random;
    private final double prob;

    public MapDuplicateFunction(double prob) {
        this(prob, DEFAULT_RANDOM_SEED);
    }

    public MapDuplicateFunction(double prob, long seed) {
        this.random = new Random(seed);
        this.prob = prob;
    }

    @Override
    public List<Tuple> apply(Tuple t) {
        List<Tuple> out = new LinkedList<>();
        out.add(t);
        if (random.nextDouble() <= prob) {
            out.add(t);
        }
        return out;
    }

}
