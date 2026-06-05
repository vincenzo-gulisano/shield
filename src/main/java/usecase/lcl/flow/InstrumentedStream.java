package usecase.lcl.flow;

import component.StreamConsumer;
import component.StreamProducer;
import java.util.List;
import stream.Stream;

final class InstrumentedStream<T> implements Stream<T> {

    private final Stream<T> delegate;
    private final StreamFlowInstrumentation instrumentation;
    private final int row;

    InstrumentedStream(Stream<T> delegate, StreamFlowInstrumentation instrumentation, int row) {
        this.delegate = delegate;
        this.instrumentation = instrumentation;
        this.row = row;
    }

    @Override
    public void addTuple(T tuple, int producerIndex) {
        delegate.addTuple(tuple, producerIndex);
        instrumentation.record(row, tuple);
    }

    @Override
    public boolean offer(T tuple, int producerIndex) {
        boolean accepted = delegate.offer(tuple, producerIndex);
        if (accepted) {
            instrumentation.record(row, tuple);
        }
        return accepted;
    }

    @Override
    public T getNextTuple(int consumerIndex) {
        return delegate.getNextTuple(consumerIndex);
    }

    @Override
    public T peek(int consumerIndex) {
        return delegate.peek(consumerIndex);
    }

    @Override
    public int remainingCapacity() {
        return delegate.remainingCapacity();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public List<? extends StreamProducer<T>> producers() {
        return delegate.producers();
    }

    @Override
    public List<? extends StreamConsumer<T>> consumers() {
        return delegate.consumers();
    }

    @Override
    public void resetArrivalTime() {
        delegate.resetArrivalTime();
    }

    @Override
    public double averageArrivalTime() {
        return delegate.averageArrivalTime();
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public boolean isFlushed() {
        return delegate.isFlushed();
    }

    @Override
    public void enable() {
        delegate.enable();
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public void disable() {
        delegate.disable();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getIndex() {
        return delegate.getIndex();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
