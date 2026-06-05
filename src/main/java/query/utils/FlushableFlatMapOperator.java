package query.utils;

import component.operator.in1.map.FlatMapOperator;
import java.util.List;

/**
 * Flat-map operator that gives stateful flat-map functions an end-of-input flush hook.
 */
public class FlushableFlatMapOperator<IN, OUT> extends FlatMapOperator<IN, OUT> {

    private final FlushableFlatMapFunction<IN, OUT> function;

    public FlushableFlatMapOperator(String id, FlushableFlatMapFunction<IN, OUT> function) {
        super(id, function);
        this.function = function;
    }

    @Override
    protected List<OUT> processEndOfInput() {
        return function.flush();
    }
}
