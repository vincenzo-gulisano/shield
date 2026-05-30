package metrics.privacy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import metrics.privacy.utils.KDTree;
import metrics.privacy.utils.MetricUtils;
import usecase.common.Tuple;

/**
 * Linkage-attack privacy score based on whether modified tuples can be linked back to their
 * original tuple through a hidden linkage id.
 *
 * <p>The linkage id is used only to verify attack success. Distances are computed only on the
 * configured visible tuple fields.
 */
public class LinkageAttackPrivacy {

    private static final double EPSILON = 1e-12;

    private final int k;
    private final List<String> attributes;
    private final Map<String, Double> inverseStds;
    private final Map<Long, double[]> originalVectorsByLinkageId;
    private final KDTree originalTree;

    public LinkageAttackPrivacy(List<? extends Tuple> originalStream, int k, List<String> attributes) {
        if (originalStream == null || originalStream.isEmpty()) {
            throw new IllegalArgumentException("Original stream cannot be null or empty");
        }
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1");
        }
        this.k = k;
        this.attributes = List.copyOf(attributes);

        Map<String, Double> means = this.attributes.stream()
                .collect(Collectors.toMap(
                        a -> a,
                        a -> originalStream.stream()
                                .mapToDouble(e -> e.lookup(a))
                                .filter(v -> !Double.isNaN(v))
                                .average()
                                .orElse(0.0)));

        this.inverseStds = this.attributes.stream()
                .collect(Collectors.toMap(
                        a -> a,
                        a -> {
                            List<Double> values = originalStream.stream()
                                    .map(e -> e.lookup(a))
                                    .filter(v -> !Double.isNaN(v))
                                    .collect(Collectors.toList());
                            if (values.size() < 2) {
                                return 1.0;
                            }
                            double mean = means.get(a);
                            double ssq = values.stream()
                                    .mapToDouble(v -> (v - mean) * (v - mean))
                                    .sum();
                            double std = Math.sqrt(ssq / (values.size() - 1));
                            return std > 1e-9 ? 1.0 / std : 1.0;
                        }));

        originalVectorsByLinkageId = new HashMap<>();
        for (Tuple tuple : originalStream) {
            long linkageId = requireLinkageId(tuple);
            if (originalVectorsByLinkageId.containsKey(linkageId)) {
                throw new IllegalArgumentException("Duplicate original linkage id: " + linkageId);
            }
            originalVectorsByLinkageId.put(linkageId, toVector(tuple));
        }

        List<double[]> originalVectors = originalVectorsByLinkageId.values().stream()
                .filter(v -> !MetricUtils.isAllNaN(v))
                .collect(Collectors.toList());
        originalTree = new KDTree(originalVectors);
    }

    /**
     * Expected re-identification privacy.
     *
     * <p>If the true original id is in the top-k nearest candidates, the attack success
     * contribution is {@code 1 / k}; when the original dataset has fewer than {@code k} valid
     * candidates, the actual candidate count is used instead. Otherwise the contribution is
     * {@code 0}. Duplicates in the modified stream are grouped by linkage id and count once, using
     * the most revealing duplicate.
     */
    public double applyExpectedSuccess(List<? extends Tuple> modifiedStream) {
        return apply(modifiedStream, false);
    }

    /**
     * Top-k shortlist privacy.
     *
     * <p>If the true original id is in the top-k nearest candidates, the tuple is considered
     * linked. Duplicates in the modified stream are grouped by linkage id and count once, using the
     * most revealing duplicate.
     */
    public double applyTopKContainment(List<? extends Tuple> modifiedStream) {
        return apply(modifiedStream, true);
    }

    private double apply(List<? extends Tuple> modifiedStream, boolean topKContainment) {
        if (modifiedStream == null) {
            return 0.0;
        }
        Map<Long, Double> maxRiskByLinkageId = new LinkedHashMap<>();
        for (Tuple tuple : modifiedStream) {
            long linkageId = requireLinkageId(tuple);
            if (!originalVectorsByLinkageId.containsKey(linkageId)) {
                throw new IllegalArgumentException("Unknown modified tuple linkage id: " + linkageId);
            }
            double risk = attackRisk(tuple, linkageId, topKContainment);
            maxRiskByLinkageId.merge(linkageId, risk, Math::max);
        }
        if (maxRiskByLinkageId.isEmpty()) {
            return 1.0;
        }
        double totalRisk = 0.0;
        for (double risk : maxRiskByLinkageId.values()) {
            totalRisk += risk;
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - totalRisk / maxRiskByLinkageId.size()));
    }

    private double attackRisk(Tuple modifiedTuple, long linkageId, boolean topKContainment) {
        double[] modifiedVector = toVector(modifiedTuple);
        if (MetricUtils.isAllNaN(modifiedVector)) {
            return 0.0;
        }
        List<Double> nearestDistances = originalTree.findNearestDistances(modifiedVector, k);
        if (nearestDistances.isEmpty()) {
            return 0.0;
        }
        double topKDistanceThreshold = nearestDistances.stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(Double.NaN);
        double trueDistance = calculateMeanDistance(modifiedVector, originalVectorsByLinkageId.get(linkageId));
        if (Double.isNaN(trueDistance) || Double.isInfinite(trueDistance)
                || trueDistance > topKDistanceThreshold + EPSILON) {
            return 0.0;
        }
        return topKContainment ? 1.0 : 1.0 / nearestDistances.size();
    }

    private double[] toVector(Tuple tuple) {
        double[] vector = new double[attributes.size()];
        for (int i = 0; i < attributes.size(); i++) {
            String attribute = attributes.get(i);
            double value = tuple.lookup(attribute);
            double inverseStd = inverseStds.get(attribute);
            vector[i] = Double.isNaN(value) ? Double.NaN : value * inverseStd;
        }
        return vector;
    }

    private static long requireLinkageId(Tuple tuple) {
        if (tuple == null) {
            throw new IllegalArgumentException("Tuple cannot be null");
        }
        if (!tuple.hasLinkageId()) {
            throw new IllegalArgumentException("Tuple is missing linkage id");
        }
        return tuple.getLinkageId();
    }

    private static double calculateMeanDistance(double[] a, double[] b) {
        double sum = 0.0;
        int valid = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
                double d = a[i] - b[i];
                sum += d * d;
                valid++;
            }
        }
        return valid == 0 ? Double.POSITIVE_INFINITY : sum / valid;
    }
}
