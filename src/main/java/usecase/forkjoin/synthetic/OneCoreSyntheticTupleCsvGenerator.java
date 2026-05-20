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
    private static final double FIELD_1_MAX = 10000.0;
    private static final double FIELD_2_MIN = 20000.0;
    private static final double FIELD_2_MAX = 30000.0;

    private static final double OUTER_FIELD_1_MIN = -150000.0;
    private static final double OUTER_FIELD_1_MAX = 240000.0;
    private static final double OUTER_FIELD_2_MIN = -130000.0;
    private static final double OUTER_FIELD_2_MAX = 180000.0;

    private static final double CORE_FIELD_1_BAND_MIN = 4950.0;
    private static final double CORE_FIELD_1_BAND_MAX = 5050.0;
    private static final double CORE_FIELD_2_BAND_MIN = 24950.0;
    private static final double CORE_FIELD_2_BAND_MAX = 25050.0;

    private static final double CORE_RECTANGLE_PROBABILITY = 0.005;
    private static final double ONE_DIMENSION_BAND_PROBABILITY = 0.95;

    private static final long INITIAL_TIMESTAMP = 0L;
    private static final long MIN_TIMESTAMP_ADVANCE = 1000L;
    private static final long MAX_TIMESTAMP_ADVANCE = 5000L;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: OneCoreSyntheticTupleCsvGenerator <output-csv-path>");
        }

        validateProbability(CORE_RECTANGLE_PROBABILITY, "CORE_RECTANGLE_PROBABILITY");
        validateProbability(ONE_DIMENSION_BAND_PROBABILITY, "ONE_DIMENSION_BAND_PROBABILITY");
        if (CORE_RECTANGLE_PROBABILITY + ONE_DIMENSION_BAND_PROBABILITY > 1.0) {
            throw new IllegalArgumentException(
                    "CORE_RECTANGLE_PROBABILITY + ONE_DIMENSION_BAND_PROBABILITY cannot be greater than 1");
        }
        validateRange(FIELD_1_MIN, FIELD_1_MAX, CORE_FIELD_1_BAND_MIN, CORE_FIELD_1_BAND_MAX, "core field 1");
        validateRange(FIELD_2_MIN, FIELD_2_MAX, CORE_FIELD_2_BAND_MIN, CORE_FIELD_2_BAND_MAX, "core field 2");
        validateContainingRange(OUTER_FIELD_1_MIN, OUTER_FIELD_1_MAX, FIELD_1_MIN, FIELD_1_MAX, "field 1");
        validateContainingRange(OUTER_FIELD_2_MIN, OUTER_FIELD_2_MAX, FIELD_2_MIN, FIELD_2_MAX, "field 2");
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
                double[] fields = sampleFields(random);
                writer.write(String.format(Locale.US, "%d,%.6f,%.6f%n", timestamp, fields[0], fields[1]));
                timestamp += random.nextLong(MIN_TIMESTAMP_ADVANCE, MAX_TIMESTAMP_ADVANCE + 1L);
            }
        }
    }

    private static double[] sampleFields(ThreadLocalRandom random) {
        double r = random.nextDouble();
        if (r < CORE_RECTANGLE_PROBABILITY) {
            return new double[] {
                    random.nextDouble(CORE_FIELD_1_BAND_MIN, CORE_FIELD_1_BAND_MAX),
                    random.nextDouble(CORE_FIELD_2_BAND_MIN, CORE_FIELD_2_BAND_MAX)
            };
        }

        if (r < CORE_RECTANGLE_PROBABILITY + ONE_DIMENSION_BAND_PROBABILITY) {
            if (random.nextBoolean()) {
                return new double[] {
                        random.nextDouble(CORE_FIELD_1_BAND_MIN, CORE_FIELD_1_BAND_MAX),
                        random.nextDouble(FIELD_2_MIN, FIELD_2_MAX)
                };
            }
            return new double[] {
                    random.nextDouble(FIELD_1_MIN, FIELD_1_MAX),
                    random.nextDouble(CORE_FIELD_2_BAND_MIN, CORE_FIELD_2_BAND_MAX)
            };
        }

        return new double[] {
                randomOutsideRangeWithin(
                        OUTER_FIELD_1_MIN,
                        OUTER_FIELD_1_MAX,
                        FIELD_1_MIN,
                        FIELD_1_MAX,
                        random),
                randomOutsideRangeWithin(
                        OUTER_FIELD_2_MIN,
                        OUTER_FIELD_2_MAX,
                        FIELD_2_MIN,
                        FIELD_2_MAX,
                        random)
        };
    }

    private static double randomOutsideRangeWithin(
            double outerMin,
            double outerMax,
            double innerMin,
            double innerMax,
            ThreadLocalRandom random
    ) {
        double lowerWidth = innerMin - outerMin;
        double upperWidth = outerMax - innerMax;
        double totalWidth = lowerWidth + upperWidth;
        if (totalWidth <= 0.0) {
            throw new IllegalArgumentException("Cannot generate an out-of-range value when the inner range covers the outer range");
        }

        double r = random.nextDouble(totalWidth);
        if (r < lowerWidth) {
            return random.nextDouble(outerMin, innerMin);
        }
        return random.nextDouble(innerMax, outerMax);
    }

    private static void validateProbability(double probability, String name) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    private static void validateRange(double min, double max, double bandMin, double bandMax, String name) {
        if (min >= max) {
            throw new IllegalArgumentException("Minimum must be less than maximum for " + name);
        }
        if (bandMin >= bandMax || bandMin < min || bandMax > max) {
            throw new IllegalArgumentException("Band must be ordered and contained in range for " + name);
        }
    }

    private static void validateContainingRange(double outerMin, double outerMax, double innerMin, double innerMax, String name) {
        if (outerMin >= outerMax) {
            throw new IllegalArgumentException("Outer minimum must be less than outer maximum for " + name);
        }
        if (outerMin > innerMin || outerMax < innerMax || (outerMin == innerMin && outerMax == innerMax)) {
            throw new IllegalArgumentException("Outer range must strictly contain inner range for " + name);
        }
    }

}
