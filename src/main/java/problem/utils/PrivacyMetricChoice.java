package problem.utils;

// Defines the set of available privacy metrics that can be used in the optimization problem
public enum PrivacyMetricChoice {

    // The metric combining k-anonymity with a cardinality penalty
    K_ANONYMITY_CARDINALITY,

    // The k-anonymity metric with cardinality penalty, the privacy score is calculated with the maximum stddev
    K_ANONYMITY_CARDINALITY_MAX,

    // The k-anonymity metric with cardinality penalty, the privacy score is calculated with the q99 stddev
    K_ANONYMITY_CARDINALITY_Q99
}