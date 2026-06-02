package usecase.common.analysis;

import java.util.List;
import usecase.common.Tuple;

public record QueryResult(List<Tuple> outputAggregatedStats, List<Tuple> outputOutliers) {
}
