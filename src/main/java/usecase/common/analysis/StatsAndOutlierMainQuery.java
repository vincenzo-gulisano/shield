package usecase.common.analysis;

import component.operator.Operator;
import component.operator.router.RouterOperator;
import component.sink.Sink;
import component.source.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import query.Query;
import query.LiebreContext;
import usecase.common.CollectionSourceFactory;
import usecase.common.ListTuple;
import usecase.common.Tuple;

public final class StatsAndOutlierMainQuery {

    static {
        LiebreContext.setSingleQueryExecution(false);
    }

    private StatsAndOutlierMainQuery() {
    }

    public static QueryResult process(
            List<Tuple> inputStream,
            String queryId,
            Function<Tuple, Tuple> analysisKeyMapper,
            long windowSizeMillis,
            long windowSlideMillis) {

        final List<Tuple> outputAggregatedStats = Collections.synchronizedList(new ArrayList<>());
        final List<Tuple> outputOutliers = Collections.synchronizedList(new ArrayList<>());

        Query query = new Query();
        Source<Tuple> inputSource = query.addBaseSource("I-" + queryId, CollectionSourceFactory.fromList(inputStream));

        Operator<Tuple, Tuple> keyMapper = query.<Tuple, Tuple>addMapOperator(
                "M-" + queryId,
                analysisKeyMapper::apply);

        RouterOperator<Tuple> router = query.addRouterOperator("router-" + queryId);

        Operator<Tuple, ListTuple> statsAggregate = query.addTimeAggregateOperator(
                "StatsA-" + queryId, windowSizeMillis, windowSlideMillis, new StatsTimeWindow());
        Operator<ListTuple, Tuple> statsUnpack = query.addFlatMapOperator(
                "StatsAUnpack-" + queryId, lt -> lt.getTuples().stream().toList());

        Operator<Tuple, ListTuple> outlierAggregate = query.addTimeAggregateOperator(
                "OutlierA-" + queryId, windowSizeMillis, windowSlideMillis, new OutlierTimeWindow());
        Operator<ListTuple, Tuple> outlierUnpack = query.addFlatMapOperator(
                "OutlierAUnpack-" + queryId, lt -> lt.getTuples().stream().toList());

        Sink<Tuple> statsSink = query.addBaseSink("O1-" + queryId, event -> {
            if (event != null) {
                outputAggregatedStats.add(event);
            }
        });

        Sink<Tuple> outlierSink = query.addBaseSink("O2-" + queryId, event -> {
            if (event != null) {
                outputOutliers.add(event);
            }
        });

        query.connect(inputSource, keyMapper)
                .connect(keyMapper, router)
                .connect(router, statsAggregate)
                .connect(statsAggregate, statsUnpack)
                .connect(router, outlierAggregate)
                .connect(outlierAggregate, outlierUnpack)
                .connect(statsUnpack, statsSink)
                .connect(outlierUnpack, outlierSink);

        query.activate();

        while (statsSink.isEnabled() || outlierSink.isEnabled()) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        query.deActivate();

        return new QueryResult(outputAggregatedStats, outputOutliers);
    }
}
