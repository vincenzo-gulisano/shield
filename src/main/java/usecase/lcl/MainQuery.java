package usecase.lcl;

import java.time.Duration;
import java.util.List;
import usecase.common.Tuple;
import usecase.common.analysis.QueryResult;
import usecase.common.analysis.StatsAndOutlierMainQuery;

public class MainQuery {

    private static final long WINDOW_SIZE_MILLIS = Duration.ofDays(2).toMillis();
    private static final long WINDOW_SLIDE_MILLIS = Duration.ofDays(1).toMillis();
    private static final String POPULATION_KEY = "all_households";

    public static QueryResult process(List<Tuple> inputStream, String queryId) {
        return StatsAndOutlierMainQuery.process(
                inputStream,
                queryId,
                MainQuery::withPopulationKey,
                WINDOW_SIZE_MILLIS,
                WINDOW_SLIDE_MILLIS);
    }

    private static Tuple withPopulationKey(Tuple tuple) {
        return new Tuple(tuple.getStimulus(), tuple.getTimestamp(), POPULATION_KEY, tuple.getFields());
    }
}
