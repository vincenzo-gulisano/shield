package usecase.forkjoin.synthetic;

import metrics.privacy.DoubleFieldLookup;
import metrics.privacy.KAnonymityPrivacyCardinality;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class OneCoreSyntheticTupleCsvGenerator {

    private static final int NUMBER_OF_TUPLES = 1500;
    public static final int INNER_1_TUPLES = 1420;
    public static final int INNER_2_TUPLES = 60;
    private static final int NOISE_TUPLES = NUMBER_OF_TUPLES - INNER_1_TUPLES - INNER_2_TUPLES;

    private static final double FIELD_1_MIN = 1_000.0;
    private static final double FIELD_1_MAX = 999_000.0;
    private static final double FIELD_2_MIN = 1_000.0;
    private static final double FIELD_2_MAX = 999_000.0;

    public static final double INNER_1_FIELD_1_MIN = 1000.0;
    public static final double INNER_1_FIELD_1_MAX = 2000.0;
    public static final double INNER_1_FIELD_2_MIN = 1000.0;
    public static final double INNER_1_FIELD_2_MAX = 2000.0;

    public static final double INNER_2_FIELD_1_MIN = 998000.0;
    public static final double INNER_2_FIELD_1_MAX = 999000.0;
    public static final double INNER_2_FIELD_2_MIN = 998000.0;
    public static final double INNER_2_FIELD_2_MAX = 999000.0;

    private static final double NOISE_FIELD_1_MIN = 10_000.0;
    private static final double NOISE_FIELD_1_MAX = 11_000.0;
    private static final double NOISE_FIELD_2_MIN = 989_000.0;
    private static final double NOISE_FIELD_2_MAX = 990_000.0;

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
        List<GeneratedTuple> generatedTuples = sampleAllTuples(random);
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            for (GeneratedTuple tuple : generatedTuples) {
                writer.write(String.format(Locale.US, "%d,%.6f,%.6f%n", timestamp, tuple.f1(), tuple.f2()));
                timestamp += random.nextLong(MIN_TIMESTAMP_ADVANCE, MAX_TIMESTAMP_ADVANCE + 1L);
            }
        }

        printKAnonymityStats(generatedTuples);
    }

    private static List<GeneratedTuple> sampleAllTuples(ThreadLocalRandom random) {
        if (NOISE_TUPLES < 0) {
            throw new IllegalStateException("Configured tuple counts exceed NUMBER_OF_TUPLES");
        }

        List<GeneratedTuple> fields = new ArrayList<>(NUMBER_OF_TUPLES);
        for (int i = 0; i < INNER_1_TUPLES; i++) {
            fields.add(sampleInner1Tuple(random));
        }
        for (int i = 0; i < INNER_2_TUPLES; i++) {
            fields.add(sampleInner2Tuple(random));
        }
        for (int i = 0; i < NOISE_TUPLES; i++) {
            fields.add(sampleNoiseTuple(random));
        }

        for (int i = fields.size() - 1; i > 0; i--) {
            Collections.swap(fields, i, random.nextInt(i + 1));
        }

        return fields;
    }

    private static GeneratedTuple sampleInner1Tuple(ThreadLocalRandom random) {
        return new GeneratedTuple(
                random.nextDouble(INNER_1_FIELD_1_MIN, INNER_1_FIELD_1_MAX),
                random.nextDouble(INNER_1_FIELD_2_MIN, INNER_1_FIELD_2_MAX),
                TupleGroup.CLUSTER_1);
    }

    private static GeneratedTuple sampleInner2Tuple(ThreadLocalRandom random) {
        return new GeneratedTuple(
                random.nextDouble(INNER_2_FIELD_1_MIN, INNER_2_FIELD_1_MAX),
                random.nextDouble(INNER_2_FIELD_2_MIN, INNER_2_FIELD_2_MAX),
                TupleGroup.CLUSTER_2);
    }

    private static GeneratedTuple sampleNoiseTuple(ThreadLocalRandom random) {
        return new GeneratedTuple(
                random.nextDouble(NOISE_FIELD_1_MIN, NOISE_FIELD_1_MAX),
                random.nextDouble(NOISE_FIELD_2_MIN, NOISE_FIELD_2_MAX),
                TupleGroup.NOISE);
    }

    private static void printKAnonymityStats(List<GeneratedTuple> allTuples) {
        KAnonymityPrivacyCardinality calculator =
                new KAnonymityPrivacyCardinality(allTuples, 50, List.of("f1", "f2"));

        List<GeneratedTuple> clusterTuples = allTuples.stream()
                .filter(GeneratedTuple::isCluster)
                .collect(Collectors.toList());
        List<GeneratedTuple> cluster1Tuples = allTuples.stream()
                .filter(t -> t.group() == TupleGroup.CLUSTER_1)
                .collect(Collectors.toList());
        List<GeneratedTuple> cluster2Tuples = allTuples.stream()
                .filter(t -> t.group() == TupleGroup.CLUSTER_2)
                .collect(Collectors.toList());

        System.out.println();
        printStats("all points", allTuples, allTuples, calculator);
        printStats("cluster points", allTuples, clusterTuples, calculator);
        printStats("cluster 1", allTuples, cluster1Tuples, calculator);
        printStats("cluster 2", allTuples, cluster2Tuples, calculator);
    }

    private static void printStats(String label,
                                   List<GeneratedTuple> originalTuples,
                                   List<GeneratedTuple> measuredTuples,
                                   KAnonymityPrivacyCardinality calculator) {
        KAnonymityPrivacyCardinality.StdDevStats stats =
                calculator.applyWithStdDevStats(originalTuples, measuredTuples);
        double score = 1.0 / (1.0 + stats.q99);
        System.out.printf(Locale.US,
                "%s: tuples=%d, q99StdDev=%.6f, q99Score=%.6f, meanStdDev=%.6f, maxStdDev=%.6f%n",
                label, measuredTuples.size(), stats.q99, score, stats.mean, stats.max);
    }

    private enum TupleGroup {
        CLUSTER_1,
        CLUSTER_2,
        NOISE
    }

    private record GeneratedTuple(double f1, double f2, TupleGroup group) implements DoubleFieldLookup {

        private boolean isCluster() {
            return group != TupleGroup.NOISE;
        }

        @Override
        public double lookup(String fieldName) {
            return switch (fieldName) {
                case "f1" -> f1;
                case "f2" -> f2;
                default -> Double.NaN;
            };
        }

        @Override
        public void set(String fieldName, double value) {
            throw new UnsupportedOperationException("GeneratedTuple is immutable");
        }
    }

}
