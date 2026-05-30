package problem.utils;

// Defines the set of available privacy metrics that can be used in the optimization problem
public enum PrivacyMetricChoice {

    // The metric combining k-anonymity with a cardinality penalty
    K_ANONYMITY_CARDINALITY,

    // The k-anonymity metric with cardinality penalty, the privacy score is calculated with the maximum stddev
    K_ANONYMITY_CARDINALITY_MAX,

    // The k-anonymity metric with cardinality penalty, the privacy score is calculated with the q99 stddev
    K_ANONYMITY_CARDINALITY_Q99,

    // Expected-success linkage attack: true id in the top-k nearest candidates contributes 1/k risk
    LINKAGE_ATTACK_EXPECTED_SUCCESS,

    // Top-k shortlist linkage attack: true id in the top-k nearest candidates contributes full risk
    LINKAGE_ATTACK_TOP_K_CONTAINMENT
}
