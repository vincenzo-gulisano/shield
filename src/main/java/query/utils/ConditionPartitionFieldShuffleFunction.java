package query.utils;

import usecase.common.Tuple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class ConditionPartitionFieldShuffleFunction implements FlushableFlatMapFunction<Tuple, Tuple> {

    private final String field;
    private final TupleConditionSpec condition;
    private final long seed;
    private final List<Tuple> currentGroup = new ArrayList<>();
    private Long currentTimestamp;

    public ConditionPartitionFieldShuffleFunction(String field, TupleConditionSpec condition, long seed) {
        this.field = Objects.requireNonNull(field, "field");
        this.condition = Objects.requireNonNull(condition, "condition");
        this.seed = seed;
    }

    @Override
    public List<Tuple> apply(Tuple in) {
        if (in == null) {
            return List.of();
        }

        long timestamp = in.getTimestamp();
        if (currentTimestamp == null) {
            currentTimestamp = timestamp;
            currentGroup.add(new Tuple(in));
            return List.of();
        }

        if (timestamp < currentTimestamp) {
            throw new IllegalArgumentException("ConditionPartitionFieldShuffleFunction requires non-decreasing timestamps");
        }

        if (timestamp == currentTimestamp) {
            currentGroup.add(new Tuple(in));
            return List.of();
        }

        List<Tuple> out = emitCurrentGroup();
        currentTimestamp = timestamp;
        currentGroup.add(new Tuple(in));
        return out;
    }

    @Override
    public List<Tuple> flush() {
        return emitCurrentGroup();
    }

    @Override
    public void enable() {
        clearState();
    }

    @Override
    public void disable() {
        clearState();
    }

    private List<Tuple> emitCurrentGroup() {
        if (currentGroup.isEmpty()) {
            currentTimestamp = null;
            return List.of();
        }

        List<Tuple> out = new ArrayList<>(currentGroup.size());
        List<Integer> truePositions = new ArrayList<>();
        List<Integer> falsePositions = new ArrayList<>();
        for (int i = 0; i < currentGroup.size(); i++) {
            Tuple tuple = currentGroup.get(i);
            out.add(new Tuple(tuple));
            if (condition.test(tuple)) {
                truePositions.add(i);
            } else {
                falsePositions.add(i);
            }
        }

        shuffleBucket(out, truePositions, 1L);
        shuffleBucket(out, falsePositions, 0L);
        clearState();
        return out;
    }

    private void shuffleBucket(List<Tuple> out, List<Integer> positions, long bucketSalt) {
        if (positions.size() <= 1) {
            return;
        }

        List<Double> values = new ArrayList<>(positions.size());
        for (int position : positions) {
            values.add(currentGroup.get(position).lookup(field));
        }

        List<Double> shuffledValues = new ArrayList<>(values);
        Collections.shuffle(shuffledValues, bucketRandom(bucketSalt, positions.size()));
        if (shuffledValues.equals(values)) {
            Collections.rotate(shuffledValues, 1);
        }

        for (int i = 0; i < positions.size(); i++) {
            out.get(positions.get(i)).set(field, shuffledValues.get(i));
        }
    }

    private Random bucketRandom(long bucketSalt, int bucketSize) {
        long mixed = seed;
        mixed ^= Long.rotateLeft(currentTimestamp, 17);
        mixed ^= Long.rotateLeft(bucketSalt, 31);
        mixed ^= Long.rotateLeft(bucketSize, 43);
        return new Random(mixed);
    }

    private void clearState() {
        currentGroup.clear();
        currentTimestamp = null;
    }
}
