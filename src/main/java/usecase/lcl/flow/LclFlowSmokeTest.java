package usecase.lcl.flow;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import usecase.common.TupleMatchingScore;
import usecase.common.TupleMatchingScore.DistanceMode;
import usecase.common.Tuple;

public final class LclFlowSmokeTest {

    private static final Path DEFAULT_OUTPUT_DIR = Path.of("outputs", "lcl-flow-smoke");

    private LclFlowSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path outputDir = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUTPUT_DIR;

        System.err.println("Loading LCL flow resource");
        List<Tuple> input = LclFlowTupleReader.readDefaultResource();
        System.err.println("Running LCL flow query on " + input.size() + " tuples");
        long start = System.nanoTime();
        LclFlowMainQuery.QueryResult result = LclFlowMainQuery.process(input, "smoke");
        System.err.println("Writing LCL flow images");
        long elapsedMillis = Math.round((System.nanoTime() - start) / 1_000_000.0);

        FlowImageWriter.writeSnapshotImages(result.flow(), outputDir);
        StreamFlowSnapshotSimilarity flowSimilarity = new StreamFlowSnapshotSimilarity(result.flow());
        double identityFidelity = flowSimilarity.apply(result.flow());
        double emptyFidelity = flowSimilarity.apply(StreamFlowSnapshotSimilarity.emptyLike(result.flow()));
        double identitySemantics = TupleMatchingScore.f1(
                TupleMatchingScore.groupByTimestampAndKey(result.outputTuples()),
                TupleMatchingScore.groupByTimestampAndKey(result.outputTuples()),
                0.05,
                DistanceMode.RELATIVE);

        System.out.printf(
                "LCL flow smoke: input=%d output=%d streams=%d bins=%d elapsedMs=%d%n",
                input.size(),
                result.outputTuples().size(),
                result.flow().streamNames().size(),
                result.flow().timeBins(),
                elapsedMillis);
        System.out.printf(
                "Sanity scores: identityFidelity=%.3f emptyFidelity=%.3f identitySemantics=%.3f%n",
                identityFidelity,
                emptyFidelity,
                identitySemantics);
        System.out.println("Tuple row sums:");
        printRowSums(result.flow().streamNames(), result.flow().tupleCounts());
        System.out.println("Key row sums:");
        printRowSums(result.flow().streamNames(), result.flow().keyCounts());
        System.out.println("Images:");
        System.out.println(outputDir.resolve("tuple-flow.png").toAbsolutePath());
        System.out.println(outputDir.resolve("key-flow.png").toAbsolutePath());
        System.out.println("First output tuples:");
        result.outputTuples().stream().limit(10).forEach(tuple -> System.out.printf(
                "timestamp=%d key=%s fields=%s%n",
                tuple.getTimestamp(),
                tuple.getKey(),
                Arrays.toString(tuple.getFields())));
        System.exit(0);
    }

    private static void printRowSums(List<String> streamNames, long[][] matrix) {
        for (int row = 0; row < matrix.length; row++) {
            long sum = 0L;
            for (long value : matrix[row]) {
                sum += value;
            }
            System.out.printf("%2d %8d %s%n", row, sum, streamNames.get(row));
        }
    }
}
