package usecase.lcl.flow;

import java.util.Arrays;
import java.util.List;
import usecase.common.TupleMatchingScore;
import usecase.common.TupleMatchingScore.DistanceMode;
import usecase.common.Tuple;
import usecase.common.flow.StreamFlowSnapshotSimilarity;

public final class LclFlowSmokeTest {

    private LclFlowSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        System.err.println("Loading LCL flow resource");
        List<Tuple> input = LclFlowTupleReader.readDefaultResource();
        System.err.println("Running all-fields LCL flow query on " + input.size() + " tuples");
        long start = System.nanoTime();
        LclFlowAllFieldsMainQuery.QueryResult result = LclFlowAllFieldsMainQuery.process(input, "smoke");
        long elapsedMillis = Math.round((System.nanoTime() - start) / 1_000_000.0);

        long stdOutputs = result.outputTuples().stream().filter(tuple -> tuple.getKey().equals("tariff_0")).count();
        long touOutputs = result.outputTuples().stream().filter(tuple -> tuple.getKey().equals("tariff_1")).count();
        if (stdOutputs == 0L || touOutputs == 0L) {
            throw new IllegalStateException("Expected non-empty Std and ToU outputs");
        }
        if (result.outputTuples().stream().anyMatch(tuple -> tuple.getNumFields() != 13)) {
            throw new IllegalStateException("Expected all-fields query outputs to have 13 fields");
        }
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
        System.out.printf("Branch outputs: std=%d tou=%d%n", stdOutputs, touOutputs);
        System.out.printf(
                "Sanity scores: identityFidelity=%.3f emptyFidelity=%.3f identitySemantics=%.3f%n",
                identityFidelity,
                emptyFidelity,
                identitySemantics);
        System.out.println("Tuple row sums:");
        printRowSums(result.flow().streamNames(), result.flow().tupleCounts());
        System.out.println("Key row sums:");
        printRowSums(result.flow().streamNames(), result.flow().keyCounts());
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
