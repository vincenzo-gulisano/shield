package grammar.generator;

import grammar.generator.TypedGrammarGenerator.Options;

import java.util.List;

public final class TypedGrammarGeneratorSmokeTest {

    private static final List<FieldDefinition> LCL_FIELDS = List.of(
            FieldDefinition.withValues("f1", FieldType.NOMINAL_CATEGORICAL, "0", "1"),
            FieldDefinition.of("f2", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f3", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f4", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f5", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f6", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f7", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f8", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f9", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f10", FieldType.DISCRETE_NUMERIC),
            FieldDefinition.of("f11", FieldType.DISCRETE_NUMERIC));

    private TypedGrammarGeneratorSmokeTest() {
    }

    public static void main(String[] args) {
        assertTimestampAwareDefaults();
        assertQueryConditionAwareShape();
        assertQueryAwareContributorRootShape();
        assertAllOperatorContributorRootShape();
        System.exit(0);
    }

    private static void assertTimestampAwareDefaults() {
        String grammar = TypedGrammarGenerator.generate(LCL_FIELDS, Options.timestampAwareDefaults());
        require(rule(grammar, "<pipeline>").equals("<pipeline> ::= <sorted_pipeline>"),
                "Timestamp-aware grammar should start from <sorted_pipeline>");
        require(!rule(grammar, "<unsorted_pipeline>").contains("<timestamp_sensitive_operator>"),
                "Unsorted continuations must not include timestamp-sensitive operators");
        require(rule(grammar, "<timestamp_sensitive_operator>").contains("<map_timestamp_group_shuffle_continuous_numeric>"),
                "Timestamp-aware defaults should include timestamp-group shuffle");
    }

    private static void assertQueryConditionAwareShape() {
        String grammar = TypedGrammarGenerator.generate(LCL_FIELDS, Options.queryConditionAwareDefaults());
        require(rule(grammar, "<ordinary_operator>").contains("<filter_query_condition>"),
                "Query-aware grammar should include provenance-condition filters");
        require(rule(grammar, "<timestamp_sensitive_operator>").contains("<map_condition_pairwise_swap_continuous_numeric>"),
                "Query-aware grammar should include condition pairwise swaps");
        require(rule(grammar, "<sorted_fork>").contains("<query_condition_fork_sorted>"),
                "Query-aware grammar should include sorted query-condition forks");
        require(rule(grammar, "<unsorted_fork>").contains("<query_condition_fork_unsorted>"),
                "Query-aware grammar should include unsorted query-condition forks");
        require(!rule(grammar, "<unsorted_pipeline>").contains("<timestamp_sensitive_operator>"),
                "Query-aware unsorted continuations must not include timestamp-sensitive operators");
        require(!grammar.contains("<query_condition>") && !grammar.contains("c_f2_ge_1_0"),
                "Generated query-aware grammar should use only the implicit provenance condition");
    }

    private static void assertQueryAwareContributorRootShape() {
        String grammar = TypedGrammarGenerator.generate(
                LCL_FIELDS,
                Options.queryConditionAwareDefaults().withContributorRoot());
        require(rule(grammar, "<pipeline>").equals("<pipeline> ::= <contributor_root>"),
                "Forced contributor grammar should start from <contributor_root>");
        require(rule(grammar, "<contributor_root>").equals("<contributor_root> ::= <sorted_pipeline> <sorted_pipeline>"),
                "Forced contributor grammar should generate both branches");
        require(!grammar.contains("<empty_pipeline>"),
                "Forced contributor grammar should not generate one empty branch");
    }

    private static void assertAllOperatorContributorRootShape() {
        String grammar = TypedGrammarGenerator.generate(
                LCL_FIELDS,
                Options.queryConditionAwareAllOperatorsDefaults().withContributorRoot());
        require(rule(grammar, "<contributor_root>").equals("<contributor_root> ::= <sorted_pipeline> <sorted_pipeline>"),
                "Both-branch contributor grammar should generate both branches");
        require(rule(grammar, "<ordinary_operator>").contains("<map_noise_continuous_numeric>"),
                "All-operator grammar should include plain map noise");
        require(rule(grammar, "<ordinary_operator>").contains("<map_condition_preserving_noise_continuous_numeric>"),
                "All-operator grammar should include condition-preserving noise");
        require(rule(grammar, "<timestamp_sensitive_operator>").contains("<map_timestamp_group_shuffle_continuous_numeric>"),
                "All-operator grammar should include plain timestamp-group shuffle");
        require(rule(grammar, "<timestamp_sensitive_operator>").contains("<map_condition_partition_shuffle_continuous_numeric>"),
                "All-operator grammar should include condition partition shuffle");
        require(!rule(grammar, "<unsorted_pipeline>").contains("<timestamp_sensitive_operator>"),
                "All-operator unsorted continuations must not include timestamp-sensitive operators");
    }

    private static String rule(String grammar, String ruleName) {
        for (String line : grammar.split("\\R")) {
            if (line.startsWith(ruleName + " ::= ")) {
                return line;
            }
        }
        throw new IllegalStateException("Missing grammar rule: " + ruleName);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
