package event;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DataLoader is a utility class for loading events from a CSV file.
 *
 * Expected input format:
 * - A column named 'timestamp' containing time values in milliseconds.
 * - Optional missing values represented as the string "NaN".
 *
 * Main responsibilities:
 * - Parse the CSV file from the classpath.
 * - Automatically detect numeric attributes (used as quasi-identifiers).
 * - Generate a sequential ID for each event.
 * - Use a specified column as a partitioning key (e.g., user or sensor ID).
 */
public class DataLoader {

    private static final Logger logger = LoggerFactory.getLogger(DataLoader.class);
    public record LoadResult(List<GenericEvent> events, List<String> numericAttributes) {}

    private final String resourcePath;
    private final String keyColumn; // Name of the column used as partitioning key
    private final List<String> excludedColumns; // Columns that must not be considered numeric attributes

    public DataLoader(String resourcePath, String keyColumn) {
        this.resourcePath = resourcePath;
        this.keyColumn = keyColumn.trim();

        // Columns that must never be treated as numeric attributes
        this.excludedColumns = new ArrayList<>();
        this.excludedColumns.add("timestamp");
        this.excludedColumns.add("ID");
        if (!this.keyColumn.isEmpty()) {
            this.excludedColumns.add(this.keyColumn);
        }
    }

    // Load and parses the CSV file
    public LoadResult load() throws IOException {

        // CSV configuration: comma-separated values, dot as decimal separator, first row treated as header
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setTrim(true).build();

        List<GenericEvent> events = new ArrayList<>();
        Set<String> numericHeaders = new HashSet<>();

        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);

        if (is == null) {
            logger.error("Resource not found in classpath: {}", resourcePath);
            throw new IOException("Resource not found in classpath: " + resourcePath);
        }

        try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, format)) {

            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                return new LoadResult(events, new ArrayList<>());
            }

            // Automatically detect numeric attributes
            List<String> allHeaders = new ArrayList<>(parser.getHeaderMap().keySet());
            for (String header : allHeaders) {
                if (!this.excludedColumns.contains(header) && isNumericColumn(records, header)) {
                    numericHeaders.add(header);
                }
            }
            logger.info("Detected numeric attributes: {}", numericHeaders);

            long idCounter = 0;
            // Parse each CSV record into a GenericEvent
            for (CSVRecord record : records) {
                try {
                    long tsInMillis = Long.parseLong(record.get("timestamp"));
                    String key = !this.keyColumn.isEmpty() ? record.get(this.keyColumn) : "GLOBAL";

                    GenericEvent event = new GenericEvent(tsInMillis, key);
                    // Assign a sequential internal ID
                    event.setAttribute("ID", (double) idCounter++);

                    // Load numeric attributes
                    for (String attrName : numericHeaders) {
                        String rawValue = record.get(attrName);
                        double value;
                        if (rawValue.isEmpty() || rawValue.equalsIgnoreCase("NaN")) {
                            value = Double.NaN;
                        } else {
                            value = Double.parseDouble(rawValue);
                        }
                        event.setAttribute(attrName, value);
                    }

                    events.add(event);

                } catch (Exception e) {
                    // Skip malformed records but keep processing
                    logger.warn("Skipping record due to parsing error", e);
                }
            }
        }
        return new LoadResult(events, new ArrayList<>(numericHeaders));
    }

    // Check whether a column can be safely interpreted as numeric.
    private boolean isNumericColumn(List<CSVRecord> records, String header) {
        for (CSVRecord record : records) {
            String value = record.get(header);
            if (value != null && !value.trim().isEmpty()) {
                if (value.trim().equalsIgnoreCase("NaN")) {
                    continue;
                }
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return true;
    }
}