package usecase.analysis.performance;

import component.sink.SinkFunction;
import usecase.common.Tuple;

final class LatencyRecordingSinkFunction implements SinkFunction<Tuple> {

    private final SecondStatsRecorder stats;
    private boolean enabled;

    LatencyRecordingSinkFunction(SecondStatsRecorder stats) {
        this.stats = stats;
    }

    @Override
    public void accept(Tuple tuple) {
        if (tuple == null) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        long latencyMillis = Math.max(0L, nowMillis - tuple.getStimulus());
        stats.recordOutput(nowMillis, latencyMillis);
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void disable() {
        enabled = false;
    }
}
