package query.utils;

import usecase.common.Tuple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Buffers tuples with the same timestamp and permutes one field inside that timestamp group.
 *
 * <p>The operator preserves stream cardinality and output order: every input tuple produces one
 * output tuple, and output tuples are emitted in the same timestamp/key order as the input group.
 * Only the selected field values are permuted across tuples sharing the same timestamp.
 */
public class TimestampGroupFieldShuffleFunction implements FlushableFlatMapFunction<Tuple, Tuple> {

    private final String field;
    private final long seed;
    private final List<Tuple> currentGroup = new ArrayList<>();
    private Long currentTimestamp;
    private boolean enabled = true;

    public TimestampGroupFieldShuffleFunction(String field, long seed) {
        this.field = Objects.requireNonNull(field, "field");
        this.seed = seed;
    }

    @Override
    public List<Tuple> apply(Tuple in) {
        if (!enabled || in == null) {
            return Collections.emptyList();
        }

        long timestamp = in.getTimestamp();
        if (currentTimestamp == null) {
            currentTimestamp = timestamp;
            currentGroup.add(new Tuple(in));
            return Collections.emptyList();
        }

        if (timestamp < currentTimestamp) {
            throw new IllegalArgumentException("TimestampGroupFieldShuffleFunction requires non-decreasing timestamps");
        }

        if (timestamp == currentTimestamp) {
            currentGroup.add(new Tuple(in));
            return Collections.emptyList();
        }

        // A larger timestamp closes the previous group. Emitting the whole group at this point
        // preserves tuple count and order while guaranteeing that all same-timestamp values were
        // available before the field permutation was applied.
        List<Tuple> out = emitCurrentGroup();
        currentTimestamp = timestamp;
        currentGroup.add(new Tuple(in));
        return out;
    }

    @Override
    public List<Tuple> flush() {
        if (!enabled) {
            clearState();
            return Collections.emptyList();
        }

        // End-of-input may leave one timestamp group buffered. Flush emits it so the operator
        // cannot drop the final group and therefore keeps one output tuple per input tuple.
        return emitCurrentGroup();
    }

    @Override
    public void enable() {
        enabled = true;
        clearState();
    }

    @Override
    public void disable() {
        enabled = false;
        clearState();
    }

    private List<Tuple> emitCurrentGroup() {
        if (currentGroup.isEmpty()) {
            currentTimestamp = null;
            return Collections.emptyList();
        }

        List<Tuple> out = new ArrayList<>(currentGroup.size());
        List<Double> values = new ArrayList<>(currentGroup.size());
        for (Tuple tuple : currentGroup) {
            out.add(new Tuple(tuple));
            values.add(tuple.lookup(field));
        }

        if (values.size() > 1) {
            List<Double> shuffledValues = new ArrayList<>(values);
            Collections.shuffle(shuffledValues, groupRandom(currentTimestamp, values.size()));
            if (shuffledValues.equals(values)) {
                Collections.rotate(shuffledValues, 1);
            }
            for (int i = 0; i < out.size(); i++) {
                out.get(i).set(field, shuffledValues.get(i));
            }
        }

        clearState();
        return out;
    }

    private Random groupRandom(long timestamp, int size) {
        long mixed = seed;
        mixed ^= Long.rotateLeft(timestamp, 21);
        mixed ^= Long.rotateLeft(size, 37);
        return new Random(mixed);
    }

    private void clearState() {
        currentGroup.clear();
        currentTimestamp = null;
    }
}
