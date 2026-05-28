package usecase.forkjoin.synthetic;

import common.util.backoff.InactiveBackoff;
import component.operator.Operator;
import component.operator.in1.aggregate.BaseTimeWindowAdd;
import component.operator.in1.aggregate.TimeWindowAdd;
import component.operator.in1.aggregate.Window;
import component.operator.in1.map.MapFunction;
import component.operator.router.RouterOperator;
import component.operator.union.UnionOperator;
import component.sink.Sink;
import component.source.Source;
import component.source.SourceFunction;
import metrics.performance.utils.StreamStatsWindow;
import query.Query;
import usecase.common.CollectionSourceFactory;
import usecase.common.Tuple;
import usecase.forkjoin.synthetic.OneCoreSyntheticTupleCsvGenerator;

import java.util.*;

public class MainQuery {

    // Record to contain the final results events and the collected performance
    // metrics
    public record QueryResult(List<Tuple> outputAggregatedStats, List<Tuple> outputOutliers) {
    }

    public static QueryResult process(List<Tuple> inputStream, String queryId) {

        final List<Tuple> outputAggregatedStats = Collections.synchronizedList(new ArrayList<>());
        final List<Tuple> outputOutliers = Collections.synchronizedList(new ArrayList<>());

        Query query = new Query();

        // Create and add a source that reads from the provided in-memory list
        Source<Tuple> inputSource = query.addBaseSource("I-" + queryId, CollectionSourceFactory.fromList(inputStream));

        query.<Tuple, Tuple>addMapOperator("M-" + queryId, t -> {
            // Cohort is based on gender (f1) and age (f2)
            double age = t.getField("f2");
            int gender = (int) t.getField("f1");

            String ageBand;
            if (age < 18) {
                ageBand = "A00_17";
            } else if (age < 35) {
                ageBand = "A18_34";
            } else if (age < 50) {
                ageBand = "A35_49";
            } else if (age < 65) {
                ageBand = "A50_64";
            } else {
                ageBand = "A65_PLUS";
            }

            String cohort = ageBand + "_G" + gender;
            return new Tuple(t.getStimulus(), t.getTimestamp(), cohort, t.getFields());
        });

        RouterOperator<Tuple> router = query.addRouterOperator("router-" + queryId);

        // Filter for branch 1
        // Operator<Tuple, Tuple> b1f =
        query.addTimeAggregateOperator("StatsA-"+queryId,1,1, new BaseTimeWindowAdd<Tuple,Tuple>() {

            @Override
            public TimeWindowAdd<Tuple, Tuple> factory() {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException("Unimplemented method 'factory'");
            }

            @Override
            public void add(Tuple arg0) {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException("Unimplemented method 'add'");
            }

            @Override
            public Tuple getAggregatedResult() {
                // TODO Auto-generated method stub
                throw new UnsupportedOperationException("Unimplemented method 'getAggregatedResult'");
            }
            
        })

        // Final Sink that adds every valid event to the results list
        Sink<Tuple> sink = query.addBaseSink("o1-" + queryId, event -> {
            outputAggregatedStats.add(event);
        });

        // // Connect the pipeline components
        // query.connect(inputSource, recorderAfterSource)
        // .connect(recorderAfterSource, router)
        // .connect(router, b1f)
        // .connect(router, b2f)
        // .connect(b1f, recorderAfterBranch1)
        // .connect(b2f, recorderAfterBranch2)
        // .connect(recorderAfterBranch1, union, InactiveBackoff.INSTANCE)
        // .connect(recorderAfterBranch2, union, InactiveBackoff.INSTANCE)
        // .connect(union, sink);

        query.activate();

        while (sink.isEnabled()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        query.deActivate();

        return new QueryResult(outputAggregatedStats, outputOutliers);
    }

    private static final class StatsTimeWindow extends BaseTimeWindowAdd<Tuple, Tuple> {

        private static final int STATS_PER_FIELD = 9;

        private final List<List<Double>> sortedValuesByField = new ArrayList<>();
        private int numFields = -1;

        @Override
        public TimeWindowAdd<Tuple, Tuple> factory() {
            return new StatsTimeWindow();
        }

        @Override
        public void add(Tuple tuple) {
            if (tuple == null) {
                return;
            }
            initializeIfNeeded(tuple.getNumFields());
            if (tuple.getNumFields() != numFields) {
                throw new IllegalArgumentException(
                        "Expected " + numFields + " fields, found " + tuple.getNumFields());
            }
            for (int i = 0; i < numFields; i++) {
                double value = tuple.getField("f" + (i + 1));
                if (!Double.isNaN(value)) {
                    insertSorted(sortedValuesByField.get(i), value);
                }
            }
        }

        @Override
        public Tuple getAggregatedResult() {
            String outputKey = key == null ? "" : key;
            if (numFields < 0) {
                return new Tuple(startTimestamp, outputKey);
            }

            double[] stats = new double[numFields * STATS_PER_FIELD];
            int outIndex = 0;
            for (List<Double> values : sortedValuesByField) {
                stats[outIndex++] = values.size();
                stats[outIndex++] = mean(values);
                stats[outIndex++] = std(values);
                stats[outIndex++] = percentile(values, 0.50);
                stats[outIndex++] = percentile(values, 0.05);
                stats[outIndex++] = percentile(values, 0.25);
                stats[outIndex++] = percentile(values, 0.75);
                stats[outIndex++] = percentile(values, 0.95);
                stats[outIndex++] = percentile(values, 0.99);
            }
            return new Tuple(startTimestamp, outputKey, stats);
        }

        private void initializeIfNeeded(int newNumFields) {
            if (numFields >= 0) {
                return;
            }
            numFields = newNumFields;
            for (int i = 0; i < numFields; i++) {
                sortedValuesByField.add(new ArrayList<>());
            }
        }

        private static void insertSorted(List<Double> values, double value) {
            int index = Collections.binarySearch(values, value);
            if (index < 0) {
                index = -index - 1;
            }
            values.add(index, value);
        }

        private static double mean(List<Double> values) {
            if (values.isEmpty()) {
                return Double.NaN;
            }
            double sum = 0.0;
            for (double value : values) {
                sum += value;
            }
            return sum / values.size();
        }

        private static double std(List<Double> values) {
            if (values.size() < 2) {
                return 0.0;
            }
            double mean = mean(values);
            double sumSquaredDiffs = 0.0;
            for (double value : values) {
                double diff = value - mean;
                sumSquaredDiffs += diff * diff;
            }
            return Math.sqrt(sumSquaredDiffs / (values.size() - 1));
        }

        private static double percentile(List<Double> sortedValues, double p) {
            if (sortedValues.isEmpty()) {
                return Double.NaN;
            }
            if (sortedValues.size() == 1) {
                return sortedValues.get(0);
            }
            double position = p * (sortedValues.size() - 1);
            int lowerIndex = (int) Math.floor(position);
            int upperIndex = (int) Math.ceil(position);
            if (lowerIndex == upperIndex) {
                return sortedValues.get(lowerIndex);
            }
            double weight = position - lowerIndex;
            return sortedValues.get(lowerIndex) * (1.0 - weight)
                    + sortedValues.get(upperIndex) * weight;
        }
        
    }

}
