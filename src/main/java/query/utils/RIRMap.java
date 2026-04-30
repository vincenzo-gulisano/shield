package query.utils;

import component.operator.in1.map.MapFunction;
import event.GenericEvent;
import mappers.QueryRepresentation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * A stateful MapFunction that replaces an attribute's value with a moving average
 * The average is calculated over the last N valid (non-NaN) values encountered in the stream
 *
 * This class is stateful and maintains an internal buffer of recent values
 */
public class RIRMap implements MapFunction<GenericEvent, GenericEvent> {

    private final String attribute;
    private double min = Double.MAX_VALUE;
    private double max = Double.MIN_VALUE;
    private final Random random;
    
    public RIRMap(String attribute) {
        this.attribute = attribute;
        this.random = new Random();
    }

    // Applies the moving average transformation to a single event
    @Override
    public GenericEvent apply(GenericEvent currentEvent) {
        if (currentEvent == null) {
            return null;
        }

        // Extract the value of the target attribute from the current event
        double currentValue = OperatorUtils.getAttributeValue(currentEvent, attribute);

        // If the current value is NaN, do not update the window or apply a new value
        if (Double.isNaN(currentValue)) {
            return new GenericEvent(currentEvent);
        }

        // Update the min and max values
        if (currentValue < min) {
            min = currentValue;
        }
        if (currentValue > max) {
            max = currentValue;
        }

        // Create a copy of the event and set the calculated transformation on the corresponding attribute
        GenericEvent anonymizedEvent = new GenericEvent(currentEvent);
        OperatorUtils.setAttributeValue(anonymizedEvent, attribute, this.random.nextDouble(min, max));

        return anonymizedEvent;
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
