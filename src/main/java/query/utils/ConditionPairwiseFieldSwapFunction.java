package query.utils;

import usecase.common.Tuple;

import java.util.List;
import java.util.Objects;

public class ConditionPairwiseFieldSwapFunction implements FlushableFlatMapFunction<Tuple, Tuple> {

    private final String field;
    private final TupleConditionSpec condition;
    private Tuple bufferedTuple;

    public ConditionPairwiseFieldSwapFunction(String field, TupleConditionSpec condition) {
        this.field = Objects.requireNonNull(field, "field");
        this.condition = Objects.requireNonNull(condition, "condition");
    }

    @Override
    public List<Tuple> apply(Tuple in) {
        if (in == null) {
            return List.of();
        }

        Tuple current = new Tuple(in);
        if (bufferedTuple == null) {
            bufferedTuple = current;
            return List.of();
        }

        if (current.getTimestamp() < bufferedTuple.getTimestamp()) {
            throw new IllegalArgumentException("ConditionPairwiseFieldSwapFunction requires non-decreasing timestamps");
        }

        if (bufferedTuple.getTimestamp() == current.getTimestamp()
                && condition.test(bufferedTuple) == condition.test(current)) {
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
        bufferedTuple = current;
        return List.of(emitted);
    }

    @Override
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
