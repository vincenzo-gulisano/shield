package metrics.privacy.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// KDTree Implementation
public class KDTree {
    private record Neighbor(double distance, double[] point) {
    }

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
        BoundedMaxHeap nearestDistances = findNearestDistanceHeap(target, k);
        double[] values = nearestDistances.toArray();
        List<Double> distances = new ArrayList<>(values.length);
        for (double value : values) {
            distances.add(value);
        }
        return distances;
    }

    public List<double[]> findNearestPoints(double[] target, int k) {
        BoundedNeighborMaxHeap nearestNeighbors = findNearestNeighborHeap(target, k);
        List<Neighbor> values = nearestNeighbors.toList();
        List<double[]> points = new ArrayList<>(values.size());
        for (Neighbor value : values) {
            points.add(value.point());
        }
        return points;
    }

    public double findNearestDistanceStdDev(double[] target, int k) {
        BoundedMaxHeap nearestDistances = findNearestDistanceHeap(target, k);
        if (nearestDistances.size() < 2) {
            return Double.NaN;
        }
        return nearestDistances.sqrtStdDev();
    }

    private BoundedMaxHeap findNearestDistanceHeap(double[] target, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be positive");
        }
        BoundedMaxHeap nearestDistances = new BoundedMaxHeap(k);
        searchKNearest(root, target, nearestDistances);
        return nearestDistances;
    }

    private BoundedNeighborMaxHeap findNearestNeighborHeap(double[] target, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be positive");
        }
        BoundedNeighborMaxHeap nearestNeighbors = new BoundedNeighborMaxHeap(k);
        searchKNearest(root, target, nearestNeighbors);
        return nearestNeighbors;
    }

    // Search for the k nearest tuples with pruning
    private void searchKNearest(Node node, double[] target, BoundedMaxHeap nearestDistances) {
        if (node == null) return;

        // Calculate distance between target and current node
        double distSq = calculateMeanDistance(target, node.point);

        // Add to queue if valid
        if (!Double.isNaN(distSq) && !Double.isInfinite(distSq)) {
            nearestDistances.offer(distSq);
        }

        // Determine which child to visit first
        int axis = node.axis;
        double targetVal = target[axis];
        double nodeVal = node.point[axis];

        // If we have NaNs on the splitting axis, we cannot make a binary decision so we visit both
        if (Double.isNaN(targetVal) || Double.isNaN(nodeVal)) {
            searchKNearest(node.left, target, nearestDistances);
            searchKNearest(node.right, target, nearestDistances);
            return;
        }

        double diff = targetVal - nodeVal;
        double diffSq = diff * diff;
        Node near = diff < 0 ? node.left : node.right;
        Node far = diff < 0 ? node.right : node.left;

        // Visit the neared side
        searchKNearest(near, target, nearestDistances);

        // Pruning logic
        boolean mustVisitFar = false;
        if (!nearestDistances.isFull())
            // If we haven't found k tuples yet, we must search everywhere
            mustVisitFar = true;
        else if (diffSq < (nearestDistances.max() * maxDims))
            mustVisitFar = true;
        if (!mustVisitFar && far == node.right && node.rightHasNaNs) mustVisitFar = true;
        if (mustVisitFar) searchKNearest(far, target, nearestDistances);
    }

    private void searchKNearest(Node node, double[] target, BoundedNeighborMaxHeap nearestNeighbors) {
        if (node == null) return;

        double distSq = calculateMeanDistance(target, node.point);
        if (!Double.isNaN(distSq) && !Double.isInfinite(distSq)) {
            nearestNeighbors.offer(distSq, node.point);
        }

        int axis = node.axis;
        double targetVal = target[axis];
        double nodeVal = node.point[axis];

        if (Double.isNaN(targetVal) || Double.isNaN(nodeVal)) {
            searchKNearest(node.left, target, nearestNeighbors);
            searchKNearest(node.right, target, nearestNeighbors);
            return;
        }

        double diff = targetVal - nodeVal;
        double diffSq = diff * diff;
        Node near = diff < 0 ? node.left : node.right;
        Node far = diff < 0 ? node.right : node.left;

        searchKNearest(near, target, nearestNeighbors);

        boolean mustVisitFar = false;
        if (!nearestNeighbors.isFull())
            mustVisitFar = true;
        else if (diffSq < (nearestNeighbors.maxDistance() * maxDims))
            mustVisitFar = true;
        if (!mustVisitFar && far == node.right && node.rightHasNaNs) mustVisitFar = true;
        if (mustVisitFar) searchKNearest(far, target, nearestNeighbors);
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

    private static final class BoundedMaxHeap {
        private final double[] values;
        private int size;

        private BoundedMaxHeap(int capacity) {
            values = new double[capacity];
        }

        private void offer(double value) {
            if (size < values.length) {
                values[size] = value;
                siftUp(size);
                size++;
            } else if (value < values[0]) {
                values[0] = value;
                siftDown(0);
            }
        }

        private boolean isFull() {
            return size == values.length;
        }

        private double max() {
            return values[0];
        }

        private int size() {
            return size;
        }

        private double[] toArray() {
            return Arrays.copyOf(values, size);
        }

        private double sqrtStdDev() {
            double sum = 0.0;
            for (int i = 0; i < size; i++) {
                sum += Math.sqrt(values[i]);
            }
            double mean = sum / size;

            double sqDiff = 0.0;
            for (int i = 0; i < size; i++) {
                double diff = Math.sqrt(values[i]) - mean;
                sqDiff += diff * diff;
            }
            return Math.sqrt(Math.max(0.0, sqDiff / (size - 1)));
        }

        private void siftUp(int index) {
            while (index > 0) {
                int parent = (index - 1) / 2;
                if (values[parent] >= values[index]) {
                    return;
                }
                swap(parent, index);
                index = parent;
            }
        }

        private void siftDown(int index) {
            while (true) {
                int left = index * 2 + 1;
                int right = left + 1;
                int largest = index;
                if (left < size && values[left] > values[largest]) {
                    largest = left;
                }
                if (right < size && values[right] > values[largest]) {
                    largest = right;
                }
                if (largest == index) {
                    return;
                }
                swap(index, largest);
                index = largest;
            }
        }

        private void swap(int a, int b) {
            double tmp = values[a];
            values[a] = values[b];
            values[b] = tmp;
        }
    }

    private static final class BoundedNeighborMaxHeap {
        private final Neighbor[] values;
        private int size;

        private BoundedNeighborMaxHeap(int capacity) {
            values = new Neighbor[capacity];
        }

        private void offer(double distance, double[] point) {
            Neighbor neighbor = new Neighbor(distance, point);
            if (size < values.length) {
                values[size] = neighbor;
                siftUp(size);
                size++;
            } else if (distance < values[0].distance()) {
                values[0] = neighbor;
                siftDown(0);
            }
        }

        private boolean isFull() {
            return size == values.length;
        }

        private double maxDistance() {
            return values[0].distance();
        }

        private List<Neighbor> toList() {
            return new ArrayList<>(Arrays.asList(Arrays.copyOf(values, size)));
        }

        private void siftUp(int index) {
            while (index > 0) {
                int parent = (index - 1) / 2;
                if (values[parent].distance() >= values[index].distance()) {
                    return;
                }
                swap(parent, index);
                index = parent;
            }
        }

        private void siftDown(int index) {
            while (true) {
                int left = index * 2 + 1;
                int right = left + 1;
                int largest = index;
                if (left < size && values[left].distance() > values[largest].distance()) {
                    largest = left;
                }
                if (right < size && values[right].distance() > values[largest].distance()) {
                    largest = right;
                }
                if (largest == index) {
                    return;
                }
                swap(index, largest);
                index = largest;
            }
        }

        private void swap(int a, int b) {
            Neighbor tmp = values[a];
            values[a] = values[b];
            values[b] = tmp;
        }
    }
}
