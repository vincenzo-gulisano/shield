package usecase.lcl.flow;

import experimental.provenance.ProvenanceTransformationContext;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;
import query.utils.FlushableFlatMapFunction;
import query.utils.ProvenanceAwareFlushableFlatMapFunction;
import usecase.common.Tuple;

/**
 * Stateful branch summarizer for the all-fields LCL flow query.
 *
 * <p>The upstream query maps all alerting households in one tariff branch to the same branch key,
 * so each open {@code (timestamp, key)} group contains the households that triggered the same
 * benchmark alert for one day and one tariff.
 *
 * <p>The emitted tuple intentionally exposes every original quasi-identifier-derived feature to
 * the semantic comparison: tariff, alert count, averages of {@code f2..f11}, and the average
 * synthetic branch risk score. This makes anonymization changes to any input field more likely to
 * affect the main-query output.
 */
final class AllFieldsAlertSummaryFunction implements ProvenanceAwareFlushableFlatMapFunction<Tuple, Tuple> {

    private static final int SUMMED_INPUT_FIELDS = 10;

    private final ToDoubleFunction<Tuple> scoreFunction;
    private final ProvenanceTransformationContext provenanceContext;
    private final double[] featureSums;
    private ProvenanceTransformationContext.TupleChain currentChain;
    private long currentTimestamp;
    private String currentKey;
    private double tariff;
    private long count;
    private double scoreSum;
    private boolean hasCurrentGroup;

    AllFieldsAlertSummaryFunction(ToDoubleFunction<Tuple> scoreFunction) {
        this(scoreFunction, null);
    }

    private AllFieldsAlertSummaryFunction(
            ToDoubleFunction<Tuple> scoreFunction,
            ProvenanceTransformationContext provenanceContext) {
        if (scoreFunction == null) {
            throw new IllegalArgumentException("scoreFunction cannot be null");
        }
        this.scoreFunction = scoreFunction;
        this.provenanceContext = provenanceContext;
        this.featureSums = new double[SUMMED_INPUT_FIELDS];
    }

    @Override
    public FlushableFlatMapFunction<Tuple, Tuple> createProvenanceFunction(ProvenanceTransformationContext context) {
        return new AllFieldsAlertSummaryFunction(scoreFunction, context);
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
     * Emit the final open day/tariff group at end of input.
     *
     * <p>The input stream is event-time sorted, so every group except the final one is emitted when
     * a later timestamp arrives. This flush preserves the last alert summary instead of dropping it
     * when no later tuple exists to close the group naturally.
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
        currentTimestamp = tuple.getTimestamp();
        currentKey = tuple.getKey();
        tariff = tuple.getField("f1");
        count = 0L;
        scoreSum = 0d;
        Arrays.fill(featureSums, 0d);
        currentChain = provenanceContext == null ? null : provenanceContext.newTupleChain();
        hasCurrentGroup = true;
        add(tuple);
    }

    private void add(Tuple tuple) {
        if (currentChain != null) {
            currentChain.append(tuple);
        }
        count++;
        for (int i = 0; i < SUMMED_INPUT_FIELDS; i++) {
            featureSums[i] += tuple.getField("f" + (i + 2));
        }
        scoreSum += scoreFunction.applyAsDouble(tuple);
    }

    private Tuple emitCurrentGroup() {
        double[] fields = new double[13];
        fields[0] = tariff;
        fields[1] = count;
        for (int i = 0; i < SUMMED_INPUT_FIELDS; i++) {
            fields[i + 2] = featureSums[i] / count;
        }
        fields[12] = scoreSum / count;
        Tuple out = new Tuple(currentTimestamp, currentKey, fields);
        if (provenanceContext != null && currentChain != null && !currentChain.isEmpty()) {
            provenanceContext.markAggregate(out, currentChain.first(), currentChain.last());
        }
        return out;
    }

    private void reset() {
        currentTimestamp = 0L;
        currentKey = null;
        tariff = 0d;
        count = 0L;
        scoreSum = 0d;
        Arrays.fill(featureSums, 0d);
        currentChain = null;
        hasCurrentGroup = false;
    }
}
