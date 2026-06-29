package query.utils;

import experimental.provenance.ProvenanceTransformationContext;

public interface ProvenanceAwareFlushableFlatMapFunction<IN, OUT> extends FlushableFlatMapFunction<IN, OUT> {

    FlushableFlatMapFunction<IN, OUT> createProvenanceFunction(ProvenanceTransformationContext context);
}
