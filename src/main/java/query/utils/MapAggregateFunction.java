package query.utils;

import component.operator.in1.map.MapFunction;
import mappers.QueryMapper.MapAggregateAggregatorFunction;
import usecase.common.Tuple;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A stateful MapFunction that replaces an attribute's value with a moving average
 * The average is calculated over the last N valid (non-NaN) values encountered in the stream
 *
 * This class is stateful and maintains an internal buffer of recent values
 */
public class MapAggregateFunction implements MapFunction<Tuple, Tuple> {

    private final String field;
    private final int windowSize;
    private final MapAggregateAggregatorFunction function;

    private final Deque<Double> windowBuffer = new ArrayDeque<>();

    public MapAggregateFunction(String field, MapAggregateAggregatorFunction function, int windowSize) {
        this.field = field;
        this.function = function;
        this.windowSize = windowSize;
    }

    // Applies the moving average transformation to a single event
    @Override
    public Tuple apply(Tuple in) {
        if (in == null) {
            return null;
        }

        // Extract the value of the target attribute from the current event
        double val = in.lookup(field);

        // If the current value is NaN, do not update the window or apply a new value
        if (Double.isNaN(val)) {
            return new Tuple(in);
        }

        // Add the new valid value to the end of the window buffer
        windowBuffer.addLast(val);

        // If the buffer has exceeded its maximum size, remove the oldest element from the front
        if (windowBuffer.size() > windowSize) {
            windowBuffer.removeFirst();
        }

        double aggregatedValue;

        // Choose the right function
        switch (function) {
            case AVG -> {
                double sum = 0.0;
                for (double v : windowBuffer) {
                    sum += v;
                }
                aggregatedValue = sum / windowBuffer.size();
            }
            case MIN -> {
                double min = Double.POSITIVE_INFINITY;
                for (double v : windowBuffer) {
                    if (v < min) {
                        min = v;
                    }
                }
                aggregatedValue = min;
            }
            case MAX -> {
                double max = Double.NEGATIVE_INFINITY;
                for (double v : windowBuffer) {
                    if (v > max) {
                        max = v;
                    }
                }
                aggregatedValue = max;
            }
            default -> throw new IllegalStateException("Unexpected aggregation function: " + function);
        }

        // Create a copy of the event and set the calculated transformation on the corresponding attribute
        Tuple out = new Tuple(in);
       out.set(field, aggregatedValue);

        return out;
    }

    @Override
    public void enable() {
        // No specific action needed on enable
    }

    @Override
    public void disable() {
        // No specific action needed on disable
    }
}
