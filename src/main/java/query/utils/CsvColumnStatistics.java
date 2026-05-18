package query.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CsvColumnStatistics {

    public enum Statistic {
        FIRST_QUARTILE,
        THIRD_QUARTILE,
        MEAN,
        MEDIAN,
        PERCENTILE_01,
        PERCENTILE_05,
        PERCENTILE_95,
        PERCENTILE_99
    }

    private final Map<String, Map<Statistic, Double>> statsByColumn;

    public CsvColumnStatistics(String inputCsvPath) throws IOException {
        this(inputCsvPath, null);
    }

    public CsvColumnStatistics(String inputCsvPath, List<String> columnIds) throws IOException {
        statsByColumn = computeStats(inputCsvPath, columnIds);
    }

    public double getStatistic(String columnId, Statistic statistic) {
        Map<Statistic, Double> columnStats = statsByColumn.get(columnId);
        if (columnStats == null) {
            throw new IllegalArgumentException("Unknown numeric column: " + columnId);
        }
        return columnStats.get(statistic);
    }

    public boolean hasNumericColumn(String columnId) {
        return statsByColumn.containsKey(columnId);
    }

    public Set<String> numericColumnIds() {
        return statsByColumn.keySet();
    }

    private static Map<String, Map<Statistic, Double>> computeStats(String inputCsvPath, List<String> columnIds)
            throws IOException {
        List<List<Double>> valuesByColumn = new ArrayList<>();
        List<Boolean> numericColumns = new ArrayList<>();
        int numColumns = -1;

        try (BufferedReader reader = openCsv(inputCsvPath)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",", -1);
                if (numColumns < 0) {
                    numColumns = parts.length;
                    for (int i = 0; i < numColumns; i++) {
                        valuesByColumn.add(new ArrayList<>());
                        numericColumns.add(true);
                    }
                } else if (parts.length != numColumns) {
                    throw new IllegalArgumentException(
                            "Expected " + numColumns + " columns at line " + lineNumber + ", found " + parts.length);
                }

                for (int i = 0; i < parts.length; i++) {
                    String rawValue = parts[i].trim();
                    if (rawValue.isEmpty()) {
                        continue;
                    }
                    try {
                        valuesByColumn.get(i).add(Double.parseDouble(rawValue));
                    } catch (NumberFormatException e) {
                        numericColumns.set(i, false);
                    }
                }
            }
        }

        if (numColumns < 0) {
            throw new IllegalArgumentException("CSV is empty: " + inputCsvPath);
        }

        List<String> ids = columnIds;
        if (ids == null || ids.size() != numColumns) {
            ids = defaultColumnIds(numColumns);
        }

        Map<String, Map<Statistic, Double>> result = new LinkedHashMap<>();
        for (int i = 0; i < numColumns; i++) {
            List<Double> values = valuesByColumn.get(i);
            if (numericColumns.get(i) && !values.isEmpty()) {
                values.sort(Double::compareTo);
                result.put(ids.get(i), computeColumnStats(values));
            }
        }
        return result;
    }

    private static Map<Statistic, Double> computeColumnStats(List<Double> sortedValues) {
        Map<Statistic, Double> stats = new HashMap<>();
        stats.put(Statistic.FIRST_QUARTILE, percentile(sortedValues, 0.25));
        stats.put(Statistic.THIRD_QUARTILE, percentile(sortedValues, 0.75));
        stats.put(Statistic.MEAN, mean(sortedValues));
        stats.put(Statistic.MEDIAN, percentile(sortedValues, 0.50));
        stats.put(Statistic.PERCENTILE_01, percentile(sortedValues, 0.01));
        stats.put(Statistic.PERCENTILE_05, percentile(sortedValues, 0.05));
        stats.put(Statistic.PERCENTILE_95, percentile(sortedValues, 0.95));
        stats.put(Statistic.PERCENTILE_99, percentile(sortedValues, 0.99));
        return stats;
    }

    private static double percentile(List<Double> sortedValues, double p) {
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double position = p * (sortedValues.size() - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) {
            return sortedValues.get(lowerIndex);
        }
        double weight = position - lowerIndex;
        return sortedValues.get(lowerIndex) * (1.0 - weight) + sortedValues.get(upperIndex) * weight;
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static List<String> defaultColumnIds(int numColumns) {
        List<String> ids = new ArrayList<>(numColumns);
        for (int i = 0; i < numColumns; i++) {
            ids.add("c" + (i + 1));
        }
        return ids;
    }

    private static BufferedReader openCsv(String inputCsvPath) throws IOException {
        Path path = Path.of(inputCsvPath);
        if (Files.exists(path)) {
            return Files.newBufferedReader(path, StandardCharsets.UTF_8);
        }

        InputStream is = CsvColumnStatistics.class.getClassLoader().getResourceAsStream(inputCsvPath);
        if (is == null) {
            throw new IOException("Input CSV not found as file or classpath resource: " + inputCsvPath);
        }
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }
}
