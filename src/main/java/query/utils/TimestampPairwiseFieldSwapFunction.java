package query.utils;

import java.util.List;
import usecase.common.Tuple;

/**
 * Flat-map function that swaps one field between adjacent tuples that share the same timestamp.
 *
 * <p>The function preserves tuple cardinality and order: each input tuple is eventually emitted
 * exactly once, either as part of a same-timestamp swapped pair or unchanged when it has no
 * same-timestamp partner.
 */
public class TimestampPairwiseFieldSwapFunction implements FlushableFlatMapFunction<Tuple, Tuple> {

    private final String field;
    private Tuple bufferedTuple;

    public TimestampPairwiseFieldSwapFunction(String field) {
        this.field = field;
    }

    @Override
    public List<Tuple> apply(Tuple in) {
        if (in == null) {
            return List.of();
        }

        Tuple current = new Tuple(in);
        if (bufferedTuple == null) {
            // Hold the first tuple of a possible pair. Nothing is emitted yet, but flush()
            // guarantees this tuple is eventually output even if no partner ever arrives.
            bufferedTuple = current;
            return List.of();
        }

        if (bufferedTuple.getTimestamp() == current.getTimestamp()) {
            Tuple first = new Tuple(bufferedTuple);
            Tuple second = new Tuple(current);
            double firstValue = first.lookup(field);
            double secondValue = second.lookup(field);
            first.set(field, secondValue);
            second.set(field, firstValue);
            bufferedTuple = null;
            return List.of(first, second);
        }

        Tuple emitted = new Tuple(bufferedTuple);
        // A timestamp change means the buffered tuple cannot be paired without crossing
        // timestamp groups. Emit it unchanged, then start a new pending pair with current.
        bufferedTuple = current;
        return List.of(emitted);
    }

    /**
     * Emit the final unpaired tuple unchanged.
     *
     * <p>Liebre calls this through {@link TimestampPairwiseFieldSwapOperator#processEndOfInput()}.
     * This is the only end-of-stream path and is what preserves one output per input when a
     * timestamp group has odd cardinality or the whole stream has a single pending tuple.
     */
    public List<Tuple> flush() {
        if (bufferedTuple == null) {
            return List.of();
        }
        Tuple emitted = new Tuple(bufferedTuple);
        bufferedTuple = null;
        return List.of(emitted);
    }

    @Override
    public void enable() {
        bufferedTuple = null;
    }

    @Override
    public void disable() {
        bufferedTuple = null;
    }
}
