package usecase.lcl.flow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import usecase.common.Tuple;

/**
 * Hardcoded LCL-flow condition used by the contributor-fork experiment.
 *
 * <p>A tuple is marked as a contributor if, in the baseline LCL-flow query, the tuple passes the
 * active-day filter, passes the corresponding tariff alert filter, and belongs to a day/tariff
 * alert group that survives the final count filter. The condition is intentionally keyed by the
 * tuple linkage id, so it identifies the original input records that contribute to semantic
 * outputs.
 */
public final class LclFlowContributorCondition {

    public static final String CONDITION_ID = "c_lcl_flow_contributor";

    private static final Set<Long> CONTRIBUTOR_LINKAGE_IDS = computeContributorLinkageIds();

    private LclFlowContributorCondition() {
    }

    public static boolean isContributor(Tuple tuple) {
        return tuple != null
                && tuple.hasLinkageId()
                && CONTRIBUTOR_LINKAGE_IDS.contains(tuple.getLinkageId());
    }

    public static int contributorCount() {
        return CONTRIBUTOR_LINKAGE_IDS.size();
    }

    public static Set<Long> contributorLinkageIds() {
        return CONTRIBUTOR_LINKAGE_IDS;
    }

    private static Set<Long> computeContributorLinkageIds() {
        List<Tuple> tuples = LclFlowTupleReader.loadUnchecked(LclFlowTupleReader.DEFAULT_RESOURCE);
        LclFlowAllFieldsMainQuery.Settings settings = LclFlowAllFieldsMainQuery.Settings.defaults();
        Map<GroupKey, Set<Long>> alertCandidatesByGroup = new LinkedHashMap<>();

        for (Tuple tuple : tuples) {
            if (!tuple.hasLinkageId() || !passesActiveDayFilter(tuple, settings)) {
                continue;
            }

            boolean stdTariff = tuple.getField("f1") == 0d;
            boolean alert = stdTariff
                    ? LclFlowAllFieldsMainQuery.stdDailyLoadRisk(tuple, settings) >= settings.stdAlertThreshold()
                    : LclFlowAllFieldsMainQuery.touEveningPeakRisk(tuple, settings) >= settings.touAlertThreshold();
            if (!alert) {
                continue;
            }

            GroupKey groupKey = new GroupKey(tuple.getTimestamp(), stdTariff ? "tariff_0" : "tariff_1");
            alertCandidatesByGroup
                    .computeIfAbsent(groupKey, ignored -> new LinkedHashSet<>())
                    .add(tuple.getLinkageId());
        }

        Set<Long> contributors = new LinkedHashSet<>();
        for (Set<Long> groupLinkageIds : alertCandidatesByGroup.values()) {
            if (groupLinkageIds.size() >= settings.minAlertHouseholds()) {
                contributors.addAll(groupLinkageIds);
            }
        }
        return Set.copyOf(contributors);
    }

    private static boolean passesActiveDayFilter(Tuple tuple, LclFlowAllFieldsMainQuery.Settings settings) {
        return tuple.getField("f2") >= settings.minDailyKwh()
                && tuple.getField("f11") < settings.maxZeroHalfHours();
    }

    private record GroupKey(long timestamp, String key) {
    }
}
