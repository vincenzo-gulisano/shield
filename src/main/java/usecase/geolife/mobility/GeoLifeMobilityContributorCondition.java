package usecase.geolife.mobility;

import java.util.List;
import java.util.Set;
import usecase.common.Tuple;
import usecase.common.provenance.SourceContributorLinkageCondition;

/**
 * Provenance-backed GeoLife mobility condition used by contributor-fork experiments.
 *
 * <p>A tuple is marked as a contributor if the provenance-transformed baseline GeoLife mobility
 * query shows that the tuple contributes to any final hotspot output. The condition is keyed by
 * tuple linkage id, so it identifies original input points independently of later anonymization
 * copies or value changes.
 */
public final class GeoLifeMobilityContributorCondition {

    public static final String CONDITION_ID = "c_geolife_mobility_contributor";

    private static final SourceContributorLinkageCondition CONDITION =
            new SourceContributorLinkageCondition(CONDITION_ID);

    private GeoLifeMobilityContributorCondition() {
    }

    public static void initializeFromProvenance(
            List<Tuple> inputTuples,
            GeoLifeMobilityMainQuery.Settings querySettings) {
        if (inputTuples == null || inputTuples.isEmpty()) {
            throw new IllegalArgumentException("inputTuples cannot be null or empty");
        }
        List<Tuple> provenanceInput = inputTuples.stream().map(Tuple::new).toList();
        GeoLifeMobilityMainQuery.ProvenanceQueryResult provenance =
                GeoLifeMobilityMainQuery.processWithProvenance(
                        provenanceInput,
                        "geolife-mobility-contributor-condition",
                        querySettings);
        CONDITION.initializeFromProvenanceOutputs(provenance.outputTuples());
    }

    public static boolean isContributor(Tuple tuple) {
        return CONDITION.isContributor(tuple);
    }

    public static int contributorCount() {
        return CONDITION.contributorCount();
    }

    public static Set<Long> contributorLinkageIds() {
        return CONDITION.contributorLinkageIds();
    }
}
