package usecase.common.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import usecase.common.Tuple;

/**
 * Computes per-feature statistics for {@link Tuple} objects.
 *
 * <p>The same statistics are used by the aggregate-stat output and by the outlier detector. Keeping
 * them here avoids duplicating percentile, median, IQR, mean, and standard-deviation logic.
 */
public final class TupleFeatureStats {

    public static final int STATS_PER_FIELD = 9;

    private final List<FeatureStats> features;

    public record FeatureStats(
            int count,
            double mean,
            double std,
            double median,
            double p05,
            double p25,
            double p75,
            double p95,
            double p99) {

        public double iqr() {
            return p75 - p25;
        }
    }

    public TupleFeatureStats(List<FeatureStats> features) {
        this.features = List.copyOf(features);
    }

    public List<FeatureStats> features() {
        return features;
    }

    /**
     * Compute feature statistics from a complete list of tuples.
     */
    public static TupleFeatureStats compute(List<? extends Tuple> tuples) {
        Accumulator accumulator = new Accumulator();
        for (Tuple tuple : tuples) {
            accumulator.add(tuple);
        }
        return accumulator.toStats();
    }

    /**
     * Flatten stats into the output tuple field order:
     * count, mean, std, median, p05, p25, p75, p95, p99 for f1, then the same for f2, etc.
     */
    public double[] toTupleFields() {
        double[] fields = new double[features.size() * STATS_PER_FIELD];
        int index = 0;
        for (FeatureStats feature : features) {
            fields[index++] = feature.count();
            fields[index++] = feature.mean();
            fields[index++] = feature.std();
            fields[index++] = feature.median();
            fields[index++] = feature.p05();
            fields[index++] = feature.p25();
            fields[index++] = feature.p75();
            fields[index++] = feature.p95();
            fields[index++] = feature.p99();
        }
        return fields;
    }

    /**
     * Mutable collector for streaming/windowed use.
     */
    public static final class Accumulator {

        private final List<List<Double>> sortedValuesByField = new ArrayList<>();
        private int numFields = -1;

        public void add(Tuple tuple) {
            if (tuple == null) {
                return;
            }
            initializeIfNeeded(tuple.getNumFields());
            if (tuple.getNumFields() != numFields) {
                throw new IllegalArgumentException(
                        "Expected " + numFields + " fields, found " + tuple.getNumFields());
            }
            for (int i = 0; i < numFields; i++) {
                double value = tuple.getField("f" + (i + 1));
                if (!Double.isNaN(value)) {
                    insertSorted(sortedValuesByField.get(i), value);
                }
            }
        }

        public TupleFeatureStats toStats() {
            List<FeatureStats> stats = new ArrayList<>(sortedValuesByField.size());
            for (List<Double> values : sortedValuesByField) {
                stats.add(computeFeatureStats(values));
            }
            return new TupleFeatureStats(stats);
        }

        private void initializeIfNeeded(int newNumFields) {
            if (numFields >= 0) {
                return;
            }
            numFields = newNumFields;
            for (int i = 0; i < numFields; i++) {
                sortedValuesByField.add(new ArrayList<>());
            }
        }
    }

    private static FeatureStats computeFeatureStats(List<Double> sortedValues) {
        return new FeatureStats(
                sortedValues.size(),
                mean(sortedValues),
                std(sortedValues),
                percentile(sortedValues, 0.50),
                percentile(sortedValues, 0.05),
                percentile(sortedValues, 0.25),
                percentile(sortedValues, 0.75),
                percentile(sortedValues, 0.95),
                percentile(sortedValues, 0.99));
    }

    private static void insertSorted(List<Double> values, double value) {
        int index = Collections.binarySearch(values, value);
        if (index < 0) {
            index = -index - 1;
        }
        values.add(index, value);
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double std(List<Double> values) {
        if (values.size() < 2) {
            return 0.0;
        }
        double mean = mean(values);
        double sumSquaredDiffs = 0.0;
        for (double value : values) {
            double diff = value - mean;
            sumSquaredDiffs += diff * diff;
        }
        return Math.sqrt(sumSquaredDiffs / (values.size() - 1));
    }

    static double percentile(List<Double> sortedValues, double p) {
        if (sortedValues.isEmpty()) {
            return Double.NaN;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double position = p * (sortedValues.size() - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) {
            return sortedValues.get(lowerIndex);
        }
        double weight = position - lowerIndex;
        return sortedValues.get(lowerIndex) * (1.0 - weight)
                + sortedValues.get(upperIndex) * weight;
    }
}
