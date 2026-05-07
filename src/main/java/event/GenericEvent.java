package event;

import common.tuple.BaseRichTuple;
import metrics.privacy.DoubleFieldLookup;
import usecase.common.Tuple;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

// GenericEvent represents a single event (tuple) in the stream
public class GenericEvent extends Tuple {

    public enum EventType {
        NORMAL,
        DUPLICATE,
        EMPTY_WINDOW
    }

    // Map containing all numeric attributes of the event
    private final Map<String, Double> attributes = new HashMap<>();
    private EventType eventType;

    // Base constructor
    public GenericEvent(long timestamp, String key) {
        super(timestamp, key);
        this.eventType = EventType.NORMAL;
    }

    // Copy constructor
    public GenericEvent(GenericEvent other) {
        super(other.timestamp, other.key);
        this.attributes.putAll(other.attributes);
        this.eventType = other.eventType;
    }

    // Specialized constructor for creating a copy of an event while explicitly setting a different EventType
    public GenericEvent(GenericEvent other, EventType eventType) {
        this(other); // Reuse the copy constructor
        this.setEventType(eventType);
    }

    // Factory method for creating an EMPTY_WINDOW event
    public static GenericEvent createEmptyEvent(long timestampMillis) {
        GenericEvent emptyEvent = new GenericEvent(timestampMillis, "-1");
        emptyEvent.setEventType(EventType.EMPTY_WINDOW);
        return emptyEvent;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    // Assign a numeric attribute to the event
    public void setAttribute(String name, Double value) {
        this.attributes.put(name, value);
    }

    // Retrieves the value of a numeric attribute
    public Double getAttribute(String name) {
        return this.attributes.getOrDefault(name, Double.NaN);
    }

    public Map<String, Double> getAllAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        String attrs = attributes.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        return String.format("Event{timestamp=%d, key=%s, type=%s, attributes={%s}}",
                getTimestamp(), getKey(), eventType, attrs);
    }

    @Override
    public double lookup(String fieldName) {
        return getAttribute(fieldName);
    }
}