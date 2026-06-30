package usecase.lcl.flow;

import experimental.provenance.GenealogTraverser;
import experimental.provenance.GenealogTuple;
import experimental.provenance.GenealogTupleType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import usecase.common.Tuple;

/**
 * Provenance-backed LCL-flow condition used by the contributor-fork experiment.
 *
 * <p>A tuple is marked as a contributor if the provenance-transformed baseline LCL-flow query shows
 * that the tuple contributes to any final semantic output. The condition is keyed by tuple linkage
 * id, so it identifies original input records independently of later anonymization copies.
 */
public final class LclFlowContributorCondition {

    public static final String CONDITION_ID = "c_lcl_flow_contributor";

    private static volatile Set<Long> contributorLinkageIds;

    private LclFlowContributorCondition() {
    }

    public static void initializeFromProvenance(
            List<Tuple> inputTuples,
            LclFlowAllFieldsMainQuery.Settings querySettings) {
        if (inputTuples == null || inputTuples.isEmpty()) {
            throw new IllegalArgumentException("inputTuples cannot be null or empty");
        }
        List<Tuple> provenanceInput = inputTuples.stream().map(Tuple::new).toList();
        LclFlowAllFieldsMainQuery.ProvenanceQueryResult provenance =
                LclFlowAllFieldsMainQuery.processWithProvenance(
                        provenanceInput,
                        "lcl-flow-contributor-condition",
                        querySettings);
        contributorLinkageIds = sourceContributorLinkageIds(provenance.outputTuples());
    }

    public static boolean isContributor(Tuple tuple) {
        Set<Long> contributors = requireInitialized();
        return tuple != null
                && tuple.hasLinkageId()
                && contributors.contains(tuple.getLinkageId());
    }

    public static int contributorCount() {
        return requireInitialized().size();
    }

    public static Set<Long> contributorLinkageIds() {
        return requireInitialized();
    }

    static Set<Long> sourceContributorLinkageIds(List<Tuple> provenanceOutputs) {
        Set<Long> contributors = new LinkedHashSet<>();
        for (Tuple output : provenanceOutputs) {
            for (GenealogTuple contributor : GenealogTraverser.INSTANCE.process(output)) {
                if (contributor.type != GenealogTupleType.SOURCE) {
                    throw new IllegalStateException("Expected only source contributors, got " + contributor.type);
                }
                if (!(contributor instanceof Tuple tuple)) {
                    throw new IllegalStateException("Expected LCL tuple contributor, got " + contributor.getClass());
                }
                if (!tuple.hasLinkageId()) {
                    throw new IllegalStateException("Contributor tuple does not have a linkage id");
                }
                contributors.add(tuple.getLinkageId());
            }
        }
        return Set.copyOf(contributors);
    }

    private static Set<Long> requireInitialized() {
        Set<Long> contributors = contributorLinkageIds;
        if (contributors == null) {
            throw new IllegalStateException(
                    "LCL-flow contributor condition has not been initialized from provenance");
        }
        return contributors;
    }
}
