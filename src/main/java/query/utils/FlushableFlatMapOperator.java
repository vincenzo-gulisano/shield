package query.utils;

import component.operator.in1.map.FlatMapOperator;
import component.operator.Operator;
import experimental.provenance.ProvenanceTransformableOperator;
import experimental.provenance.ProvenanceTransformationContext;
import java.util.List;

/**
 * Flat-map operator that gives stateful flat-map functions an end-of-input flush hook.
 */
public class FlushableFlatMapOperator<IN, OUT> extends FlatMapOperator<IN, OUT>
        implements ProvenanceTransformableOperator {

    private final FlushableFlatMapFunction<IN, OUT> function;

    public FlushableFlatMapOperator(String id, FlushableFlatMapFunction<IN, OUT> function) {
        super(id, function);
        this.function = function;
    }

    @Override
    protected List<OUT> processEndOfInput() {
        return function.flush();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Operator<?, ?> createProvenanceOperator(ProvenanceTransformationContext context) {
        if (!(function instanceof ProvenanceAwareFlushableFlatMapFunction<?, ?> provenanceAwareFunction)) {
            throw new UnsupportedOperationException(
                    "Flushable flat-map function does not define provenance behavior: "
                            + function.getClass().getName());
        }
        return new FlushableFlatMapOperator<>(
                getId(),
                ((ProvenanceAwareFlushableFlatMapFunction<IN, OUT>) provenanceAwareFunction)
                        .createProvenanceFunction(context));
    }
}
