package usecase.lcl;

import query.utils.TimestampGroupFieldShuffleFunction;
import usecase.common.Tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TimestampGroupShuffleSmokeTest {

    private TimestampGroupShuffleSmokeTest() {
    }

    public static void main(String[] args) {
        testFunctionGroupingAndFlush();
        testDecreasingTimestampFails();
        System.exit(0);
    }

    private static void testFunctionGroupingAndFlush() {
        List<Tuple> input = List.of(
                new Tuple(1L, "a", 1.0, 10.0),
                new Tuple(1L, "b", 2.0, 20.0),
                new Tuple(1L, "c", 3.0, 30.0),
                new Tuple(2L, "d", 4.0, 40.0),
                new Tuple(3L, "e", 5.0, 50.0),
                new Tuple(3L, "f", 6.0, 60.0));

        TimestampGroupFieldShuffleFunction function = new TimestampGroupFieldShuffleFunction("f2", 123L);
        List<Tuple> output = new ArrayList<>();
        for (Tuple tuple : input) {
            output.addAll(function.apply(tuple));
        }
        output.addAll(function.flush());

        require(output.size() == input.size(), "Expected one output per input tuple");
        for (int i = 0; i < input.size(); i++) {
            require(output.get(i).getTimestamp() == input.get(i).getTimestamp(), "Timestamp order changed at " + i);
            require(output.get(i).getKey().equals(input.get(i).getKey()), "Key order changed at " + i);
        }
        require(sameFieldMultiset(input, output, 1L), "Timestamp 1 field multiset changed");
        require(sameFieldMultiset(input, output, 2L), "Timestamp 2 field multiset changed");
        require(sameFieldMultiset(input, output, 3L), "Timestamp 3 field multiset changed");
    }

    private static void testDecreasingTimestampFails() {
        TimestampGroupFieldShuffleFunction function = new TimestampGroupFieldShuffleFunction("f2", 123L);
        function.apply(new Tuple(2L, "a", 1.0, 10.0));
        try {
            function.apply(new Tuple(1L, "b", 2.0, 20.0));
            throw new IllegalStateException("Expected decreasing timestamp to fail");
        } catch (IllegalArgumentException expected) {
            // Expected: the operator assumes event-time sorted streams.
        }
    }

    private static boolean sameFieldMultiset(List<Tuple> input, List<Tuple> output, long timestamp) {
        return fieldValuesAt(input, timestamp).equals(fieldValuesAt(output, timestamp));
    }

    private static List<Double> fieldValuesAt(List<Tuple> tuples, long timestamp) {
        return tuples.stream()
                .filter(tuple -> tuple.getTimestamp() == timestamp)
                .map(tuple -> tuple.lookup("f2"))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
