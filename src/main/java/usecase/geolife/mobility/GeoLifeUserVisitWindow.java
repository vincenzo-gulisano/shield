package usecase.geolife.mobility;

import component.operator.in1.aggregate.BaseTimeWindowAddRemove;
import component.operator.in1.aggregate.TimeWindowAddRemove;
import usecase.common.Tuple;

/**
 * Time-window aggregate for the first stage of the GeoLife mobility query.
 *
 * <p>Liebre instantiates this window per key and per time window. In this query the key is the
 * GeoLife user id, so each instance summarizes one user's points over the configured visit window.
 * The result is a compact per-user visit tuple that the next query stage maps to a spatial cell.
 *
 * <p>Input tuples are expected to have {@code f1 = X coordinate} and {@code f2 = Y coordinate}.
 * Tuples whose coordinates are missing or non-finite are ignored.
 *
 * <p>The output tuple keeps the user key and uses the window start timestamp. Its fields are:
 * {@code f1 = visit centroid X}, {@code f2 = visit centroid Y},
 * {@code f3 = valid source sample count}, {@code f4 = root-mean-square visit radius}, and
 * {@code f5 = numeric user id}.
 *
 * <p>The radius is computed as:
 *
 * <pre>
 * radius = sqrt(varianceX + varianceY)
 * </pre>
 *
 * <p>This is a spread measure around the centroid, not the maximum point-to-centroid distance. When
 * the window contains no valid finite coordinate pairs, the aggregate emits {@code NaN} centroid
 * fields, zero sample count, and {@code POSITIVE_INFINITY} radius.
 */
public final class GeoLifeUserVisitWindow extends BaseTimeWindowAddRemove<Tuple, Tuple> {

    private int count;
    private double sumX;
    private double sumY;
    private double sumSqX;
    private double sumSqY;

    /**
     * Creates a fresh empty window instance for Liebre when it opens a new keyed time window.
     *
     * @return a new aggregate window with no accumulated user points
     */
    @Override
    public TimeWindowAddRemove<Tuple, Tuple> factory() {
        return new GeoLifeUserVisitWindow();
    }

    /**
     * Adds one GeoLife point to the current user/window aggregate.
     *
     * <p>Only finite coordinate pairs update the aggregate state. The method keeps the count, sums,
     * and squared sums needed to compute the centroid and root-mean-square radius.
     *
     * @param tuple raw GeoLife point tuple keyed by user id
     */
    @Override
    public void add(Tuple tuple) {
        double x = tuple.getField("f1");
        double y = tuple.getField("f2");
        if (Double.isFinite(x) && Double.isFinite(y)) {
            count++;
            sumX += x;
            sumY += y;
            sumSqX += x * x;
            sumSqY += y * y;
        }
    }

    /**
     * Removes one GeoLife point when it leaves a sliding time window.
     *
     * <p>This reverses {@link #add(Tuple)} for finite coordinate pairs so the same window
     * implementation works for sliding time windows without rebuilding state from scratch.
     *
     * @param tuple raw GeoLife point tuple keyed by user id
     */
    @Override
    public void remove(Tuple tuple) {
        double x = tuple.getField("f1");
        double y = tuple.getField("f2");
        if (Double.isFinite(x) && Double.isFinite(y)) {
            count--;
            sumX -= x;
            sumY -= y;
            sumSqX -= x * x;
            sumSqY -= y * y;
        }
    }

    /**
     * Emits the current per-user visit summary.
     *
     * <p>The summary uses the window start timestamp and the current user key. For non-empty
     * windows it emits the centroid, valid point count, root-mean-square radius, and numeric user
     * id. Empty windows emit sentinel values that the downstream filter can reject.
     *
     * @return one tuple summarizing the current keyed user/window
     */
    @Override
    public Tuple getAggregatedResult() {
        if (count <= 0) {
            return new Tuple(startTimestamp, keyOrEmpty(), Double.NaN, Double.NaN, 0d, Double.POSITIVE_INFINITY,
                    userId());
        }
        double meanX = sumX / count;
        double meanY = sumY / count;
        double meanSquaredDistance = Math.max(
                0d,
                (sumSqX + sumSqY) / count - meanX * meanX - meanY * meanY);
        double radius = Math.sqrt(meanSquaredDistance);
        return new Tuple(startTimestamp, keyOrEmpty(), meanX, meanY, count, radius, userId());
    }

    /**
     * Returns Liebre's current keyed-window key, using an empty key when Liebre has not set one.
     *
     * @return the current user key or an empty string
     */
    private String keyOrEmpty() {
        return key == null ? "" : key;
    }

    /**
     * Converts the user key into a numeric field for downstream aggregation.
     *
     * <p>The GeoLife resource uses numeric-looking user ids, so normal execution parses the key as a
     * {@code double}. The hash fallback keeps the method defined for non-numeric keys used in tests
     * or future data variants.
     *
     * @return numeric representation of the current user key
     */
    private double userId() {
        if (key == null || key.isBlank()) {
            return 0d;
        }
        try {
            return Double.parseDouble(key);
        } catch (NumberFormatException ignored) {
            return Math.abs(key.hashCode());
        }
    }
}
