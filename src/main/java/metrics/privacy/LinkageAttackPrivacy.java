package metrics.privacy;

import grammar.generator.FieldType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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

    public static final int DEFAULT_TRUE_RANK_MAX = 50;

    private static final double EPSILON = 1e-12;

    private final int k;
    private final int trueRankMax;
    private final List<String> attributes;
    private final Map<String, FieldType> attributeTypes;
    private final Map<String, Double> inverseStds;
    private final Map<Long, double[]> originalVectorsByLinkageId;
    private final List<double[]> originalVectors;
    private final KDTree originalTree;

    public LinkageAttackPrivacy(List<? extends Tuple> originalStream, int k, List<String> attributes) {
        this(originalStream, k, attributes, attributes.stream()
                .collect(Collectors.toMap(a -> a, ignored -> FieldType.CONTINUOUS_NUMERIC)));
    }

    public LinkageAttackPrivacy(
            List<? extends Tuple> originalStream,
            int k,
            List<String> attributes,
            Map<String, FieldType> attributeTypes) {
        this(originalStream, k, attributes, attributeTypes, DEFAULT_TRUE_RANK_MAX);
    }

    public LinkageAttackPrivacy(
            List<? extends Tuple> originalStream,
            int k,
            List<String> attributes,
            Map<String, FieldType> attributeTypes,
            int trueRankMax) {
        if (originalStream == null || originalStream.isEmpty()) {
            throw new IllegalArgumentException("Original stream cannot be null or empty");
        }
        if (k < 1) {
            throw new IllegalArgumentException("k must be at least 1");
        }
        if (trueRankMax < k) {
            throw new IllegalArgumentException("trueRankMax must be at least k");
        }
        this.k = k;
        this.trueRankMax = Math.max(k, trueRankMax);
        this.attributes = List.copyOf(attributes);
        this.attributeTypes = validateAttributeTypes(this.attributes, attributeTypes);

        Map<String, Double> means = this.attributes.stream()
                .collect(Collectors.toMap(
                        a -> a,
                        a -> isNominal(a)
                                ? 0.0
                                : attributeValues(originalStream, a).stream()
                                        .mapToDouble(Double::doubleValue)
                                        .average()
                                        .orElse(0.0)));

        this.inverseStds = this.attributes.stream()
                .collect(Collectors.toMap(
                        a -> a,
                        a -> {
                            if (isNominal(a)) {
                                return 1.0;
                            }
                            List<Double> values = attributeValues(originalStream, a);
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

        originalVectors = originalVectorsByLinkageId.values().stream()
                .filter(v -> !MetricUtils.isAllNaN(v))
                .collect(Collectors.toList());
        originalTree = hasNominalAttributes() ? null : new KDTree(originalVectors);
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

    /**
     * True-rank linkage privacy.
     *
     * <p>For each released tuple {@code y}, the metric finds the nearest-neighbor rank {@code k'}
     * needed to include its true original tuple {@code x} when original tuples are ordered around
     * {@code y}. If {@code k' <= k}, the tuple still has full attack risk and contributes privacy
     * {@code 0}. Otherwise, the attack risk is {@code 1 / k'}, so the tuple contributes privacy
     * {@code 1 - 1 / k'}. Ranks are capped at {@code trueRankMax} to keep the KD-tree path bounded.
     * Duplicates in the modified stream are grouped by linkage id and count once, using the most
     * revealing duplicate.
     */
    public double applyTrueRankScore(List<? extends Tuple> modifiedStream) {
        if (modifiedStream == null) {
            return 0.0;
        }
        Map<Long, Double> maxRiskByLinkageId = new LinkedHashMap<>();
        for (Tuple tuple : modifiedStream) {
            long linkageId = requireLinkageId(tuple);
            if (!originalVectorsByLinkageId.containsKey(linkageId)) {
                throw new IllegalArgumentException("Unknown modified tuple linkage id: " + linkageId);
            }
            double risk = trueRankAttackRisk(tuple, linkageId);
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
        List<Double> nearestDistances = findNearestDistances(modifiedVector);
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

    private double trueRankAttackRisk(Tuple modifiedTuple, long linkageId) {
        double[] modifiedVector = toVector(modifiedTuple);
        if (MetricUtils.isAllNaN(modifiedVector)) {
            return 0.0;
        }
        double[] trueOriginalVector = originalVectorsByLinkageId.get(linkageId);
        int trueRank = trueOriginalRank(modifiedVector, trueOriginalVector);
        if (trueRank < 1) {
            return 0.0;
        }
        if (trueRank <= k) {
            return 1.0;
        }
        return 1.0 / trueRank;
    }

    private int trueOriginalRank(double[] modifiedVector, double[] trueOriginalVector) {
        if (originalTree != null) {
            return trueOriginalRankWithTree(modifiedVector, trueOriginalVector);
        }
        return trueOriginalRankByScan(modifiedVector, trueOriginalVector);
    }

    private int trueOriginalRankWithTree(double[] modifiedVector, double[] trueOriginalVector) {
        int maxRank = Math.min(trueRankMax, originalVectors.size());
        int topKLimit = Math.min(k, maxRank);
        if (containsTrueOriginalInNearest(modifiedVector, trueOriginalVector, topKLimit)) {
            return topKLimit;
        }
        if (topKLimit >= maxRank) {
            return maxRank;
        }

        List<double[]> cappedNearest = originalTree.findNearestPoints(modifiedVector, maxRank);
        if (!containsByReference(cappedNearest, trueOriginalVector)) {
            return maxRank;
        }
        cappedNearest.sort(Comparator.comparingDouble(candidate -> calculateMeanDistance(modifiedVector, candidate)));
        for (int i = 0; i < cappedNearest.size(); i++) {
            if (cappedNearest.get(i) == trueOriginalVector) {
                return i + 1;
            }
        }
        return maxRank;
    }

    private boolean containsTrueOriginalInNearest(
            double[] modifiedVector,
            double[] trueOriginalVector,
            int limit) {
        if (limit < 1) {
            return false;
        }
        for (double[] candidate : originalTree.findNearestPoints(modifiedVector, limit)) {
            if (candidate == trueOriginalVector) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsByReference(List<double[]> candidates, double[] target) {
        for (double[] candidate : candidates) {
            if (candidate == target) {
                return true;
            }
        }
        return false;
    }

    private int trueOriginalRankByScan(double[] modifiedVector, double[] trueOriginalVector) {
        double trueDistance = calculateMeanDistance(modifiedVector, trueOriginalVector);
        if (Double.isNaN(trueDistance) || Double.isInfinite(trueDistance)) {
            return 0;
        }
        int rank = 0;
        for (double[] originalVector : originalVectors) {
            double distance = calculateMeanDistance(modifiedVector, originalVector);
            if (!Double.isNaN(distance)
                    && !Double.isInfinite(distance)
                    && distance <= trueDistance + EPSILON) {
                rank++;
                if (rank >= trueRankMax) {
                    return trueRankMax;
                }
            }
        }
        return rank;
    }

    private double[] toVector(Tuple tuple) {
        double[] vector = new double[attributes.size()];
        for (int i = 0; i < attributes.size(); i++) {
            String attribute = attributes.get(i);
            double value = requireFinite(attribute, tuple.lookup(attribute));
            double inverseStd = inverseStds.get(attribute);
            vector[i] = isNominal(attribute) ? value : value * inverseStd;
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

    private List<Double> findNearestDistances(double[] modifiedVector) {
        if (originalTree != null) {
            return originalTree.findNearestDistances(modifiedVector, k);
        }

        PriorityQueue<Double> nearestDistances = new PriorityQueue<>(Comparator.reverseOrder());
        for (double[] originalVector : originalVectors) {
            double distance = calculateMeanDistance(modifiedVector, originalVector);
            if (Double.isNaN(distance) || Double.isInfinite(distance)) {
                continue;
            }
            if (nearestDistances.size() < k) {
                nearestDistances.offer(distance);
            } else if (distance < nearestDistances.peek()) {
                nearestDistances.poll();
                nearestDistances.offer(distance);
            }
        }
        return new ArrayList<>(nearestDistances);
    }

    private double calculateMeanDistance(double[] a, double[] b) {
        double sum = 0.0;
        int valid = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
                sum += fieldDistance(i, a[i], b[i]);
                valid++;
            }
        }
        return valid == 0 ? Double.POSITIVE_INFINITY : sum / valid;
    }

    private double fieldDistance(int attributeIndex, double a, double b) {
        FieldType fieldType = attributeTypes.get(attributes.get(attributeIndex));
        return switch (fieldType) {
            case NOMINAL_CATEGORICAL -> Double.compare(a, b) == 0 ? 0.0 : 1.0;
            case DISCRETE_NUMERIC, CONTINUOUS_NUMERIC -> {
                double d = a - b;
                yield d * d;
            }
        };
    }

    private boolean hasNominalAttributes() {
        return attributes.stream().anyMatch(this::isNominal);
    }

    private boolean isNominal(String attribute) {
        return attributeTypes.get(attribute) == FieldType.NOMINAL_CATEGORICAL;
    }

    private static Map<String, FieldType> validateAttributeTypes(
            List<String> attributes,
            Map<String, FieldType> attributeTypes) {
        if (attributeTypes == null) {
            throw new IllegalArgumentException("Attribute types cannot be null");
        }
        Map<String, FieldType> result = new LinkedHashMap<>();
        for (String attribute : attributes) {
            FieldType fieldType = attributeTypes.get(attribute);
            if (fieldType == null) {
                throw new IllegalArgumentException("Missing type for linkage attribute: " + attribute);
            }
            result.put(attribute, fieldType);
        }
        return Map.copyOf(result);
    }

    private static List<Double> attributeValues(List<? extends Tuple> tuples, String attribute) {
        return tuples.stream()
                .map(tuple -> requireFinite(attribute, tuple.lookup(attribute)))
                .collect(Collectors.toList());
    }

    private static double requireFinite(String attribute, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Attribute " + attribute + " has non-finite value: " + value);
        }
        return value;
    }
}
