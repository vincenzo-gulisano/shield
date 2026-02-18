package metrics.privacy.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

// KDTree Implementation
public class KDTree {
    private static class Node {
        double[] point;
        int axis;
        Node left, right;
        boolean rightHasNaNs; // Flag to know if NaNs exist in the right branch to help pruning
        Node(double[] point, int axis) { this.point = point; this.axis = axis; }
    }

    private final Node root;
    private final int dims;
    private final double maxDims;

    public KDTree(List<double[]> points) {
        if (points.isEmpty()) {
            this.root = null;
            this.dims = 0;
            this.maxDims = 0;
        } else {
            this.dims = points.get(0).length;
            this.maxDims = (double) this.dims;
            this.root = build(points, 0);
        }
    }

    // Recursive build function
    private Node build(List<double[]> pts, int depth) {
        if (pts.isEmpty()) return null;
        int axis = depth % dims;

        // Sort points based on current axis
        pts.sort((a, b) -> {
            double va = a[axis];
            double vb = b[axis];
            if (Double.isNaN(va) && Double.isNaN(vb)) return 0;
            if (Double.isNaN(va)) return 1; // Send NaNs at the end (right)
            if (Double.isNaN(vb)) return -1;
            return Double.compare(va, vb);
        });
        int mid = pts.size() / 2;
        Node node = new Node(pts.get(mid), axis);

        // Check if the right subset contains NaNs to set the flag
        boolean hasNaNs = false;
        if (pts.size() > mid + 1) {
            double lastVal = pts.get(pts.size() - 1)[axis];
            if (Double.isNaN(lastVal)) hasNaNs = true;
        }
        node.rightHasNaNs = hasNaNs;
        node.left = build(pts.subList(0, mid), depth + 1);
        node.right = build(pts.subList(mid + 1, pts.size()), depth + 1);
        return node;
    }

    // Method to find distances of k nearest tuples
    public List<Double> findNearestDistances(double[] target, int k) {
        // Priority Queue to keep track of the k smallest distances so far
        // The head of the queue is the largest distance among the k best
        PriorityQueue<Double> pq = new PriorityQueue<>(k, Collections.reverseOrder());
        searchKNearest(root, target, k, pq);
        return new ArrayList<>(pq);
    }

    // Search for the k nearest tuples with pruning
    private void searchKNearest(Node node, double[] target, int k, PriorityQueue<Double> pq) {
        if (node == null) return;

        // Calculate distance between target and current node
        double distSq = calculateMeanDistance(target, node.point);

        // Add to queue if valid
        if (!Double.isNaN(distSq) && !Double.isInfinite(distSq)) {
            if (pq.size() < k) {
                pq.add(distSq);
            } else if (distSq < pq.peek()) {
                pq.poll(); // Remove worst of the best
                pq.add(distSq); // Add new candidate
            }
        }

        // Determine which child to visit first
        int axis = node.axis;
        double targetVal = target[axis];
        double nodeVal = node.point[axis];

        // If we have NaNs on the splitting axis, we cannot make a binary decision so we visit both
        if (Double.isNaN(targetVal) || Double.isNaN(nodeVal)) {
            searchKNearest(node.left, target, k, pq);
            searchKNearest(node.right, target, k, pq);
            return;
        }

        double diff = targetVal - nodeVal;
        double diffSq = diff * diff;
        Node near = diff < 0 ? node.left : node.right;
        Node far = diff < 0 ? node.right : node.left;

        // Visit the neared side
        searchKNearest(near, target, k, pq);

        // Pruning logic
        boolean mustVisitFar = false;
        if (pq.size() < k)
            // If we haven't found k tuples yet, we must search everywhere
            mustVisitFar = true;
        else if (diffSq < (pq.peek() * maxDims))
            mustVisitFar = true;
        if (!mustVisitFar && far == node.right && node.rightHasNaNs) mustVisitFar = true;
        if (mustVisitFar) searchKNearest(far, target, k, pq);
    }

    // Calculate Mean Squared Distance
    private static double calculateMeanDistance(double[] a, double[] b) {
        double sum = 0;
        int valid = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
                double d = a[i] - b[i];
                sum += d * d;
                valid++;
            }
        }
        if (valid == 0) return Double.POSITIVE_INFINITY;
        return sum / valid;
    }
}