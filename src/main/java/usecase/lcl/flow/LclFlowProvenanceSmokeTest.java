package usecase.lcl.flow;

import experimental.provenance.GenealogTraverser;
import experimental.provenance.GenealogTuple;
import experimental.provenance.GenealogTupleType;
import experimental.provenance.UIDFactory;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import usecase.common.Tuple;

public final class LclFlowProvenanceSmokeTest {

    private LclFlowProvenanceSmokeTest() {
    }

    public static void main(String[] args) {
        try {
            run();
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run() throws IOException {
        List<Tuple> input = LclFlowTupleReader.readDefaultResource();
        System.out.println("Running baseline LCL flow query");
        LclFlowAllFieldsMainQuery.QueryResult baseline =
                LclFlowAllFieldsMainQuery.process(input, "provenance-baseline");
        System.out.println("Running provenance-transformed LCL flow query");
        boolean uidStateBeforeProvenance = UIDFactory.INSTANCE.isUIDsEnabled();
        LclFlowAllFieldsMainQuery.ProvenanceQueryResult provenance =
                LclFlowAllFieldsMainQuery.processWithProvenance(input, "provenance-smoke");
        require(
                UIDFactory.INSTANCE.isUIDsEnabled() == uidStateBeforeProvenance,
                "Provenance query did not restore UID generation state");
        System.out.println("Checking provenance graph");

        require(
                baseline.outputTuples().size() == provenance.outputTuples().size(),
                "Provenance query changed output count: "
                        + provenance.outputTuples().size()
                        + " vs "
                        + baseline.outputTuples().size());
        System.out.println("Output count matches: " + provenance.outputTuples().size());

        requireSameOutputs(baseline.outputTuples(), provenance.outputTuples());
        System.out.println("Output values match");

        long maxTimestamp = provenance.outputTuples().stream().mapToLong(Tuple::getTimestamp).max().orElseThrow();
        int finalTimestampOutputs = 0;
        int totalSourceContributors = 0;
        Set<Long> provenanceContributorLinkageIds = new LinkedHashSet<>();
        for (Tuple output : provenance.outputTuples()) {
            require(
                    output.getUID() != null,
                    "Provenance output without UID: type=" + output.type
                            + " timestamp=" + output.getTimestamp()
                            + " key=" + output.getKey());
            require(output.type == GenealogTupleType.AGGREGATE, "Expected aggregate provenance output");
            validateAggregateChain(output);
            Set<GenealogTuple> contributors = GenealogTraverser.INSTANCE.process(output);
            require(!contributors.isEmpty(), "Output without source contributors");
            for (GenealogTuple contributor : contributors) {
                require(contributor.type == GenealogTupleType.SOURCE, "Contributor is not a source tuple");
                require(contributor instanceof Tuple, "Contributor is not an LCL tuple");
                Tuple tuple = (Tuple) contributor;
                require(tuple.hasLinkageId(), "Contributor tuple without linkage id");
                provenanceContributorLinkageIds.add(tuple.getLinkageId());
            }
            totalSourceContributors += contributors.size();
            if (output.getTimestamp() == maxTimestamp) {
                finalTimestampOutputs++;
            }
        }
        require(finalTimestampOutputs > 0, "Expected provenance for final flushed timestamp outputs");
        require(
                provenanceContributorLinkageIds.equals(LclFlowContributorCondition.contributorLinkageIds()),
                "Transformed provenance contributors differ from ad-hoc contributors: transformed="
                        + provenanceContributorLinkageIds.size()
                        + " adHoc="
                        + LclFlowContributorCondition.contributorCount());

        System.out.printf(
                "lclFlowProvenanceSmoke outputs=%d finalTimestampOutputs=%d sourceContributorLinks=%d "
                        + "uniqueContributors=%d semantics=%.3f%n",
                provenance.outputTuples().size(),
                finalTimestampOutputs,
                totalSourceContributors,
                provenanceContributorLinkageIds.size(),
                1.0d);
        runTiming(input);
    }

    private static void runTiming(List<Tuple> input) {
        int runs = 3;
        for (int run = 1; run <= runs; run++) {
            int runIndex = run;
            List<Tuple> normalInput = copyInput(input);
            long normalMillis = timeMillis(() ->
                    LclFlowAllFieldsMainQuery.process(normalInput, "timing-normal-" + runIndex));

            List<Tuple> provenanceInput = copyInput(input);
            long provenanceMillis = timeMillis(() ->
                    LclFlowAllFieldsMainQuery.processWithProvenance(provenanceInput, "timing-provenance-" + runIndex));

            double ratio = (double) provenanceMillis / Math.max(1d, normalMillis);
            System.out.printf(
                    "timingRun=%d normalMs=%d provenanceMs=%d provenanceOverNormal=%.2f%n",
                    run,
                    normalMillis,
                    provenanceMillis,
                    ratio);
        }
    }

    private static List<Tuple> copyInput(List<Tuple> input) {
        return input.stream().map(Tuple::new).toList();
    }

    private static long timeMillis(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
    }

    private static void requireSameOutputs(List<Tuple> baseline, List<Tuple> provenance) {
        require(baseline.size() < 1_000, "Unexpectedly large smoke output: " + baseline.size());
        Map<OutputKey, Tuple> baselineByKey = new HashMap<>();
        for (Tuple expected : baseline) {
            OutputKey key = new OutputKey(expected.getTimestamp(), expected.getKey());
            require(baselineByKey.put(key, expected) == null, "Duplicate baseline output key: " + key);
        }
        for (Tuple actual : provenance) {
            OutputKey key = new OutputKey(actual.getTimestamp(), actual.getKey());
            Tuple expected = baselineByKey.remove(key);
            require(expected != null, "Unexpected provenance output key: " + key);
            require(expected.getNumFields() == actual.getNumFields(), "Provenance changed output field count");
            for (int fieldIndex = 1; fieldIndex <= expected.getNumFields(); fieldIndex++) {
                String field = "f" + fieldIndex;
                require(
                        Double.compare(expected.getField(field), actual.getField(field)) == 0,
                        "Provenance changed output field " + field);
            }
        }
        require(baselineByKey.isEmpty(), "Provenance query missed output keys: " + baselineByKey.keySet());
    }

    private static void validateAggregateChain(GenealogTuple output) {
        require(output.U2 != null, "Aggregate output without first grouped tuple");
        require(output.U1 != null, "Aggregate output without last grouped tuple");
        Set<GenealogTuple> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        GenealogTuple current = output.U2;
        while (true) {
            require(current != null, "Aggregate tuple chain ended before reaching the last grouped tuple");
            require(seen.add(current), "Aggregate tuple chain contains a cycle before reaching the last grouped tuple");
            if (current == output.U1) {
                require(current.N == null, "Aggregate tuple chain last node points past the group");
                return;
            }
            current = current.N;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record OutputKey(long timestamp, String key) {
    }
}
