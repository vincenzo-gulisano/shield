package metrics.privacy;

import io.github.ericmedvet.jgea.core.distance.Distance;
import metrics.privacy.utils.KDTree;
import metrics.privacy.utils.MetricUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * KAnonymityPrivacyCardinality implements a k-anonymity–based privacy metric based on two criteria:
 *
 * 1.  Anonymity Quality: For each tuple, it measures the dispersion (standard deviation) of the
 *     distances to the k nearest neighbors in the original dataset.
 * 2.  Cardinality Fidelity: It penalizes solutions that significantly alter the size (cardinality)
 *     of the dataset compared to the original.
 *
 * The metric also exposes descriptive statistics (mean, min, max, quantiles)
 * on the standard deviation values to support detailed analysis and plotting.
 */

public class KAnonymityPrivacyCardinality
        implements Distance<List<? extends DoubleFieldLookup>> {

    public static class StdDevStats {
        public double mean = 0.0;
        public double min = 0.0; // Best-case privacy (Minimum stddev)
        public double max = 0.0; // Worst-case privacy (Maximum stddev)
        public double q95 = 0.0;
        public double q99 = 0.0;
    }

    private final int k;
    private final Map<String, Double> inverseStds;
    private final KDTree originalTree;
    private final List<String> attributes;

    public KAnonymityPrivacyCardinality(
            List<? extends DoubleFieldLookup> originalStream, int k, List<String> attributes) {

        if (k < 2) {
            throw new IllegalArgumentException("k must be at least 2");
        }
        this.k = k;
        this.attributes = attributes;

        // Calculate mean for each attribute in the original stream
        Map<String, Double> means = this.attributes.stream()
                .collect(Collectors.toMap(
                        a -> a,
                        a -> originalStream.stream()
                                .mapToDouble(e -> e.lookup(a))
                                .filter(v -> !Double.isNaN(v))
                                .average()
                                .orElse(0.0)
                ));

        // Calculate Standard Deviation for each attribute in the original stream
        this.inverseStds = this.attributes.stream()
                .collect(Collectors.toMap(
                        a -> a,
                        a -> {
                            List<Double> values = originalStream.stream()
                                    .map(e -> e.lookup(a))
                                    .filter(v -> !Double.isNaN(v))
                                    .collect(Collectors.toList());
                            if (values.size() < 2) return 1.0;
                            double mean = means.get(a);
                            double ssq = values.stream()
                                    .mapToDouble(v -> (v - mean) * (v - mean))
                                    .sum();
                            double std = Math.sqrt(ssq / (values.size() - 1));
                            return (std > 1e-9) ? 1.0 / std : 1.0;
                        }
                ));

        // Build the tree once with the original stream
        List<double[]> originalVectors = originalStream.stream()
                .map(this::toVector)
                .filter(v -> !MetricUtils.isAllNaN(v))
                .collect(Collectors.toList());
        this.originalTree = new KDTree(originalVectors);
    }

    /**
     * Implementation of the Distance interface.
     * Return a single privacy score based on the mean standard deviation.
     */
    @Override
    public Double apply(List<? extends DoubleFieldLookup> original,
                        List<? extends DoubleFieldLookup> modified) {
        if (modified == null) {
            return 0.0;
        }
        // Compute raw statistics on standard deviations
        StdDevStats stats = applyWithStdDevStats(original, modified);
        double cardinalityFactor = calculateCardinalityFactor(original, modified);
        // Convert mean standard deviation (privacy risk) into a score to maximize
        double score = 1.0 / (1.0 + stats.mean);
        return score * cardinalityFactor;
    }

    /**
     * Calculate the privacy score based on the 99° percentile of the standard deviation
     */
    public Double applyWithQuantile99(List<? extends DoubleFieldLookup> original, List<? extends DoubleFieldLookup> modified) {
        if (modified == null) {
            return 0.0;
        }
        DoubleAccumulator stddevs = collectStdDevs(original, modified);
        double q99 = stddevs.quantile(0.99);
        double cardinalityFactor = calculateCardinalityFactor(original, modified);
        double score = 1.0 / (1.0 + q99);
        return score * cardinalityFactor;
    }

    /**
     * Calculate the privacy score based on the maximum of the standard deviation
     */
    public Double applyWithMax(List<? extends DoubleFieldLookup> original, List<? extends DoubleFieldLookup> modified) {
        if (modified == null) {
            return 0.0;
        }
        StdDevStats stats = applyWithStdDevStats(original, modified);
        double cardinalityFactor = calculateCardinalityFactor(original, modified);
        double score = 1.0 / (1.0 + stats.max);
        return score * cardinalityFactor;
    }

    /**
     * Computes statistics directly on the standard deviation of k-nearest-neighbor distances.
     */
    public StdDevStats applyWithStdDevStats(
            List<? extends DoubleFieldLookup> original,
            List<? extends DoubleFieldLookup> modified) {

        StdDevStats stats = new StdDevStats();

        if (original == null || modified == null ||
                original.isEmpty() || modified.isEmpty()) {
            return stats;
        }

        DoubleAccumulator stddevs = collectStdDevs(original, modified);
        if (stddevs.isEmpty()) return stats;

        // Sort values to compute quantiles
        stddevs.sort();
        int n = stddevs.size();

        // Compute aggregate statistics
        stats.mean = stddevs.sum() / n;

        stats.min = stddevs.get(0);
        stats.max = stddevs.get(n - 1);

        stats.q95 = stddevs.get((int) Math.floor(0.95 * (n - 1)));
        stats.q99 = stddevs.get((int) Math.floor(0.99 * (n - 1)));

        return stats;
    }

    private DoubleAccumulator collectStdDevs(
            List<? extends DoubleFieldLookup> original,
            List<? extends DoubleFieldLookup> modified) {

        DoubleAccumulator stddevs = new DoubleAccumulator(modified == null ? 0 : modified.size());

        if (original == null || modified == null ||
                original.isEmpty() || modified.isEmpty()) {
            return stddevs;
        }

        for (DoubleFieldLookup e : modified) {
            double[] v = toVector(e);
            if (MetricUtils.isAllNaN(v)) continue;

            double std = originalTree.findNearestDistanceStdDev(v, k);
            if (Double.isNaN(std)) continue;

            stddevs.add(std);
        }

        return stddevs;
    }

    private double calculateCardinalityFactor(List<? extends DoubleFieldLookup> original, List<? extends DoubleFieldLookup> modified) {
        if (original == null || modified == null) return 0.0;
        double nOrig = original.size();
        double nMod = modified.size();
        if (nOrig == 0) return (nMod == 0) ? 1.0 : 0.0;
        if (nMod == 0) return 0.0;
        double sizeRatio = nMod / nOrig;
        return Math.min(sizeRatio, 1.0 / sizeRatio);
    }

    // Converts a GenericEvent into a normalized feature vector
    private double[] toVector(DoubleFieldLookup e) {
        double[] v = new double[this.attributes.size()];
        for (int i = 0; i < this.attributes.size(); i++) {
            String attrName = this.attributes.get(i);
            double val = e.lookup(attrName);
            double inv = inverseStds.get(attrName);
            v[i] = Double.isNaN(val) ? Double.NaN : val * inv;
        }
        return v;
    }

    private static final class DoubleAccumulator {
        private double[] values;
        private int size;
        private double sum;

        private DoubleAccumulator(int initialCapacity) {
            values = new double[Math.max(0, initialCapacity)];
        }

        private void add(double value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, Math.max(4, size * 2));
            }
            values[size++] = value;
            sum += value;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private int size() {
            return size;
        }

        private double sum() {
            return sum;
        }

        private double get(int index) {
            return values[index];
        }

        private double quantile(double p) {
            if (isEmpty()) {
                return 0.0;
            }
            sort();
            return values[(int) Math.floor(p * (size - 1))];
        }

        private void sort() {
            Arrays.sort(values, 0, size);
        }
    }
}
