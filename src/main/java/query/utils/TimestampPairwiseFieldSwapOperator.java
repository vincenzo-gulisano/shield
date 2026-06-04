package query.utils;

import component.operator.in1.map.FlatMapOperator;
import java.util.List;

/**
 * Flat-map operator that exposes {@link TimestampPairwiseFieldSwapFunction#flush()} to Liebre's
 * end-of-input lifecycle.
 */
public class TimestampPairwiseFieldSwapOperator<IN, OUT> extends FlatMapOperator<IN, OUT> {

    private final FlushableFlatMapFunction<IN, OUT> function;

    public TimestampPairwiseFieldSwapOperator(String id, FlushableFlatMapFunction<IN, OUT> function) {
        super(id, function);
        this.function = function;
    }

    @Override
    protected List<OUT> processEndOfInput() {
        return function.flush();
    }
}
