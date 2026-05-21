package usecase.forkjoin.synthetic;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class OneCoreSyntheticTupleCsvGenerator {

    private static final int NUMBER_OF_TUPLES = 1000;

    private static final double FIELD_1_MIN = 0.0;
    private static final double FIELD_1_MAX = 100_000_000.0;
    private static final double FIELD_2_MIN = 0.0;
    private static final double FIELD_2_MAX = 100_000_000.0;

    public static final double INNER_1_FIELD_1_MIN = 1000.0;
    public static final double INNER_1_FIELD_1_MAX = 1050.0;
    public static final double INNER_1_FIELD_2_MIN = 1000.0;
    public static final double INNER_1_FIELD_2_MAX = 41000.0;

    public static final double INNER_2_FIELD_1_MIN = 992500.0;
    public static final double INNER_2_FIELD_1_MAX = 997500.0;
    public static final double INNER_2_FIELD_2_MIN = 990000.0;
    public static final double INNER_2_FIELD_2_MAX = 990200.0;

    private static final double INNER_1_PROBABILITY = 0.485;
    private static final double INNER_2_PROBABILITY = 0.485;

    private static final long INITIAL_TIMESTAMP = 0L;
    private static final long MIN_TIMESTAMP_ADVANCE = 1000L;
    private static final long MAX_TIMESTAMP_ADVANCE = 5000L;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: OneCoreSyntheticTupleCsvGenerator <output-csv-path>");
        }

        Path outputPath = Path.of(args[0]);
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        long timestamp = INITIAL_TIMESTAMP;
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            for (int i = 0; i < NUMBER_OF_TUPLES; i++) {
                double[] fields = sampleFields(random);
                writer.write(String.format(Locale.US, "%d,%.6f,%.6f%n", timestamp, fields[0], fields[1]));
                timestamp += random.nextLong(MIN_TIMESTAMP_ADVANCE, MAX_TIMESTAMP_ADVANCE + 1L);
            }
        }
    }

    private static double[] sampleFields(ThreadLocalRandom random) {
        double r = random.nextDouble();
        if (r < INNER_1_PROBABILITY) {
            return new double[] {
                    random.nextDouble(INNER_1_FIELD_1_MIN, INNER_1_FIELD_1_MAX),
                    random.nextDouble(INNER_1_FIELD_2_MIN, INNER_1_FIELD_2_MAX)
            };
        }

        if (r < INNER_1_PROBABILITY + INNER_2_PROBABILITY) {
            return new double[] {
                    random.nextDouble(INNER_2_FIELD_1_MIN, INNER_2_FIELD_1_MAX),
                    random.nextDouble(INNER_2_FIELD_2_MIN, INNER_2_FIELD_2_MAX)
            };

        }

        return new double[] {
                random.nextDouble(FIELD_1_MIN, FIELD_1_MAX),
                random.nextDouble(FIELD_2_MIN, FIELD_2_MAX)
        };
    }

}
