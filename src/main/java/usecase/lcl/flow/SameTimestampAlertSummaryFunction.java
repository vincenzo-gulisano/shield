package usecase.lcl.flow;

import experimental.provenance.ProvenanceTransformationContext;
import java.util.List;
import query.utils.FlushableFlatMapFunction;
import query.utils.ProvenanceAwareFlushableFlatMapFunction;
import usecase.common.Tuple;

/**
 * Stateful branch summarizer for the LCL flow query.
 *
 * <p>This function receives the household-level alert tuples of one tariff branch after
 * {@link LclFlowMainQuery} has rewritten their keys to a single branch key, e.g. {@code tariff_0}
 * or {@code tariff_1}. Therefore, all tuples with the same timestamp and key represent the
 * households that triggered an alert for the same day and tariff.
 *
 * <p>The input stream is expected to be sorted by event time. The function keeps only the currently
 * open {@code (timestamp, key)} group. It emits no tuple while a group is still open; when a new
 * group starts, it emits one summary tuple for the previous group. The final open group is emitted
 * by {@link #flush()} at end of input.
 *
 * <p>The emitted summary tuple uses the same timestamp and branch key as the group and stores:
 * <ul>
 *     <li>{@code f1}: tariff ({@code 0=Std}, {@code 1=ToU});</li>
 *     <li>{@code f2}: number of alert households in the group;</li>
 *     <li>{@code f3}: average daily kWh;</li>
 *     <li>{@code f4}: average maximum 30-minute kWh;</li>
 *     <li>{@code f5}: average evening-share kWh;</li>
 *     <li>{@code f6}: average load factor;</li>
 *     <li>{@code f7}: average severity score.</li>
 * </ul>
 */
final class SameTimestampAlertSummaryFunction implements ProvenanceAwareFlushableFlatMapFunction<Tuple, Tuple> {

    private final ProvenanceTransformationContext provenanceContext;
    private ProvenanceTransformationContext.TupleChain currentChain;
    private long currentTimestamp;
    private String currentKey;
    private double tariff;
    private long count;
    private double dailyKwhSum;
    private double max30MinSum;
    private double eveningShareSum;
    private double loadFactorSum;
    private double severitySum;
    private boolean hasCurrentGroup;

    SameTimestampAlertSummaryFunction() {
        this(null);
    }

    private SameTimestampAlertSummaryFunction(ProvenanceTransformationContext provenanceContext) {
        this.provenanceContext = provenanceContext;
    }

    @Override
    public FlushableFlatMapFunction<Tuple, Tuple> createProvenanceFunction(ProvenanceTransformationContext context) {
        return new SameTimestampAlertSummaryFunction(context);
    }

    @Override
    public List<Tuple> apply(Tuple in) {
        if (in == null) {
            return List.of();
        }
        if (!hasCurrentGroup) {
            startGroup(in);
            return List.of();
        }
        if (currentTimestamp == in.getTimestamp() && currentKey.equals(in.getKey())) {
            add(in);
            return List.of();
        }
        Tuple emitted = emitCurrentGroup();
        startGroup(in);
        return List.of(emitted);
    }

    /**
     * Emit the final open timestamp/key group at end of input.
     *
     * <p>The upstream branch stream is sorted by event time and then mapped to a single branch key,
     * so a timestamp/key group is complete as soon as a later timestamp arrives. This flush handles
     * only the final group, preserving one alert summary per non-empty day/tariff branch.
     */
    @Override
    public List<Tuple> flush() {
        if (!hasCurrentGroup) {
            return List.of();
        }
        Tuple emitted = emitCurrentGroup();
        reset();
        return List.of(emitted);
    }

    @Override
    public void enable() {
        reset();
    }

    @Override
    public void disable() {
        reset();
    }

    private void startGroup(Tuple tuple) {
        // Start a new timestamp/branch group. All accumulators are reset before adding the first
        // tuple so a group contains exactly the tuples seen with this timestamp and branch key.
        currentTimestamp = tuple.getTimestamp();
        currentKey = tuple.getKey();
        tariff = tuple.getField("f1");
        count = 0L;
        dailyKwhSum = 0d;
        max30MinSum = 0d;
        eveningShareSum = 0d;
        loadFactorSum = 0d;
        severitySum = 0d;
        currentChain = provenanceContext == null ? null : provenanceContext.newTupleChain();
        hasCurrentGroup = true;
        add(tuple);
    }

    private void add(Tuple tuple) {
        // The query has already filtered this tuple as an alert candidate. Here we only accumulate
        // branch-level daily statistics; no additional alert logic is applied in this function.
        if (currentChain != null) {
            currentChain.append(tuple);
        }
        count++;
        dailyKwhSum += tuple.getField("f2");
        max30MinSum += tuple.getField("f3");
        eveningShareSum += tuple.getField("f7");
        loadFactorSum += tuple.getField("f9");
        severitySum += severity(tuple);
    }

    private Tuple emitCurrentGroup() {
        // The output is a compact day/tariff alert tuple. The downstream count filter reads f2,
        // while semantic comparison sees the whole tuple as the observable alert output.
        Tuple out = new Tuple(
                currentTimestamp,
                currentKey,
                tariff,
                (double) count,
                dailyKwhSum / count,
                max30MinSum / count,
                eveningShareSum / count,
                loadFactorSum / count,
                severitySum / count);
        if (provenanceContext != null && currentChain != null && !currentChain.isEmpty()) {
            provenanceContext.markAggregate(out, currentChain.first(), currentChain.last());
        }
        return out;
    }

    private double severity(Tuple tuple) {
        // A simple load-shape severity score: daily consumption is amplified by evening share, then
        // combined with the maximum half-hour peak. It is descriptive, not a separate filter.
        return tuple.getField("f2") * (1d + tuple.getField("f7")) + tuple.getField("f3");
    }

    private void reset() {
        currentTimestamp = 0L;
        currentKey = null;
        tariff = 0d;
        count = 0L;
        dailyKwhSum = 0d;
        max30MinSum = 0d;
        eveningShareSum = 0d;
        loadFactorSum = 0d;
        severitySum = 0d;
        currentChain = null;
        hasCurrentGroup = false;
    }
}
