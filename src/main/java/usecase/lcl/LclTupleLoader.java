package usecase.lcl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import usecase.common.Tuple;

public final class LclTupleLoader {

    private static final String RESOURCE_PATH = "datasets/lcl_10days_100keys.csv";
    private static final List<String> COLUMNS = List.of(
            "KWH_day",
            "KWH_max_30min",
            "KWH_median_30min");

    private LclTupleLoader() {
    }

    public static List<Tuple> load() {
        return load(RESOURCE_PATH);
    }

    public static List<Tuple> load(String resourcePath) {
        InputStream inputStream = LclTupleLoader.class.getClassLoader()
                .getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IllegalArgumentException("Resource not found: " + resourcePath);
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .get();

        List<Tuple> tuples = new ArrayList<>();
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                CSVParser parser = CSVParser.parse(reader, format)) {
            long linkageId = 0L;
            for (CSVRecord record : parser) {
                long timestamp = Long.parseLong(record.get("timestamp"));
                String key = record.get("LCLid");
                double[] fields = new double[COLUMNS.size()];
                boolean finite = true;
                for (int i = 0; i < COLUMNS.size(); i++) {
                    fields[i] = parseFiniteDouble(record.get(COLUMNS.get(i)));
                    finite &= Double.isFinite(fields[i]);
                }
                if (!finite) {
                    continue;
                }
                tuples.add(new Tuple(timestamp, key, fields).withLinkageId(linkageId));
                linkageId++;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read resource: " + resourcePath, e);
        }
        tuples.sort(Comparator
                .comparingLong(Tuple::getTimestamp)
                .thenComparing(Tuple::getKey));
        return tuples;
    }

    private static double parseFiniteDouble(String rawValue) {
        if (rawValue == null || rawValue.isBlank() || rawValue.equalsIgnoreCase("NaN")) {
            return Double.NaN;
        }
        return Double.parseDouble(rawValue.trim());
    }
}
