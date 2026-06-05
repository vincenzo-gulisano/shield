package usecase.lcl.flow;

import common.util.backoff.Backoff;
import component.StreamConsumer;
import component.StreamProducer;
import java.util.List;
import stream.MWMRStream;
import stream.Stream;
import stream.StreamFactory;

public final class InstrumentedStreamFactory implements StreamFactory {

    private final StreamFactory delegate;
    private final StreamFlowInstrumentation instrumentation;

    public InstrumentedStreamFactory(StreamFactory delegate, StreamFlowInstrumentation instrumentation) {
        this.delegate = delegate;
        this.instrumentation = instrumentation;
    }

    @Override
    public <T> Stream<T> newStream(
            StreamProducer<T> producer,
            StreamConsumer<T> consumer,
            int capacity,
            Backoff backoff) {
        Stream<T> stream = delegate.newStream(producer, consumer, capacity, backoff);
        int row = instrumentation.registerStream(streamName(producer, consumer, stream));
        return new InstrumentedStream<>(stream, instrumentation, row);
    }

    @Override
    public <T extends Comparable<? super T>> MWMRStream<T> newMWMRStream(
            List<? extends StreamProducer<T>> producers,
            List<? extends StreamConsumer<T>> consumers,
            int maxLevels,
            Backoff backoff) {
        throw new UnsupportedOperationException(
                "Stream flow instrumentation does not support MWMR streams yet");
    }

    private static String streamName(StreamProducer<?> producer, StreamConsumer<?> consumer, Stream<?> stream) {
        return stream.getId() + " (" + producer.getId() + " -> " + consumer.getId() + ")";
    }
}
