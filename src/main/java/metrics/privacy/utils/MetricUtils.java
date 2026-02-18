package metrics.privacy.utils;
import java.util.List;

public class MetricUtils {

    // Helper to check if a vector contains only NaN values
    public static boolean isAllNaN(double[] vector) {
        for (double v : vector) {
            if (!Double.isNaN(v)) return false;
        }
        return true;
    }

    // Standard Deviation calculation
    public static double calculateStdDev(List<Double> distances) {
        double sum = 0.0;
        for (double d : distances) sum += d;
        double mean = sum / distances.size();

        double sqDiff = 0.0;
        for (double d : distances) {
            double diff = d - mean;
            sqDiff += diff * diff;
        }
        return Math.sqrt(Math.max(0.0, sqDiff / (distances.size() - 1)));
    }
}
