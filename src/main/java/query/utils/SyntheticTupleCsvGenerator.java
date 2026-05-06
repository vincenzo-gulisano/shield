package query.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class SyntheticTupleCsvGenerator {

    private static final int NUMBER_OF_TUPLES = 1000;

    private static final double FIELD_1_MIN = 0.0;
    private static final double FIELD_1_MAX = 10000.0;
    private static final double FIELD_1_BAND_MIN = 4950.0;
    private static final double FIELD_1_BAND_MAX = 5050.0;
    private static final double FIELD_1_BAND_PROBABILITY = 0.9;

    private static final double FIELD_2_MIN = 20000.0;
    private static final double FIELD_2_MAX = 30000.0;
    private static final double FIELD_2_BAND_MIN = 24990.0;
    private static final double FIELD_2_BAND_MAX = 25010.0;
    private static final double FIELD_2_BAND_PROBABILITY = 0.85;

    private static final long INITIAL_TIMESTAMP = 0;
    private static final long MIN_TIMESTAMP_ADVANCE = 1000L;
    private static final long MAX_TIMESTAMP_ADVANCE = 5000L;

    @SuppressWarnings("unused")
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: SyntheticTupleCsvGenerator <output-csv-path>");
        }

        validateProbability(FIELD_1_BAND_PROBABILITY, "FIELD_1_BAND_PROBABILITY");
        validateProbability(FIELD_2_BAND_PROBABILITY, "FIELD_2_BAND_PROBABILITY");
        validateRange(FIELD_1_MIN, FIELD_1_MAX, FIELD_1_BAND_MIN, FIELD_1_BAND_MAX, "field 1");
        validateRange(FIELD_2_MIN, FIELD_2_MAX, FIELD_2_BAND_MIN, FIELD_2_BAND_MAX, "field 2");
        if (MIN_TIMESTAMP_ADVANCE > MAX_TIMESTAMP_ADVANCE) {
            throw new IllegalArgumentException("MIN_TIMESTAMP_ADVANCE cannot be greater than MAX_TIMESTAMP_ADVANCE");
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
                double f1 = randomValue(
                        FIELD_1_MIN,
                        FIELD_1_MAX,
                        FIELD_1_BAND_MIN,
                        FIELD_1_BAND_MAX,
                        FIELD_1_BAND_PROBABILITY,
                        random
                );
                double f2 = randomValue(
                        FIELD_2_MIN,
                        FIELD_2_MAX,
                        FIELD_2_BAND_MIN,
                        FIELD_2_BAND_MAX,
                        FIELD_2_BAND_PROBABILITY,
                        random
                );
                writer.write(String.format(Locale.US, "%d,%.6f,%.6f%n", timestamp, f1, f2));
                timestamp += random.nextLong(MIN_TIMESTAMP_ADVANCE, MAX_TIMESTAMP_ADVANCE + 1L);
            }
        }
    }

    private static void validateProbability(double probability, String name) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    private static void validateRange(double min, double max, double bandMin, double bandMax, String fieldName) {
        if (min > max) {
            throw new IllegalArgumentException("Minimum cannot be greater than maximum for " + fieldName);
        }
        if (bandMin > bandMax || bandMin < min || bandMax > max) {
            throw new IllegalArgumentException("Band must be ordered and contained in range for " + fieldName);
        }
    }

    private static double randomValue(
            double min,
            double max,
            double bandMin,
            double bandMax,
            double bandProbability,
            ThreadLocalRandom random
    ) {
        if (random.nextDouble() < bandProbability) {
            return random.nextDouble(bandMin, bandMax);
        }

        double lowerWidth = bandMin - min;
        double upperWidth = max - bandMax;
        double outsideWidth = lowerWidth + upperWidth;
        if (outsideWidth <= 0.0) {
            throw new IllegalArgumentException("Cannot generate an out-of-band value when the band covers the range");
        }

        if (random.nextDouble(outsideWidth) < lowerWidth) {
            return random.nextDouble(min, bandMin);
        }
        return random.nextDouble(bandMax, max);
    }
}
