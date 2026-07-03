package usecase.common.provenance;

import experimental.provenance.GenealogTraverser;
import experimental.provenance.GenealogTuple;
import experimental.provenance.GenealogTupleType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import query.utils.TupleConditionRegistry;
import query.utils.TupleConditionSpec;
import usecase.common.Tuple;

/**
 * Shared provenance-backed condition for original source tuples that contribute to final query
 * outputs.
 *
 * <p>The condition is keyed by tuple linkage id, so it can still recognize original source records
 * after anonymization operators copy or modify tuple values.
 */
public final class SourceContributorLinkageCondition {

    private final String conditionId;
    private volatile Set<Long> contributorLinkageIds;

    public SourceContributorLinkageCondition(String conditionId) {
        if (conditionId == null || conditionId.isBlank()) {
            throw new IllegalArgumentException("conditionId cannot be blank");
        }
        this.conditionId = conditionId;
        TupleConditionRegistry.register(new TupleConditionSpec(conditionId, null, this::isContributor));
    }

    public void initializeFromProvenanceOutputs(List<Tuple> provenanceOutputs) {
        contributorLinkageIds = sourceContributorLinkageIds(provenanceOutputs);
    }

    public boolean isContributor(Tuple tuple) {
        Set<Long> contributors = requireInitialized();
        return tuple != null
                && tuple.hasLinkageId()
                && contributors.contains(tuple.getLinkageId());
    }

    public int contributorCount() {
        return requireInitialized().size();
    }

    public Set<Long> contributorLinkageIds() {
        return requireInitialized();
    }

    public static Set<Long> sourceContributorLinkageIds(List<Tuple> provenanceOutputs) {
        Set<Long> contributors = new LinkedHashSet<>();
        for (Tuple output : provenanceOutputs) {
            for (GenealogTuple contributor : GenealogTraverser.INSTANCE.process(output)) {
                if (contributor.type != GenealogTupleType.SOURCE) {
                    throw new IllegalStateException("Expected only source contributors, got " + contributor.type);
                }
                if (!(contributor instanceof Tuple tuple)) {
                    throw new IllegalStateException("Expected Tuple contributor, got " + contributor.getClass());
                }
                if (!tuple.hasLinkageId()) {
                    throw new IllegalStateException("Contributor tuple does not have a linkage id");
                }
                contributors.add(tuple.getLinkageId());
            }
        }
        return Set.copyOf(contributors);
    }

    private Set<Long> requireInitialized() {
        Set<Long> contributors = contributorLinkageIds;
        if (contributors == null) {
            throw new IllegalStateException(
                    "Source contributor condition " + conditionId + " has not been initialized from provenance");
        }
        return contributors;
    }
}
