package metrics.results;

import event.GenericEvent;
import io.github.ericmedvet.jgea.core.distance.Distance;

import java.util.*;

public class F1Score implements Distance<List<GenericEvent>> {

    private final double percentageThreshold;
    private final List<String> valueAttributes;

    public F1Score(double percentageThreshold, List<String> valueAttributes) {
        this.percentageThreshold = percentageThreshold;
        this.valueAttributes = valueAttributes;
    }

    /**
     * Computes the F1-score between ground truth and predicted output events.
     *
     * A True Positive (TP) is counted when:
     *  - timestamp is identical
     *  - key is identical
     *  - all specified numeric attributes differ by no more than a fixed relative threshold
     */
    @Override
    public Double apply(List<GenericEvent> groundTruth, List<GenericEvent> predictions) {

        if (groundTruth == null || predictions == null || predictions.isEmpty()) {
            return 0.0;
        }

        // Index ground truth events by (timestamp, key)
        Map<String, List<GenericEvent>> gtIndex = new HashMap<>();

        for (GenericEvent gt : groundTruth) {
            String compositeKey = buildKey(gt);
            gtIndex.computeIfAbsent(compositeKey, k -> new ArrayList<>())
                    .add(gt);
        }
        int truePositive = 0;

        // For each predicted event, attempt to find a matching ground truth event
        for (GenericEvent pred : predictions) {
            String compositeKey = buildKey(pred);
            List<GenericEvent> candidates = gtIndex.get(compositeKey);

            // No ground truth events with same (timestamp, key)
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }

            Iterator<GenericEvent> iterator = candidates.iterator();

            while (iterator.hasNext()) {
                GenericEvent gt = iterator.next();

                // Check value similarity under relative tolerance
                if (valuesAreSimilar(pred, gt)) {
                    truePositive++;
                    // Remove matched ground truth event to enforce one-to-one matching
                    iterator.remove();
                    break;
                }
            }
        }

        // Calculate False Positives and False Negatives
        int falsePositive = predictions.size() - truePositive;
        int falseNegative = groundTruth.size() - truePositive;

        // Calculate Precision and Recall
        double precision = (truePositive + falsePositive > 0) ? (double) truePositive / (truePositive + falsePositive) : 0.0;
        double recall = (truePositive + falseNegative > 0) ? (double) truePositive / (truePositive + falseNegative) : 0.0;

        // Calculate F1 score
        if (precision + recall == 0) {
            return 0.0;
        }
        return 2 * (precision * recall) / (precision + recall);
    }

    /**
     * Builds a composite key based on timestamp and logical key.
     */
    private String buildKey(GenericEvent e) {
        return e.getTimestamp() + "_" + e.getKey();
    }

    /**
     * Verifies whether all specified numeric attributes are similar
     * between two events within a fixed relative error threshold.
     *
     * The relative error is computed as:
     *      |gt - pred| / |gt|
     */
    private boolean valuesAreSimilar(GenericEvent pred, GenericEvent gt) {

        for (String attr : valueAttributes) {

            double predValue = pred.getAttribute(attr);
            double gtValue = gt.getAttribute(attr);

            // If either value is missing, events cannot match
            if (Double.isNaN(predValue) || Double.isNaN(gtValue)) {
                return false;
            }

            if (Math.abs(gtValue) < 1e-9) {
                if (Math.abs(predValue) > 1e-9) {
                    return false;
                }
            } else {
                double relativeError =
                        Math.abs(gtValue - predValue) / Math.abs(gtValue);

                if (relativeError > percentageThreshold) {
                    return false;
                }
            }
        }
        return true;
    }
}

