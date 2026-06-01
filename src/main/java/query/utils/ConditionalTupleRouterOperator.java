package query.utils;

import component.operator.in1.filter.FilterFunction;
import component.operator.router.BaseRouterOperator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import stream.Stream;
import usecase.common.Tuple;

/**
 * Router that sends each tuple to exactly one of two outputs according to a condition.
 *
 * <p>The first connected output receives matching tuples, and the second connected output receives
 * non-matching tuples. The mapper/query builder is responsible for connecting outputs in that order.
 */
@SuppressWarnings("unchecked")
public class ConditionalTupleRouterOperator extends BaseRouterOperator<Tuple> {

    private final FilterFunction<Tuple> condition;

    public ConditionalTupleRouterOperator(String id, FilterFunction<Tuple> condition) {
        super(id);
        this.condition = condition;
    }

    @Override
    public Collection<? extends Stream<Tuple>> chooseOutputs(Tuple tuple) {
        List<Stream<Tuple>> outputs = new ArrayList<>();
        for (Stream<Tuple> output : getOutputs()) {
            outputs.add(output);
        }
        if (outputs.size() != 2) {
            throw new IllegalStateException(
                    "Conditional router requires exactly two outputs, found " + outputs.size());
        }
        return List.of(condition.test(tuple) ? outputs.get(0) : outputs.get(1));
    }
}
