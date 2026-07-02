package usecase.lcl.flow;

import common.util.backoff.InactiveBackoff;
import java.util.List;
import stream.BackoffStreamFactory;
import usecase.common.Tuple;
import usecase.common.flow.InstrumentedStreamFactory;
import usecase.common.flow.StreamFlowInstrumentation;

public final class LclFlowInstrumentationSmokeTest {

    private LclFlowInstrumentationSmokeTest() {
    }

    public static void main(String[] args) {
        StreamFlowInstrumentation instrumentation = new StreamFlowInstrumentation(1_000L, 2_999L, 2);
        int row = instrumentation.registerStream("test-stream");
        instrumentation.record(row, new Tuple(1_000L, "a", 1d));
        instrumentation.record(row, new Tuple(1_100L, "a", 2d));
        instrumentation.record(row, new Tuple(1_200L, "b", 3d));
        instrumentation.record(row, new Tuple(2_500L, "a", 4d));

        StreamFlowInstrumentation.Snapshot snapshot = instrumentation.snapshot();
        require(snapshot.tupleCounts()[row][0] == 3L, "Expected 3 tuples in first bin");
        require(snapshot.keyCounts()[row][0] == 2L, "Expected 2 unique keys in first bin");
        require(snapshot.tupleCounts()[row][1] == 1L, "Expected 1 tuple in second bin");
        require(snapshot.keyCounts()[row][1] == 1L, "Expected key set to reset in second bin");

        requireThrows(() -> instrumentation.record(row, new Tuple(2_000L, "c", 5d)),
                "Expected decreasing timestamp to throw");
        requireThrows(() -> new InstrumentedStreamFactory(new BackoffStreamFactory(), instrumentation)
                        .newMWMRStream(List.of(), List.of(), 1, InactiveBackoff.INSTANCE),
                "Expected MWMR instrumentation to throw");

        System.out.println("LCL flow instrumentation smoke passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void requireThrows(Runnable runnable, String message) {
        try {
            runnable.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
