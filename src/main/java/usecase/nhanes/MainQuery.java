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
import usecase.common.ListTuple;
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

        Operator<Tuple, ListTuple> agg1 = query.addTimeAggregateOperator("StatsA-"+queryId,1,1, new StatsTimeWindow());

        Operator<ListTuple, Tuple> agg1unpack = query.addFlatMapOperator("StatsAUnpack-"+queryId, lt -> lt.getTuples().stream().toList());

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

}
