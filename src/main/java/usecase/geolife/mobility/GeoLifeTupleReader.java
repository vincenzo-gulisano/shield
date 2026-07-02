package usecase.geolife.mobility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import usecase.common.Tuple;

public final class GeoLifeTupleReader {

    public static final String DEFAULT_RESOURCE = "datasets/geolife_60mins.csv";
    private static final String EXPECTED_HEADER = "timestamp,user,avg_X,avg_Y";

    private GeoLifeTupleReader() {
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
        InputStream inputStream = GeoLifeTupleReader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        List<Tuple> tuples = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!EXPECTED_HEADER.equals(header)) {
                throw new IOException("Unexpected GeoLife CSV header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length != 4) {
                    throw new IOException("Malformed GeoLife CSV row: " + line);
                }
                long timestamp = Long.parseLong(parts[0]);
                String user = parts[1];
                double x = Double.parseDouble(parts[2]);
                double y = Double.parseDouble(parts[3]);
                if (Double.isFinite(x) && Double.isFinite(y)) {
                    tuples.add(new Tuple(timestamp, user, x, y));
                }
            }
        }
        tuples.sort(Comparator.comparingLong(Tuple::getTimestamp).thenComparing(Tuple::getKey));
        List<Tuple> withLinkageIds = new ArrayList<>(tuples.size());
        for (int i = 0; i < tuples.size(); i++) {
            withLinkageIds.add(tuples.get(i).withLinkageId(i));
        }
        return List.copyOf(withLinkageIds);
    }
}
