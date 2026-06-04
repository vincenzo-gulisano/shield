package query.utils;

import component.operator.in1.map.FlatMapFunction;
import java.util.List;

public interface FlushableFlatMapFunction<IN, OUT> extends FlatMapFunction<IN, OUT> {

    List<OUT> flush();
}
