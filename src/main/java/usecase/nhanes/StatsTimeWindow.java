package usecase.nhanes;

import component.operator.in1.aggregate.BaseTimeWindowAdd;
import component.operator.in1.aggregate.TimeWindowAdd;
import java.util.ArrayList;
import java.util.List;
import usecase.common.ListTuple;
import usecase.common.Tuple;

/**
 * Time-window aggregate that emits per-feature statistics for all tuples in the window.
 *
 * <p>The output is a {@link ListTuple}. Each contained tuple represents one input field and uses the
 * window start timestamp. The tuple key is the window key plus the feature name, and its fields are
 * count, mean, std, median, p05, p25, p75, p95, p99 for that feature.
 */
public final class StatsTimeWindow extends BaseTimeWindowAdd<Tuple, ListTuple> {

    private final TupleFeatureStats.Accumulator statsAccumulator = new TupleFeatureStats.Accumulator();

    @Override
    public TimeWindowAdd<Tuple, ListTuple> factory() {
        return new StatsTimeWindow();
    }

    @Override
    public void add(Tuple tuple) {
        statsAccumulator.add(tuple);
    }

    @Override
    public ListTuple getAggregatedResult() {
        String outputKey = key == null ? "" : key;
        List<Tuple> tuples = new ArrayList<>();
        List<TupleFeatureStats.FeatureStats> features = statsAccumulator.toStats().features();
        for (int i = 0; i < features.size(); i++) {
            String featureName = "f" + (i + 1);
            tuples.add(new Tuple(
                    startTimestamp,
                    featureKey(outputKey, featureName),
                    fieldsFor(features.get(i))));
        }
        return new ListTuple(startTimestamp, outputKey, tuples);
    }

    private static String featureKey(String outputKey, String featureName) {
        if (outputKey.isEmpty()) {
            return featureName;
        }
        return outputKey + ":" + featureName;
    }

    private static double[] fieldsFor(TupleFeatureStats.FeatureStats feature) {
        return new double[] {
                feature.count(),
                feature.mean(),
                feature.std(),
                feature.median(),
                feature.p05(),
                feature.p25(),
                feature.p75(),
                feature.p95(),
                feature.p99()
        };
    }
}
