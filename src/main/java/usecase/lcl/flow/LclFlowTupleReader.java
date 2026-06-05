package usecase.lcl.flow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import usecase.common.Tuple;

public final class LclFlowTupleReader {

    public static final String DEFAULT_RESOURCE = "datasets/lcl_flow_daily_10days_100keys.csv";
    private static final String EXPECTED_HEADER = String.join(",",
            "timestamp",
            "key",
            "f1_tariff",
            "f2_kwh_day",
            "f3_kwh_max_30min",
            "f4_kwh_median_30min",
            "f5_kwh_p90_30min",
            "f6_kwh_stdev_30min",
            "f7_evening_share",
            "f8_night_share",
            "f9_load_factor",
            "f10_peak_half_hour_slot",
            "f11_zero_half_hour_count");

    private LclFlowTupleReader() {
    }

    public static List<Tuple> readDefaultResource() throws IOException {
        return readResource(DEFAULT_RESOURCE);
    }

    public static List<Tuple> loadUnchecked(String resourcePath) {
        try {
            return readResource(resourcePath);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read resource: " + resourcePath, e);
        }
    }

    public static List<Tuple> readResource(String resourcePath) throws IOException {
        InputStream inputStream = LclFlowTupleReader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        List<Tuple> tuples = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!EXPECTED_HEADER.equals(header)) {
                throw new IOException("Unexpected LCL flow CSV header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length != 13) {
                    throw new IOException("Malformed LCL flow CSV row: " + line);
                }
                long timestamp = Long.parseLong(parts[0]);
                String key = parts[1];
                double[] fields = new double[11];
                boolean finite = true;
                for (int i = 0; i < fields.length; i++) {
                    fields[i] = Double.parseDouble(parts[i + 2]);
                    finite &= Double.isFinite(fields[i]);
                }
                if (finite) {
                    tuples.add(new Tuple(timestamp, key, fields).withLinkageId(tuples.size()));
                }
            }
        }
        tuples.sort(Comparator.comparingLong(Tuple::getTimestamp).thenComparing(Tuple::getKey));
        return List.copyOf(tuples);
    }
}
