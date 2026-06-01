package query.utils;

import event.GenericEvent;
import mappers.QueryRepresentation;

public class OperatorUtils {

    // Helper method that evaluates if an event satisfies a given condition for the filter operator
    public static boolean evaluateCondition(GenericEvent event, QueryRepresentation.FilterArgs args) {
        if (event == null) return false;

        double eventValue = event.getAttribute(args.variable());
        if (Double.isNaN(eventValue)) return false;

        double conditionValue = args.value();
        QueryRepresentation.Condition condition = args.condition();

        return switch (condition) {
            case LESS_THAN -> eventValue < conditionValue;
            case GREATER_THAN -> eventValue > conditionValue;
        };
    }

    // Helper method that applies a noise value to a specific attribute of an event
    public static GenericEvent applyNoise(GenericEvent originalEvent, String attributeToModify, double originalValue, double noise) {
        GenericEvent noisyEvent = new GenericEvent(originalEvent);

        double newValue = originalValue + noise;
        noisyEvent.setAttribute(attributeToModify, newValue);

        return noisyEvent;
    }

    // Helper method to obtain the value of an attribute from an event
    public static double getAttributeValue(GenericEvent event, String attributeName) {
        return event.getAttribute(attributeName);
    }

    public static double requireFinite(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Field " + field + " has non-finite value: " + value);
        }
        return value;
    }

    // Helper method that sets the value of a specified attribute
    public static void setAttributeValue(GenericEvent event, String attributeToSet, double newValue) {
        if (event == null) return;
        event.setAttribute(attributeToSet, newValue);
    }
}
