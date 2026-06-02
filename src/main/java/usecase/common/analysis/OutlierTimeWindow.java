package usecase.common.analysis;

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

    private static final double DEFAULT_OUTLIER_SCORE_QUANTILE = 0.95;

    private final double outlierScoreQuantile;
    private final List<Tuple> tuples = new ArrayList<>();

    public OutlierTimeWindow() {
        this(DEFAULT_OUTLIER_SCORE_QUANTILE);
    }

    public OutlierTimeWindow(double outlierScoreQuantile) {
        if (outlierScoreQuantile < 0.0 || outlierScoreQuantile > 1.0 || Double.isNaN(outlierScoreQuantile)) {
            throw new IllegalArgumentException("outlierScoreQuantile must be in [0, 1]");
        }
        this.outlierScoreQuantile = outlierScoreQuantile;
    }

    @Override
    public TimeWindowAdd<Tuple, ListTuple> factory() {
        return new OutlierTimeWindow(outlierScoreQuantile);
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
                outlierScoreQuantile);
        return new ListTuple(startTimestamp, outputKey, outliers);
    }
}
