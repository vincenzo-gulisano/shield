package usecase.nhanes;

import java.util.List;
import usecase.common.Tuple;
import usecase.common.analysis.QueryResult;
import usecase.common.analysis.StatsAndOutlierMainQuery;

public class MainQuery {

    public static QueryResult process(List<Tuple> inputStream, String queryId) {
        return StatsAndOutlierMainQuery.process(inputStream, queryId, MainQuery::withCohortKey, 1L, 1L);
    }

    private static Tuple withCohortKey(Tuple tuple) {
        double age = tuple.getField("f2");
        int gender = (int) tuple.getField("f1");

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

        return new Tuple(tuple.getStimulus(), tuple.getTimestamp(), ageBand + "_G" + gender, tuple.getFields());
    }
}
