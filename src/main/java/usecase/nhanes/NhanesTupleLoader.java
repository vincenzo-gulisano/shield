package usecase.nhanes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import usecase.common.Tuple;

public final class NhanesTupleLoader {

    private static final String RESOURCE_PATH = "datasets/nhanes.csv";
    private static final long TIMESTAMP = 0L;
    private static final String KEY = "";
    private static final List<String> COLUMNS = List.of(
            "gender",
            "age_years",
            "race_ethnicity_expanded",
            "family_income_to_poverty_ratio",
            "weight_kg",
            "hip_circumference_cm");

    private NhanesTupleLoader() {
    }

    public static List<Tuple> load() {
        return load(RESOURCE_PATH);
    }

    public static List<Tuple> load(String resourcePath) {
        InputStream inputStream = NhanesTupleLoader.class.getClassLoader()
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
            for (CSVRecord record : parser) {
                double[] fields = new double[COLUMNS.size()];
                for (int i = 0; i < COLUMNS.size(); i++) {
                    fields[i] = Double.parseDouble(record.get(COLUMNS.get(i)));
                }
                tuples.add(new Tuple(TIMESTAMP, KEY, fields));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read resource: " + resourcePath, e);
        }
        return tuples;
    }
}
