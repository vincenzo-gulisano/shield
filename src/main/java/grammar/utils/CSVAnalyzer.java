package grammar.utils;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CSVAnalyzer {

    // Data structure holding basic statistics for a numeric attribute
    public record AttributeStats(double min, double max, int minIntDigits, int maxIntDigits) {}

    // Helper method to read the CSV file in the package resources
    private static BufferedReader getReaderForResource(String resourcePath) throws IOException {
        InputStream in = CSVAnalyzer.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) throw new IOException("Resource not found in classpath: " + resourcePath);
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    // Read the first line of the CSV file and extract the column names
    public static List<String> extractAttributes(String resourcePath, List<String> excludedColumns) throws IOException {
        try (BufferedReader reader = getReaderForResource(resourcePath)) {
            String header = reader.readLine();
            if (header == null) throw new IOException("CSV file is invalid or empty");

            return Arrays.stream(header.split(","))
                    .map(String::trim)
                    .filter(h -> !h.isEmpty() && !excludedColumns.contains(h))
                    .collect(Collectors.toList());
        }
    }

    // Analyze the dataset and create a map with the attribute name and its stats (min, max, minDigits, maxDigits)
    public static Map<String, AttributeStats> analyze(String resourcePath, List<String> headersOfInterest) throws IOException {

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build();
        Map<String, AttributeStats> statsMap = new HashMap<>();

        InputStream is = CSVAnalyzer.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("Resource not found in classpath: " + resourcePath);
        }

        try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, format)) {

            for (CSVRecord record : parser) {
                // Process only the attributes we are interested in
                for (String header : headersOfInterest) {
                    if (!record.isMapped(header)) continue;

                    String rawValue = record.get(header);
                    if (rawValue.isEmpty() || rawValue.equalsIgnoreCase("NaN")) continue;

                    try {
                        double value = Double.parseDouble(rawValue);

                        // Extract the integer part to count digits
                        String[] parts = rawValue.split("\\.");
                        String intPartString = parts[0].replace("-", "");
                        // Determine the number of digits in the integer part
                        int intDigits = intPartString.length();

                        // Update statistics for this attribute
                        statsMap.compute(header, (k, current) -> {
                            if (current == null) {
                                return new AttributeStats(value, value, intDigits, intDigits);
                            } else {
                                // Update min/max values and digit counts
                                return new AttributeStats(
                                        Math.min(current.min(), value), Math.max(current.max(), value),
                                        Math.min(current.minIntDigits(), intDigits), Math.max(current.maxIntDigits(), intDigits)
                                );
                            }
                        });
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return statsMap;
    }
}