package usecase.common.analysis;

import java.util.List;
import usecase.common.Tuple;

@FunctionalInterface
public interface TupleMainQuery {

    QueryResult process(List<Tuple> inputStream, String queryId);
}
