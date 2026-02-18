package event;

import event.GenericEvent.EventType;

import java.util.HashSet;
import java.util.Set;

public class EventFactory {

    private static Set<String> numeric_attributes = Set.of();

    public static void setNumericAttributes(Set<String> numericAttributes) {
        numeric_attributes = new HashSet<>(numericAttributes);
    }

    // Create a GenericEvent instance from a single CSV line
    public static GenericEvent createEventFromLine(
            String line,
            String[] headers,
            String keyColumnName,
            long idCounter) {

        try {
            // Split the CSV line into individual tokens using comma as delimiter
            String[] tokens = line.split(String.valueOf(','));

            // Locate the index of the timestamp and key column
            int tsIndex = findHeaderIndex(headers, "timestamp");
            int keyIndex = findHeaderIndex(headers, keyColumnName);

            // Parse timestamp (expected to be already in milliseconds)
            long tsInMillis = Long.parseLong(tokens[tsIndex].trim());

            // Determine the event key, if no key column is provided, use the sequential ID as a fallback
            String key = keyColumnName.isEmpty() ? "GLOBAL" : tokens[keyIndex].trim();

            GenericEvent event = new GenericEvent(tsInMillis, key);

            // Assign a sequential internal ID as a numeric attribute
            event.setAttribute("ID", (double) idCounter);

            // If an "EventType" column exists, try to parse it into the enum
            // If parsing fails, the event keeps the default type (NORMAL)
            int eventTypeIndex = findHeaderIndex(headers, "EventType");
            if (eventTypeIndex != -1 && eventTypeIndex < tokens.length) {
                String typeString = tokens[eventTypeIndex].trim();
                if (!typeString.isEmpty()) {
                    try {
                        EventType parsedType = EventType.valueOf(typeString.toUpperCase());
                        event.setEventType(parsedType);
                    } catch (IllegalArgumentException e) {
                        // Invalid EventType values are ignored
                    }
                }
            }

            // Iterate over all columns and parse numeric attribute
            for (int i = 0; i < headers.length; i++) {
                if (i < tokens.length) {
                    String attrName = headers[i];

                    // Skip non-numeric or structural columns
                    if (!numeric_attributes.contains(attrName)) {
                        continue; // identico al DataLoader
                    }

                    String rawValue = tokens[i].trim();

                    // Handle missing or NaN values explicitly
                    if (rawValue.isEmpty() || rawValue.equalsIgnoreCase("NaN")) {
                        event.setAttribute(attrName, Double.NaN);
                    } else {
                        event.setAttribute(attrName, Double.parseDouble(rawValue));
                    }
                }
            }
            return event;
        } catch (Exception e) {
            // Any unexpected error causes the record to be discarded
            return null;
        }
    }

    // Utility method that returns the index of a column name in the header array
    private static int findHeaderIndex(String[] headers, String name) {
        if (name == null || name.isEmpty()) return -1;
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equalsIgnoreCase(name)) return i;
        }
        return -1;
    }
}