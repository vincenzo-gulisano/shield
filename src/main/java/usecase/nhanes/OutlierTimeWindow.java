package usecase.nhanes;

import component.operator.in1.aggregate.BaseTimeWindowAdd;
import component.operator.in1.aggregate.TimeWindowAdd;
import java.util.ArrayList;
import java.util.List;
import usecase.common.ListTuple;
import usecase.common.Tuple;

/**
 * Time-window aggregate that emits the tuples whose multivariate outlier score is at or above p95.
 *
 * <p>The output is a {@link ListTuple}. Each contained tuple uses the window start timestamp, keeps
 * the input tuple key, and has one field: {@code f1 = outlier_score}.
 */
public final class OutlierTimeWindow extends BaseTimeWindowAdd<Tuple, ListTuple> {

    private static final double OUTLIER_SCORE_QUANTILE = 0.95;

    private final List<Tuple> tuples = new ArrayList<>();

    @Override
    public TimeWindowAdd<Tuple, ListTuple> factory() {
        return new OutlierTimeWindow();
    }

    @Override
    public void add(Tuple tuple) {
        tuples.add(new Tuple(tuple));
    }

    @Override
    public ListTuple getAggregatedResult() {
        String outputKey = key == null ? "" : key;
        List<Tuple> outliers = OutlierDetector.findOutliersAtOrAboveScoreQuantile(
                tuples,
                startTimestamp,
                OUTLIER_SCORE_QUANTILE);
        return new ListTuple(startTimestamp, outputKey, outliers);
    }
}
