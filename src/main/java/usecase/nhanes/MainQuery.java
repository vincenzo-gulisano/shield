package usecase.nhanes;

import component.operator.Operator;
import component.operator.router.RouterOperator;
import component.sink.Sink;
import component.source.Source;
import query.Query;
import usecase.common.CollectionSourceFactory;
import usecase.common.ListTuple;
import usecase.common.Tuple;
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

        Operator<Tuple, Tuple> cohortExtractor =  query.<Tuple, Tuple>addMapOperator("M-" + queryId, t -> {
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

        Operator<Tuple, ListTuple> agg2 = query.addTimeAggregateOperator("OutlierA-"+queryId,1,1, new OutlierTimeWindow());

        Operator<ListTuple, Tuple> agg2unpack = query.addFlatMapOperator("OutlierAUnpack-"+queryId, lt -> lt.getTuples().stream().toList());

        Sink<Tuple> sink1 = query.addBaseSink("O1-" + queryId, event -> {
            outputAggregatedStats.add(event);
        });

        Sink<Tuple> sink2 = query.addBaseSink("O2-" + queryId, event -> {
            outputOutliers.add(event);
        });

        // Connect the pipeline components
        query.connect(inputSource, cohortExtractor)
        .connect(cohortExtractor, router)
        .connect(router, agg1)
        .connect(agg1, agg1unpack)
        .connect(router, agg2)
        .connect(agg2, agg2unpack)
        .connect(agg1unpack, sink1)
        .connect(agg2unpack, sink2);

        query.activate();

        while (sink1.isEnabled() || sink2.isEnabled()) {
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
