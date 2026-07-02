package usecase.geolife.mobility;

import component.operator.in1.aggregate.BaseTimeWindowAddRemove;
import component.operator.in1.aggregate.TimeWindowAddRemove;
import java.util.HashMap;
import java.util.Map;
import usecase.common.Tuple;

/**
 * Time-window aggregate for the second stage of the GeoLife mobility query.
 *
 * <p>The upstream query first maps per-user visit summaries to spatial-cell keys. This window then
 * receives all visit summaries for one cell and one time window, keeps running add/remove state, and
 * emits one hotspot summary tuple for that cell/window.
 *
 * <p>Input tuples are expected to have:
 * {@code f1 = visit centroid X}, {@code f2 = visit centroid Y},
 * {@code f3 = source samples in the visit}, {@code f4 = visit radius},
 * and {@code f5 = numeric user id}. The tuple key is the spatial cell id.
 *
 * <p>The output tuple keeps the cell key and uses the window start timestamp. Its fields are:
 * {@code f1 = cell centroid X}, {@code f2 = cell centroid Y},
 * {@code f3 = unique user count}, {@code f4 = visit-window count},
 * {@code f5 = average source samples per visit}, and
 * {@code f6 = average visit radius}.
 */
public final class GeoLifeCellHotspotWindow extends BaseTimeWindowAddRemove<Tuple, Tuple> {

    private final Map<Long, Integer> userCounts = new HashMap<>();
    private int visitCount;
    private double sumX;
    private double sumY;
    private double sumSamples;
    private double sumRadius;

    /**
     * Creates a fresh empty window instance for Liebre when it opens a new keyed time window.
     *
     * @return a new aggregate window with no accumulated visits
     */
    @Override
    public TimeWindowAddRemove<Tuple, Tuple> factory() {
        return new GeoLifeCellHotspotWindow();
    }

    /**
     * Adds one finite per-user visit summary to this cell/window.
     *
     * <p>The method updates total visit count, centroid sums, sample-count sum, radius sum, and the
     * per-user multiplicity map used to derive the number of distinct users. Tuples with missing or
     * non-finite required fields are ignored.
     *
     * @param tuple per-user visit tuple keyed by spatial cell
     */
    @Override
    public void add(Tuple tuple) {
        if (!hasFiniteFields(tuple)) {
            return;
        }
        visitCount++;
        sumX += tuple.getField("f1");
        sumY += tuple.getField("f2");
        sumSamples += tuple.getField("f3");
        sumRadius += tuple.getField("f4");
        userCounts.merge(Math.round(tuple.getField("f5")), 1, Integer::sum);
    }

    /**
     * Removes one per-user visit summary when it leaves a sliding time window.
     *
     * <p>This reverses {@link #add(Tuple)} for the numeric sums and decrements the contributing
     * user's multiplicity. When a user's last visit leaves the window, the user is removed from the
     * distinct-user map.
     *
     * @param tuple per-user visit tuple keyed by spatial cell
     */
    @Override
    public void remove(Tuple tuple) {
        if (!hasFiniteFields(tuple)) {
            return;
        }
        visitCount--;
        sumX -= tuple.getField("f1");
        sumY -= tuple.getField("f2");
        sumSamples -= tuple.getField("f3");
        sumRadius -= tuple.getField("f4");
        long userId = Math.round(tuple.getField("f5"));
        userCounts.computeIfPresent(userId, (ignored, count) -> count == 1 ? null : count - 1);
    }

    /**
     * Emits the current hotspot summary for this cell/window.
     *
     * <p>If the window has no valid visits, the output contains {@code NaN} centroid/radius values
     * and zero counts. Otherwise, it emits the average visit centroid, number of distinct users,
     * number of visit windows, average number of source samples per visit, and average visit radius.
     *
     * @return one tuple summarizing the current keyed cell/window
     */
    @Override
    public Tuple getAggregatedResult() {
        if (visitCount <= 0) {
            return new Tuple(startTimestamp, keyOrEmpty(), Double.NaN, Double.NaN, 0d, 0d, 0d, Double.NaN);
        }
        return new Tuple(
                startTimestamp,
                keyOrEmpty(),
                sumX / visitCount,
                sumY / visitCount,
                userCounts.size(),
                visitCount,
                sumSamples / visitCount,
                sumRadius / visitCount);
    }

    /**
     * Checks whether all fields required by this aggregate are finite.
     *
     * @param tuple candidate visit summary
     * @return {@code true} if the tuple can safely update aggregate state
     */
    private static boolean hasFiniteFields(Tuple tuple) {
        return Double.isFinite(tuple.getField("f1"))
                && Double.isFinite(tuple.getField("f2"))
                && Double.isFinite(tuple.getField("f3"))
                && Double.isFinite(tuple.getField("f4"))
                && Double.isFinite(tuple.getField("f5"));
    }

    /**
     * Returns Liebre's current keyed-window key, using an empty key when Liebre has not set one.
     *
     * @return the current spatial-cell key or an empty string
     */
    private String keyOrEmpty() {
        return key == null ? "" : key;
    }
}
