package usecase.common;

import common.util.Util;
import component.source.SourceFunction;
import java.util.List;
import java.util.Objects;

/**
 * Factory for Liebre sources backed by an in-memory list.
 *
 * <p>Use this when a query receives an already-loaded list of tuples/events and needs to feed it
 * into a Liebre {@link SourceFunction}. The source emits each list item once, then returns
 * {@code null} while reporting that the input is finished.
 */
public final class CollectionSourceFactory {

    private static final long DEFAULT_IDLE_SLEEP_MILLIS = 10L;

    private CollectionSourceFactory() {
    }

    /**
     * Create a source backed by {@code list}, using the default idle sleep after completion.
     */
    public static <T> SourceFunction<T> fromList(List<T> list) {
        return fromList(list, DEFAULT_IDLE_SLEEP_MILLIS);
    }

    /**
     * Create a source backed by {@code list}, using a caller-provided idle sleep after completion.
     *
     * <p>The idle sleep preserves the behavior of older query-local helpers: most main queries
     * sleep briefly after the list is exhausted, while some internal executors use zero sleep.
     */
    public static <T> SourceFunction<T> fromList(List<T> list, long idleSleepMillis) {
        Objects.requireNonNull(list, "list cannot be null");
        if (idleSleepMillis < 0) {
            throw new IllegalArgumentException("idleSleepMillis cannot be negative");
        }
        return new SourceFunction<>() {
            private int currentIndex = 0;
            private boolean isFinished = false;
            private boolean enabled;

            @Override
            public T get() {
                if (isFinished) {
                    Util.sleep(idleSleepMillis);
                    return null;
                }
                if (currentIndex < list.size()) {
                    T item = list.get(currentIndex);
                    currentIndex++;
                    return item;
                }
                isFinished = true;
                return null;
            }

            @Override
            public boolean isInputFinished() {
                return isFinished;
            }

            @Override
            public void enable() {
                this.enabled = true;
            }

            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public void disable() {
                this.enabled = false;
            }

            @Override
            public boolean canRun() {
                return !isFinished;
            }
        };
    }
}
