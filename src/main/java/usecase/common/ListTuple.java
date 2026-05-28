package usecase.common;

import common.tuple.BaseRichTuple;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link common.tuple.RichTuple} that carries a list of {@link Tuple} objects.
 *
 * <p>This is useful for Liebre operators whose output type must be a RichTuple, but where the
 * logical output is naturally multiple tuples, for example one aggregate-stat tuple per feature or
 * several outlier tuples for the same window.
 */
public class ListTuple extends BaseRichTuple {

    private final List<Tuple> tuples;

    public ListTuple(long stimulus, long timestamp, String key, List<? extends Tuple> tuples) {
        super(stimulus, timestamp, key);
        this.tuples = copyTuples(tuples);
    }

    public ListTuple(long timestamp, String key, List<? extends Tuple> tuples) {
        super(timestamp, key);
        this.tuples = copyTuples(tuples);
    }

    public ListTuple(long timestamp, List<? extends Tuple> tuples) {
        super(timestamp, "");
        this.tuples = copyTuples(tuples);
    }

    public List<Tuple> getTuples() {
        return tuples;
    }

    public int size() {
        return tuples.size();
    }

    public boolean isEmpty() {
        return tuples.isEmpty();
    }

    private static List<Tuple> copyTuples(List<? extends Tuple> tuples) {
        if (tuples == null) {
            throw new IllegalArgumentException("tuples cannot be null");
        }
        List<Tuple> copy = new ArrayList<>(tuples.size());
        for (Tuple tuple : tuples) {
            copy.add(new Tuple(tuple));
        }
        return List.copyOf(copy);
    }
}
