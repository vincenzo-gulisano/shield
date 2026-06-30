package grammar.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Generates a BNF grammar that exposes only operators compatible with field semantic types.
 *
 * <p>This class is intentionally independent from {@code QueryMapper}. Its job is only to produce
 * a grammar shape for typed operators, for example {@code <map_noise_nominal>} and
 * {@code <map_noise_continuous_numeric>}. The mapper can later decide how those new typed nodes
 * should be converted into executable operators.
 */
public final class TypedGrammarGenerator {

    private TypedGrammarGenerator() {
    }

    /**
     * Pipeline shape to generate.
     */
    public enum PipelineMode {
        /**
         * Legacy shape: every operator can appear anywhere in {@code <pipeline>}.
         */
        FLAT,

        /**
         * Split shape: timestamp-sensitive operators can appear only in {@code <sorted_pipeline>}.
         * After a fork rejoins, continuation uses {@code <unsorted_pipeline>}.
         */
        TIMESTAMP_AWARE
    }

    /**
     * Ordering of generated operator alternatives.
     */
    public enum OperatorOrdering {
        /**
         * Group alternatives by field type. This preserves the older typed LCL grammars.
         */
        TYPE_GROUPED,

        /**
         * Group alternatives by operator family. This matches the query-aware LCL grammars.
         */
        CATEGORY_GROUPED
    }

    /**
     * Generation options for a typed grammar.
     *
     * @param includeConditionalForks whether to include typed value-based conditional forks
     * @param pipelineMode whether to generate a legacy flat pipeline or a timestamp-aware pipeline
     * @param operatorOrdering ordering policy for generated operator alternatives
     * @param useSortedOperatorWrappers whether timestamp-aware grammars should define
     *     {@code <sorted_operator>} and {@code <unsorted_operator>} wrapper rules
     * @param forceContributorRoot whether to force a provenance/contributor fork at the root
     * @param includeQueryConditionOperators whether to include provenance-condition filter/fork
     *     operators
     * @param includeMapDuplicate whether to include {@code MapDuplicate}
     * @param includeNoiseOperators whether to include plain {@code MapNoise} operators
     * @param includeRirOperators whether to include plain randomized-in-range/response operators
     * @param includeAggregateOperators whether to include {@code MapAggregate} for numeric fields
     * @param includeTimestampPairwiseSwap whether to include plain timestamp-pairwise swaps
     * @param includeTimestampGroupShuffle whether to include plain timestamp-group shuffles
     * @param includeConditionPreservingOperators whether to include provenance-preserving
     *     noise/randomization operators
     * @param includeConditionTimestampOperators whether to include provenance-conditioned
     *     timestamp-sensitive swaps/shuffles
     * @param minWindow minimum generated aggregate window size
     * @param maxWindow maximum generated aggregate window size
     * @param probabilities generated probability values for duplication and categorical
     *     randomized response
     * @param percentages generated percentage values for numeric noise
     * @param numericValues generated value tokens for numeric filters
     * @param nominalFallbackValues generated value tokens for nominal filters when a nominal field
     *     has no explicit domain values
     */
    public record Options(
            boolean includeConditionalForks,
            PipelineMode pipelineMode,
            OperatorOrdering operatorOrdering,
            boolean useSortedOperatorWrappers,
            boolean forceContributorRoot,
            boolean includeQueryConditionOperators,
            boolean includeMapDuplicate,
            boolean includeNoiseOperators,
            boolean includeRirOperators,
            boolean includeAggregateOperators,
            boolean includeTimestampPairwiseSwap,
            boolean includeTimestampGroupShuffle,
            boolean includeConditionPreservingOperators,
            boolean includeConditionTimestampOperators,
            int minWindow,
            int maxWindow,
            List<String> probabilities,
            List<String> percentages,
            List<String> numericValues,
            List<String> nominalFallbackValues) {

        public Options {
            pipelineMode = Objects.requireNonNull(pipelineMode, "pipelineMode cannot be null");
            operatorOrdering = Objects.requireNonNull(operatorOrdering, "operatorOrdering cannot be null");
            if (useSortedOperatorWrappers && pipelineMode != PipelineMode.TIMESTAMP_AWARE) {
                throw new IllegalArgumentException("Sorted operator wrappers require TIMESTAMP_AWARE pipeline mode");
            }
            if (forceContributorRoot && pipelineMode != PipelineMode.TIMESTAMP_AWARE) {
                throw new IllegalArgumentException("Contributor root grammars require TIMESTAMP_AWARE pipeline mode");
            }
            if (minWindow > maxWindow) {
                throw new IllegalArgumentException("minWindow cannot be greater than maxWindow");
            }
            probabilities = copyGrammarTokens(probabilities, "probability");
            percentages = copyGrammarTokens(percentages, "percentage");
            numericValues = copyGrammarTokens(numericValues, "numeric value");
            nominalFallbackValues = copyGrammarTokens(nominalFallbackValues, "nominal fallback value");
        }

        /**
         * Default choices for typed grammars.
         */
        public static Options defaults() {
            return new Options(
                    true,
                    PipelineMode.FLAT,
                    OperatorOrdering.TYPE_GROUPED,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    false,
                    false,
                    3,
                    10,
                    List.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"),
                    List.of("0.01", "0.05", "0.10", "0.25"),
                    List.of("urir", "drir"), // uniform random in range; distribution-based random in range
                    List.of("ucr", "dcr")); // uniform categorical random; distribution-based categorical random
        }

        /**
         * Default operator set with timestamp-sensitive operators protected from post-union
         * placement.
         */
        public static Options timestampAwareDefaults() {
            return new Options(
                    true,
                    PipelineMode.TIMESTAMP_AWARE,
                    OperatorOrdering.TYPE_GROUPED,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    false,
                    false,
                    3,
                    10,
                    List.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"),
                    List.of("0.01", "0.05", "0.10", "0.25"),
                    List.of("urir", "drir"),
                    List.of("ucr", "dcr"));
        }

        /**
         * Timestamp-aware grammar shape used by query/provenance-aware LCL-flow experiments.
         */
        public static Options queryConditionAwareDefaults() {
            return new Options(
                    true,
                    PipelineMode.TIMESTAMP_AWARE,
                    OperatorOrdering.CATEGORY_GROUPED,
                    false,
                    false,
                    true,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    true,
                    3,
                    10,
                    List.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"),
                    List.of("0.01", "0.05", "0.10", "0.25"),
                    List.of("urir", "drir"),
                    List.of("ucr", "dcr"));
        }

        /**
         * Query/provenance-aware timestamp-aware grammar with both plain and
         * condition-preserving operators enabled.
         */
        public static Options queryConditionAwareAllOperatorsDefaults() {
            return new Options(
                    true,
                    PipelineMode.TIMESTAMP_AWARE,
                    OperatorOrdering.CATEGORY_GROUPED,
                    false,
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    3,
                    10,
                    List.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"),
                    List.of("0.01", "0.05", "0.10", "0.25"),
                    List.of("urir", "drir"),
                    List.of("ucr", "dcr"));
        }

        public Options withContributorRoot(boolean forceContributorRoot) {
            return new Options(
                    includeConditionalForks,
                    pipelineMode,
                    operatorOrdering,
                    useSortedOperatorWrappers,
                    forceContributorRoot,
                    includeQueryConditionOperators,
                    includeMapDuplicate,
                    includeNoiseOperators,
                    includeRirOperators,
                    includeAggregateOperators,
                    includeTimestampPairwiseSwap,
                    includeTimestampGroupShuffle,
                    includeConditionPreservingOperators,
                    includeConditionTimestampOperators,
                    minWindow,
                    maxWindow,
                    probabilities,
                    percentages,
                    numericValues,
                    nominalFallbackValues);
        }

        public Options withSortedOperatorWrappers(boolean useSortedOperatorWrappers) {
            return new Options(
                    includeConditionalForks,
                    pipelineMode,
                    operatorOrdering,
                    useSortedOperatorWrappers,
                    forceContributorRoot,
                    includeQueryConditionOperators,
                    includeMapDuplicate,
                    includeNoiseOperators,
                    includeRirOperators,
                    includeAggregateOperators,
                    includeTimestampPairwiseSwap,
                    includeTimestampGroupShuffle,
                    includeConditionPreservingOperators,
                    includeConditionTimestampOperators,
                    minWindow,
                    maxWindow,
                    probabilities,
                    percentages,
                    numericValues,
                    nominalFallbackValues);
        }

        public Options withOperatorOrdering(OperatorOrdering operatorOrdering) {
            return new Options(
                    includeConditionalForks,
                    pipelineMode,
                    operatorOrdering,
                    useSortedOperatorWrappers,
                    forceContributorRoot,
                    includeQueryConditionOperators,
                    includeMapDuplicate,
                    includeNoiseOperators,
                    includeRirOperators,
                    includeAggregateOperators,
                    includeTimestampPairwiseSwap,
                    includeTimestampGroupShuffle,
                    includeConditionPreservingOperators,
                    includeConditionTimestampOperators,
                    minWindow,
                    maxWindow,
                    probabilities,
                    percentages,
                    numericValues,
                    nominalFallbackValues);
        }

        public Options withContributorRoot() {
            return withContributorRoot(true);
        }
    }

    /**
     * Generate a typed BNF grammar.
     *
     * <p>The generated grammar contains no legacy untyped operators. This keeps it useful as a
     * clean target grammar while leaving existing grammars and configs untouched.
     */
    public static String generate(List<FieldDefinition> fields) {
        return generate(fields, Options.defaults());
    }

    /**
     * Generate a typed BNF grammar with explicit options.
     */
    public static String generate(List<FieldDefinition> fields, Options options) {
        Objects.requireNonNull(options, "options cannot be null");
        Map<FieldType, List<FieldDefinition>> fieldsByType = groupAndValidate(fields);

        OperatorRuleSet operatorRules = operatorRules(fieldsByType, options);
        if (!operatorRules.hasAnyOperator()) {
            throw new IllegalArgumentException("No operators can be generated for the provided fields");
        }

        StringBuilder grammar = new StringBuilder();
        appendPipelineDefinitions(grammar, operatorRules, options);

        appendOperatorDefinitions(grammar, fieldsByType, options);
        appendAttributeDefinitions(grammar, fieldsByType);
        appendValueDefinitions(grammar, fieldsByType, options);

        return grammar.toString();
    }

    /**
     * Write a generated grammar directly to a file.
     */
    public static void write(List<FieldDefinition> fields, Options options, Path outputPath) throws IOException {
        Objects.requireNonNull(outputPath, "outputPath cannot be null");
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputPath, generate(fields, options));
    }

    private record OperatorRuleSet(
            List<String> ordinaryOperators,
            List<String> timestampSensitiveOperators,
            List<String> flatForks,
            List<String> sortedForks,
            List<String> unsortedForks) {

        boolean hasAnyOperator() {
            return !ordinaryOperators.isEmpty()
                    || !timestampSensitiveOperators.isEmpty()
                    || !flatForks.isEmpty()
                    || !sortedForks.isEmpty()
                    || !unsortedForks.isEmpty();
        }

        List<String> flatOperators() {
            List<String> operators = new ArrayList<>();
            operators.addAll(ordinaryOperators);
            operators.addAll(timestampSensitiveOperators);
            operators.addAll(flatForks);
            return operators;
        }
    }

    private static OperatorRuleSet operatorRules(
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options) {
        if (options.operatorOrdering() == OperatorOrdering.CATEGORY_GROUPED) {
            return categoryGroupedOperatorRules(fieldsByType, options);
        }
        return typeGroupedOperatorRules(fieldsByType, options);
    }

    private static OperatorRuleSet typeGroupedOperatorRules(
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options) {
        List<String> ordinaryOperators = new ArrayList<>();
        List<String> timestampSensitiveOperators = new ArrayList<>();
        List<String> flatForks = new ArrayList<>();
        List<String> sortedForks = new ArrayList<>();
        List<String> unsortedForks = new ArrayList<>();

        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            ordinaryOperators.add("<filter_discrete_numeric>");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            ordinaryOperators.add("<filter_continuous_numeric>");
        }
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            ordinaryOperators.add("<filter_nominal>");
        }
        if (options.includeQueryConditionOperators()) {
            ordinaryOperators.add("<filter_query_condition>");
        }
        if (options.includeMapDuplicate()) {
            ordinaryOperators.add("<map_duplicate>");
        }
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            if (options.includeNoiseOperators()) {
                ordinaryOperators.add("<map_noise_nominal>");
            }
            if (options.includeRirOperators()) {
                ordinaryOperators.add("<map_rir_nominal>");
            }
            if (options.includeConditionPreservingOperators()) {
                ordinaryOperators.add("<map_condition_preserving_noise_nominal>");
                ordinaryOperators.add("<map_condition_preserving_rir_nominal>");
            }
            addTimestampSensitiveOperator(timestampOperatorTarget(ordinaryOperators, timestampSensitiveOperators, options),
                    options,
                    "<map_timestamp_pairwise_swap_nominal>",
                    "<map_timestamp_group_shuffle_nominal>",
                    "<map_condition_pairwise_swap_nominal>",
                    "<map_condition_partition_shuffle_nominal>");
        }
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            if (options.includeNoiseOperators()) {
                ordinaryOperators.add("<map_noise_discrete_numeric>");
            }
            if (options.includeRirOperators()) {
                ordinaryOperators.add("<map_rir_discrete_numeric>");
            }
            if (options.includeAggregateOperators()) {
                ordinaryOperators.add("<map_aggregate_discrete_numeric>");
            }
            if (options.includeConditionPreservingOperators()) {
                ordinaryOperators.add("<map_condition_preserving_noise_discrete_numeric>");
                ordinaryOperators.add("<map_condition_preserving_rir_discrete_numeric>");
            }
            addTimestampSensitiveOperator(timestampOperatorTarget(ordinaryOperators, timestampSensitiveOperators, options),
                    options,
                    "<map_timestamp_pairwise_swap_discrete_numeric>",
                    "<map_timestamp_group_shuffle_discrete_numeric>",
                    "<map_condition_pairwise_swap_discrete_numeric>",
                    "<map_condition_partition_shuffle_discrete_numeric>");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            if (options.includeNoiseOperators()) {
                ordinaryOperators.add("<map_noise_continuous_numeric>");
            }
            if (options.includeRirOperators()) {
                ordinaryOperators.add("<map_rir_continuous_numeric>");
            }
            if (options.includeAggregateOperators()) {
                ordinaryOperators.add("<map_aggregate_continuous_numeric>");
            }
            if (options.includeConditionPreservingOperators()) {
                ordinaryOperators.add("<map_condition_preserving_noise_continuous_numeric>");
                ordinaryOperators.add("<map_condition_preserving_rir_continuous_numeric>");
            }
            addTimestampSensitiveOperator(timestampOperatorTarget(ordinaryOperators, timestampSensitiveOperators, options),
                    options,
                    "<map_timestamp_pairwise_swap_continuous_numeric>",
                    "<map_timestamp_group_shuffle_continuous_numeric>",
                    "<map_condition_pairwise_swap_continuous_numeric>",
                    "<map_condition_partition_shuffle_continuous_numeric>");
        }
        if (options.includeConditionalForks()) {
            if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
                flatForks.add("<fork_discrete_numeric>");
                sortedForks.add("<fork_discrete_numeric_sorted>");
                unsortedForks.add("<fork_discrete_numeric_unsorted>");
            }
            if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
                flatForks.add("<fork_continuous_numeric>");
                sortedForks.add("<fork_continuous_numeric_sorted>");
                unsortedForks.add("<fork_continuous_numeric_unsorted>");
            }
            if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
                flatForks.add("<fork_nominal>");
                sortedForks.add("<fork_nominal_sorted>");
                unsortedForks.add("<fork_nominal_unsorted>");
            }
        }
        if (options.pipelineMode() == PipelineMode.TIMESTAMP_AWARE && options.includeQueryConditionOperators()) {
            sortedForks.add("<query_condition_fork_sorted>");
            unsortedForks.add("<query_condition_fork_unsorted>");
        }
        return new OperatorRuleSet(
                List.copyOf(ordinaryOperators),
                List.copyOf(timestampSensitiveOperators),
                List.copyOf(flatForks),
                List.copyOf(sortedForks),
                List.copyOf(unsortedForks));
    }

    private static List<String> timestampOperatorTarget(
            List<String> ordinaryOperators,
            List<String> timestampSensitiveOperators,
            Options options) {
        return options.pipelineMode() == PipelineMode.FLAT ? ordinaryOperators : timestampSensitiveOperators;
    }

    private static OperatorRuleSet categoryGroupedOperatorRules(
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options) {
        List<String> ordinaryOperators = new ArrayList<>();
        List<String> timestampSensitiveOperators = new ArrayList<>();
        List<String> flatForks = new ArrayList<>();
        List<String> sortedForks = new ArrayList<>();
        List<String> unsortedForks = new ArrayList<>();

        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            ordinaryOperators.add("<filter_discrete_numeric>");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            ordinaryOperators.add("<filter_continuous_numeric>");
        }
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            ordinaryOperators.add("<filter_nominal>");
        }
        if (options.includeQueryConditionOperators()) {
            ordinaryOperators.add("<filter_query_condition>");
        }
        if (options.includeMapDuplicate()) {
            ordinaryOperators.add("<map_duplicate>");
        }
        if (options.includeNoiseOperators()) {
            addByType(ordinaryOperators, fieldsByType,
                    "<map_noise_nominal>",
                    "<map_noise_discrete_numeric>",
                    "<map_noise_continuous_numeric>");
        }
        if (options.includeRirOperators()) {
            addByType(ordinaryOperators, fieldsByType,
                    "<map_rir_nominal>",
                    "<map_rir_discrete_numeric>",
                    "<map_rir_continuous_numeric>");
        }
        if (options.includeAggregateOperators()) {
            addNumericByType(ordinaryOperators, fieldsByType,
                    "<map_aggregate_discrete_numeric>",
                    "<map_aggregate_continuous_numeric>");
        }
        if (options.includeConditionPreservingOperators()) {
            addByType(ordinaryOperators, fieldsByType,
                    "<map_condition_preserving_noise_nominal>",
                    "<map_condition_preserving_noise_discrete_numeric>",
                    "<map_condition_preserving_noise_continuous_numeric>");
            addByType(ordinaryOperators, fieldsByType,
                    "<map_condition_preserving_rir_nominal>",
                    "<map_condition_preserving_rir_discrete_numeric>",
                    "<map_condition_preserving_rir_continuous_numeric>");
        }

        if (options.includeTimestampPairwiseSwap()) {
            addByType(timestampSensitiveOperators, fieldsByType,
                    "<map_timestamp_pairwise_swap_nominal>",
                    "<map_timestamp_pairwise_swap_discrete_numeric>",
                    "<map_timestamp_pairwise_swap_continuous_numeric>");
        }
        if (options.includeTimestampGroupShuffle()) {
            addByType(timestampSensitiveOperators, fieldsByType,
                    "<map_timestamp_group_shuffle_nominal>",
                    "<map_timestamp_group_shuffle_discrete_numeric>",
                    "<map_timestamp_group_shuffle_continuous_numeric>");
        }
        if (options.includeConditionTimestampOperators()) {
            addByType(timestampSensitiveOperators, fieldsByType,
                    "<map_condition_pairwise_swap_nominal>",
                    "<map_condition_pairwise_swap_discrete_numeric>",
                    "<map_condition_pairwise_swap_continuous_numeric>");
            addByType(timestampSensitiveOperators, fieldsByType,
                    "<map_condition_partition_shuffle_nominal>",
                    "<map_condition_partition_shuffle_discrete_numeric>",
                    "<map_condition_partition_shuffle_continuous_numeric>");
        }

        addForkRules(fieldsByType, options, flatForks, sortedForks, unsortedForks);
        return new OperatorRuleSet(
                List.copyOf(ordinaryOperators),
                List.copyOf(timestampSensitiveOperators),
                List.copyOf(flatForks),
                List.copyOf(sortedForks),
                List.copyOf(unsortedForks));
    }

    private static void addByType(
            List<String> operators,
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            String nominalOperator,
            String discreteNumericOperator,
            String continuousNumericOperator) {
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            operators.add(nominalOperator);
        }
        addNumericByType(operators, fieldsByType, discreteNumericOperator, continuousNumericOperator);
    }

    private static void addNumericByType(
            List<String> operators,
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            String discreteNumericOperator,
            String continuousNumericOperator) {
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            operators.add(discreteNumericOperator);
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            operators.add(continuousNumericOperator);
        }
    }

    private static void addForkRules(
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options,
            List<String> flatForks,
            List<String> sortedForks,
            List<String> unsortedForks) {
        if (options.includeConditionalForks()) {
            if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
                flatForks.add("<fork_discrete_numeric>");
                sortedForks.add("<fork_discrete_numeric_sorted>");
                unsortedForks.add("<fork_discrete_numeric_unsorted>");
            }
            if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
                flatForks.add("<fork_continuous_numeric>");
                sortedForks.add("<fork_continuous_numeric_sorted>");
                unsortedForks.add("<fork_continuous_numeric_unsorted>");
            }
            if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
                flatForks.add("<fork_nominal>");
                sortedForks.add("<fork_nominal_sorted>");
                unsortedForks.add("<fork_nominal_unsorted>");
            }
        }
        if (options.pipelineMode() == PipelineMode.TIMESTAMP_AWARE && options.includeQueryConditionOperators()) {
            sortedForks.add("<query_condition_fork_sorted>");
            unsortedForks.add("<query_condition_fork_unsorted>");
        }
    }

    private static void addTimestampSensitiveOperator(
            List<String> timestampSensitiveOperators,
            Options options,
            String pairwiseSwap,
            String groupShuffle,
            String conditionPairwiseSwap,
            String conditionGroupShuffle) {
        if (options.includeTimestampPairwiseSwap()) {
            timestampSensitiveOperators.add(pairwiseSwap);
        }
        if (options.includeTimestampGroupShuffle()) {
            timestampSensitiveOperators.add(groupShuffle);
        }
        if (options.includeConditionTimestampOperators()) {
            timestampSensitiveOperators.add(conditionPairwiseSwap);
            timestampSensitiveOperators.add(conditionGroupShuffle);
        }
    }

    private static void appendPipelineDefinitions(
            StringBuilder grammar,
            OperatorRuleSet operatorRules,
            Options options) {
        if (options.pipelineMode() == PipelineMode.FLAT) {
            List<String> flatOperators = operatorRules.flatOperators();
            if (flatOperators.isEmpty()) {
                throw new IllegalArgumentException("Flat pipeline mode requires at least one generated operator");
            }
            grammar.append("<pipeline> ::= <operator> | <operator> <pipeline>\n");
            grammar.append("<operator> ::= ").append(joinAlternatives(flatOperators)).append("\n");
            return;
        }

        if (operatorRules.ordinaryOperators().isEmpty() && !operatorRules.sortedForks().isEmpty()) {
            throw new IllegalArgumentException(
                    "Timestamp-aware fork grammars require at least one ordinary operator for unsorted continuations");
        }

        if (options.forceContributorRoot()) {
            grammar.append("<pipeline> ::= <contributor_root>\n");
            grammar.append("<contributor_root> ::= <sorted_pipeline> <sorted_pipeline>\n");
        } else {
            grammar.append("<pipeline> ::= <sorted_pipeline>\n");
        }
        if (options.useSortedOperatorWrappers()) {
            appendTimestampAwarePipelineRule(
                    grammar,
                    "<sorted_pipeline>",
                    "<sorted_pipeline>",
                    "<unsorted_pipeline>",
                    operatorRules.ordinaryOperators(),
                    operatorRules.timestampSensitiveOperators(),
                    operatorRules.sortedForks(),
                    true);
            if (!operatorRules.sortedForks().isEmpty()) {
                appendTimestampAwarePipelineRule(
                        grammar,
                        "<unsorted_pipeline>",
                        "<unsorted_pipeline>",
                        "<unsorted_pipeline>",
                        operatorRules.ordinaryOperators(),
                        List.of(),
                        operatorRules.unsortedForks(),
                        true);
            }
            grammar.append("<sorted_operator> ::= <ordinary_operator>");
            if (!operatorRules.timestampSensitiveOperators().isEmpty()) {
                grammar.append(" | <timestamp_sensitive_operator>");
            }
            grammar.append("\n");
            grammar.append("<unsorted_operator> ::= <ordinary_operator>\n");
            appendOperatorWrapperDefinition(grammar, "<ordinary_operator>", operatorRules.ordinaryOperators());
            appendOperatorWrapperDefinition(grammar, "<timestamp_sensitive_operator>",
                    operatorRules.timestampSensitiveOperators());
            appendOperatorWrapperDefinition(grammar, "<sorted_fork>", operatorRules.sortedForks());
            appendOperatorWrapperDefinition(grammar, "<unsorted_fork>", operatorRules.unsortedForks());
            return;
        }
        appendTimestampAwarePipelineRule(
                grammar,
                "<sorted_pipeline>",
                "<sorted_pipeline>",
                "<unsorted_pipeline>",
                operatorRules.ordinaryOperators(),
                operatorRules.timestampSensitiveOperators(),
                operatorRules.sortedForks(),
                false);
        if (!operatorRules.sortedForks().isEmpty()) {
            appendTimestampAwarePipelineRule(
                    grammar,
                    "<unsorted_pipeline>",
                    "<unsorted_pipeline>",
                    "<unsorted_pipeline>",
                    operatorRules.ordinaryOperators(),
                    List.of(),
                    operatorRules.unsortedForks(),
                    false);
        }
        appendOperatorWrapperDefinition(grammar, "<ordinary_operator>", operatorRules.ordinaryOperators());
        appendOperatorWrapperDefinition(grammar, "<timestamp_sensitive_operator>",
                operatorRules.timestampSensitiveOperators());
        appendOperatorWrapperDefinition(grammar, "<sorted_fork>", operatorRules.sortedForks());
        appendOperatorWrapperDefinition(grammar, "<unsorted_fork>", operatorRules.unsortedForks());
    }

    private static void appendTimestampAwarePipelineRule(
            StringBuilder grammar,
            String ruleName,
            String nextPipelineRule,
            String forkContinuationRule,
            List<String> ordinaryOperators,
            List<String> timestampSensitiveOperators,
            List<String> forkOperators,
            boolean useSortedOperatorWrappers) {
        List<String> alternatives = new ArrayList<>();
        if (useSortedOperatorWrappers) {
            String operatorRule = ruleName.equals("<sorted_pipeline>") ? "<sorted_operator>" : "<unsorted_operator>";
            if (!ordinaryOperators.isEmpty() || !timestampSensitiveOperators.isEmpty()) {
                alternatives.add(operatorRule);
                alternatives.add(operatorRule + " " + nextPipelineRule);
            }
        } else {
            if (!ordinaryOperators.isEmpty()) {
                alternatives.add("<ordinary_operator>");
                alternatives.add("<ordinary_operator> " + nextPipelineRule);
            }
            if (!timestampSensitiveOperators.isEmpty()) {
                alternatives.add("<timestamp_sensitive_operator>");
                alternatives.add("<timestamp_sensitive_operator> " + nextPipelineRule);
            }
        }
        if (!forkOperators.isEmpty()) {
            alternatives.add(ruleName.equals("<sorted_pipeline>") ? "<sorted_fork>" : "<unsorted_fork>");
            alternatives.add((ruleName.equals("<sorted_pipeline>") ? "<sorted_fork>" : "<unsorted_fork>")
                    + " " + forkContinuationRule);
        }
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("Cannot generate empty pipeline rule " + ruleName);
        }
        grammar.append(ruleName).append(" ::= ").append(joinAlternatives(alternatives)).append("\n");
    }

    private static void appendOperatorWrapperDefinition(
            StringBuilder grammar,
            String ruleName,
            List<String> alternatives) {
        if (!alternatives.isEmpty()) {
            grammar.append(ruleName).append(" ::= ").append(joinAlternatives(alternatives)).append("\n");
        }
    }

    private static void appendOperatorDefinitions(
            StringBuilder grammar,
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options) {
        if (options.operatorOrdering() == OperatorOrdering.CATEGORY_GROUPED) {
            appendCategoryGroupedOperatorDefinitions(grammar, fieldsByType, options);
            return;
        }
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            grammar.append("<filter_discrete_numeric> ::= <discrete_numeric_attribute> <numeric_condition> <numeric_value>\n");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            grammar.append("<filter_continuous_numeric> ::= <continuous_numeric_attribute> <numeric_condition> <numeric_value>\n");
        }
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            grammar.append("<filter_nominal> ::= ")
                    .append(nominalConditionAlternatives(fieldsByType.get(FieldType.NOMINAL_CATEGORICAL), ""))
                    .append("\n");
        }

        if (options.includeQueryConditionOperators()) {
            grammar.append("<filter_query_condition> ::= <condition_keep>\n");
        }

        if (options.includeMapDuplicate()) {
            grammar.append("<map_duplicate> ::= <probability>\n");
        }

        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            if (options.includeNoiseOperators()) {
                grammar.append("<map_noise_nominal> ::= <nominal_attribute> <probability>\n");
            }
            if (options.includeRirOperators()) {
                grammar.append("<map_rir_nominal> ::= <nominal_attribute>\n");
            }
            if (options.includeConditionPreservingOperators()) {
                grammar.append("<map_condition_preserving_noise_nominal> ::= <nominal_attribute> <probability>\n");
                grammar.append("<map_condition_preserving_rir_nominal> ::= <nominal_attribute>\n");
            }
            appendTimestampSensitiveDefinitions(
                    grammar,
                    options,
                    "<nominal_attribute>",
                    "<map_timestamp_pairwise_swap_nominal>",
                    "<map_timestamp_group_shuffle_nominal>",
                    "<map_condition_pairwise_swap_nominal>",
                    "<map_condition_partition_shuffle_nominal>");
        }
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            if (options.includeNoiseOperators()) {
                grammar.append("<map_noise_discrete_numeric> ::= <discrete_numeric_attribute> <percentage>\n");
            }
            if (options.includeRirOperators()) {
                grammar.append("<map_rir_discrete_numeric> ::= <discrete_numeric_attribute>\n");
            }
            if (options.includeAggregateOperators()) {
                grammar.append("<map_aggregate_discrete_numeric> ::= <discrete_numeric_attribute> <numeric_agg_fun> <window_size>\n");
            }
            if (options.includeConditionPreservingOperators()) {
                grammar.append("<map_condition_preserving_noise_discrete_numeric> ::= <discrete_numeric_attribute> <percentage>\n");
                grammar.append("<map_condition_preserving_rir_discrete_numeric> ::= <discrete_numeric_attribute>\n");
            }
            appendTimestampSensitiveDefinitions(
                    grammar,
                    options,
                    "<discrete_numeric_attribute>",
                    "<map_timestamp_pairwise_swap_discrete_numeric>",
                    "<map_timestamp_group_shuffle_discrete_numeric>",
                    "<map_condition_pairwise_swap_discrete_numeric>",
                    "<map_condition_partition_shuffle_discrete_numeric>");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            if (options.includeNoiseOperators()) {
                grammar.append("<map_noise_continuous_numeric> ::= <continuous_numeric_attribute> <percentage>\n");
            }
            if (options.includeRirOperators()) {
                grammar.append("<map_rir_continuous_numeric> ::= <continuous_numeric_attribute>\n");
            }
            if (options.includeAggregateOperators()) {
                grammar.append("<map_aggregate_continuous_numeric> ::= <continuous_numeric_attribute> <numeric_agg_fun> <window_size>\n");
            }
            if (options.includeConditionPreservingOperators()) {
                grammar.append("<map_condition_preserving_noise_continuous_numeric> ::= <continuous_numeric_attribute> <percentage>\n");
                grammar.append("<map_condition_preserving_rir_continuous_numeric> ::= <continuous_numeric_attribute>\n");
            }
            appendTimestampSensitiveDefinitions(
                    grammar,
                    options,
                    "<continuous_numeric_attribute>",
                    "<map_timestamp_pairwise_swap_continuous_numeric>",
                    "<map_timestamp_group_shuffle_continuous_numeric>",
                    "<map_condition_pairwise_swap_continuous_numeric>",
                    "<map_condition_partition_shuffle_continuous_numeric>");
        }
        if (options.includeConditionalForks()
                || (options.pipelineMode() == PipelineMode.TIMESTAMP_AWARE && options.includeQueryConditionOperators())) {
            appendForkDefinitions(grammar, fieldsByType, options);
        }
        if (options.includeQueryConditionOperators()) {
            grammar.append("<condition_keep> ::= keep_true | keep_false\n");
        }
    }

    private static void appendCategoryGroupedOperatorDefinitions(
            StringBuilder grammar,
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options) {
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            grammar.append("<filter_discrete_numeric> ::= <discrete_numeric_attribute> <numeric_condition> <numeric_value>\n");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            grammar.append("<filter_continuous_numeric> ::= <continuous_numeric_attribute> <numeric_condition> <numeric_value>\n");
        }
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            grammar.append("<filter_nominal> ::= ")
                    .append(nominalConditionAlternatives(fieldsByType.get(FieldType.NOMINAL_CATEGORICAL), ""))
                    .append("\n");
        }
        if (options.includeQueryConditionOperators()) {
            grammar.append("<filter_query_condition> ::= <condition_keep>\n");
        }
        if (options.includeMapDuplicate()) {
            grammar.append("<map_duplicate> ::= <probability>\n");
        }

        if (options.includeNoiseOperators()) {
            appendMapDefinitionByType(grammar, fieldsByType,
                    "<map_noise_nominal>", "<nominal_attribute> <probability>",
                    "<map_noise_discrete_numeric>", "<discrete_numeric_attribute> <percentage>",
                    "<map_noise_continuous_numeric>", "<continuous_numeric_attribute> <percentage>");
        }
        if (options.includeRirOperators()) {
            appendMapDefinitionByType(grammar, fieldsByType,
                    "<map_rir_nominal>", "<nominal_attribute>",
                    "<map_rir_discrete_numeric>", "<discrete_numeric_attribute>",
                    "<map_rir_continuous_numeric>", "<continuous_numeric_attribute>");
        }
        if (options.includeAggregateOperators()) {
            appendNumericDefinitionByType(grammar, fieldsByType,
                    "<map_aggregate_discrete_numeric>", "<discrete_numeric_attribute> <numeric_agg_fun> <window_size>",
                    "<map_aggregate_continuous_numeric>", "<continuous_numeric_attribute> <numeric_agg_fun> <window_size>");
        }
        if (options.includeConditionPreservingOperators()) {
            appendMapDefinitionByType(grammar, fieldsByType,
                    "<map_condition_preserving_noise_nominal>", "<nominal_attribute> <probability>",
                    "<map_condition_preserving_noise_discrete_numeric>", "<discrete_numeric_attribute> <percentage>",
                    "<map_condition_preserving_noise_continuous_numeric>", "<continuous_numeric_attribute> <percentage>");
            appendMapDefinitionByType(grammar, fieldsByType,
                    "<map_condition_preserving_rir_nominal>", "<nominal_attribute>",
                    "<map_condition_preserving_rir_discrete_numeric>", "<discrete_numeric_attribute>",
                    "<map_condition_preserving_rir_continuous_numeric>", "<continuous_numeric_attribute>");
        }

        if (options.includeTimestampPairwiseSwap()) {
            appendMapDefinitionByType(grammar, fieldsByType,
                    "<map_timestamp_pairwise_swap_nominal>", "<nominal_attribute>",
                    "<map_timestamp_pairwise_swap_discrete_numeric>", "<discrete_numeric_attribute>",
                    "<map_timestamp_pairwise_swap_continuous_numeric>", "<continuous_numeric_attribute>");
        }
        if (options.includeTimestampGroupShuffle()) {
            appendMapDefinitionByType(grammar, fieldsByType,
                    "<map_timestamp_group_shuffle_nominal>", "<nominal_attribute>",
                    "<map_timestamp_group_shuffle_discrete_numeric>", "<discrete_numeric_attribute>",
                    "<map_timestamp_group_shuffle_continuous_numeric>", "<continuous_numeric_attribute>");
        }
        if (options.includeConditionTimestampOperators()) {
            appendMapDefinitionByType(grammar, fieldsByType,
                    "<map_condition_pairwise_swap_nominal>", "<nominal_attribute>",
                    "<map_condition_pairwise_swap_discrete_numeric>", "<discrete_numeric_attribute>",
                    "<map_condition_pairwise_swap_continuous_numeric>", "<continuous_numeric_attribute>");
            appendMapDefinitionByType(grammar, fieldsByType,
                    "<map_condition_partition_shuffle_nominal>", "<nominal_attribute>",
                    "<map_condition_partition_shuffle_discrete_numeric>", "<discrete_numeric_attribute>",
                    "<map_condition_partition_shuffle_continuous_numeric>", "<continuous_numeric_attribute>");
        }

        if (options.includeConditionalForks()
                || (options.pipelineMode() == PipelineMode.TIMESTAMP_AWARE && options.includeQueryConditionOperators())) {
            appendForkDefinitions(grammar, fieldsByType, options);
        }
        if (options.includeQueryConditionOperators()) {
            grammar.append("<condition_keep> ::= keep_true | keep_false\n");
        }
    }

    private static void appendMapDefinitionByType(
            StringBuilder grammar,
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            String nominalRule,
            String nominalDefinition,
            String discreteNumericRule,
            String discreteNumericDefinition,
            String continuousNumericRule,
            String continuousNumericDefinition) {
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            grammar.append(nominalRule).append(" ::= ").append(nominalDefinition).append("\n");
        }
        appendNumericDefinitionByType(
                grammar,
                fieldsByType,
                discreteNumericRule,
                discreteNumericDefinition,
                continuousNumericRule,
                continuousNumericDefinition);
    }

    private static void appendNumericDefinitionByType(
            StringBuilder grammar,
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            String discreteNumericRule,
            String discreteNumericDefinition,
            String continuousNumericRule,
            String continuousNumericDefinition) {
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            grammar.append(discreteNumericRule).append(" ::= ").append(discreteNumericDefinition).append("\n");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            grammar.append(continuousNumericRule).append(" ::= ").append(continuousNumericDefinition).append("\n");
        }
    }

    private static void appendTimestampSensitiveDefinitions(
            StringBuilder grammar,
            Options options,
            String attributeRule,
            String pairwiseSwapRule,
            String groupShuffleRule,
            String conditionPairwiseSwapRule,
            String conditionGroupShuffleRule) {
        if (options.includeTimestampPairwiseSwap()) {
            grammar.append(pairwiseSwapRule).append(" ::= ").append(attributeRule).append("\n");
        }
        if (options.includeTimestampGroupShuffle()) {
            grammar.append(groupShuffleRule).append(" ::= ").append(attributeRule).append("\n");
        }
        if (options.includeConditionTimestampOperators()) {
            grammar.append(conditionPairwiseSwapRule).append(" ::= ").append(attributeRule).append("\n");
            grammar.append(conditionGroupShuffleRule).append(" ::= ").append(attributeRule).append("\n");
        }
    }

    private static void appendForkDefinitions(
            StringBuilder grammar,
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options) {
        if (options.pipelineMode() == PipelineMode.FLAT) {
            if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
                grammar.append("<fork_discrete_numeric> ::= <discrete_numeric_attribute> <numeric_condition> <numeric_value> <pipeline> <pipeline>\n");
            }
            if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
                grammar.append("<fork_continuous_numeric> ::= <continuous_numeric_attribute> <numeric_condition> <numeric_value> <pipeline> <pipeline>\n");
            }
            if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
                grammar.append("<fork_nominal> ::= ")
                        .append(nominalConditionAlternatives(
                                fieldsByType.get(FieldType.NOMINAL_CATEGORICAL), " <pipeline> <pipeline>"))
                        .append("\n");
            }
            return;
        }

        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            grammar.append("<fork_discrete_numeric_sorted> ::= <discrete_numeric_attribute> <numeric_condition> <numeric_value> <sorted_pipeline> <sorted_pipeline>\n");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            grammar.append("<fork_continuous_numeric_sorted> ::= <continuous_numeric_attribute> <numeric_condition> <numeric_value> <sorted_pipeline> <sorted_pipeline>\n");
        }
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            grammar.append("<fork_nominal_sorted> ::= ")
                    .append(nominalConditionAlternatives(
                            fieldsByType.get(FieldType.NOMINAL_CATEGORICAL), " <sorted_pipeline> <sorted_pipeline>"))
                    .append("\n");
        }
        if (options.includeQueryConditionOperators()) {
            grammar.append("<query_condition_fork_sorted> ::= <sorted_pipeline> <sorted_pipeline>\n");
        }
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            grammar.append("<fork_discrete_numeric_unsorted> ::= <discrete_numeric_attribute> <numeric_condition> <numeric_value> <unsorted_pipeline> <unsorted_pipeline>\n");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            grammar.append("<fork_continuous_numeric_unsorted> ::= <continuous_numeric_attribute> <numeric_condition> <numeric_value> <unsorted_pipeline> <unsorted_pipeline>\n");
        }
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            grammar.append("<fork_nominal_unsorted> ::= ")
                    .append(nominalConditionAlternatives(
                            fieldsByType.get(FieldType.NOMINAL_CATEGORICAL), " <unsorted_pipeline> <unsorted_pipeline>"))
                    .append("\n");
        }
        if (options.includeQueryConditionOperators()) {
            grammar.append("<query_condition_fork_unsorted> ::= <unsorted_pipeline> <unsorted_pipeline>\n");
        }
    }

    private static void appendAttributeDefinitions(
            StringBuilder grammar,
            Map<FieldType, List<FieldDefinition>> fieldsByType) {
        appendAttributeDefinition(grammar, "<nominal_attribute>", fieldsByType.get(FieldType.NOMINAL_CATEGORICAL));
        appendAttributeDefinition(grammar, "<discrete_numeric_attribute>", fieldsByType.get(FieldType.DISCRETE_NUMERIC));
        appendAttributeDefinition(grammar, "<continuous_numeric_attribute>", fieldsByType.get(FieldType.CONTINUOUS_NUMERIC));
    }

    private static void appendValueDefinitions(
            StringBuilder grammar,
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options) {
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            boolean hasFallback = false;
            for (FieldDefinition field : fieldsByType.get(FieldType.NOMINAL_CATEGORICAL)) {
                if (field.values().isEmpty()) {
                    hasFallback = true;
                } else {
                    grammar.append(fieldValueRule(field)).append(" ::= ")
                            .append(joinAlternatives(field.values()))
                            .append("\n");
                }
            }
            if (hasFallback) {
                grammar.append("<nominal_value> ::= ")
                        .append(joinAlternatives(options.nominalFallbackValues()))
                        .append("\n");
            }
            grammar.append("<nominal_condition> ::= eq | neq\n");
        }

        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)
                || has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            grammar.append("<numeric_condition> ::= lt | gt\n");
            grammar.append("<numeric_value> ::= ").append(joinAlternatives(options.numericValues())).append("\n");
            grammar.append("<numeric_agg_fun> ::= min | avg | max\n");
            grammar.append("<window_size> ::= ").append(windowAlternatives(options.minWindow(), options.maxWindow())).append("\n");
            grammar.append("<percentage> ::= ").append(joinAlternatives(options.percentages())).append("\n");
        }
        grammar.append("<probability> ::= ").append(joinAlternatives(options.probabilities())).append("\n");
    }

    private static void appendAttributeDefinition(
            StringBuilder grammar,
            String ruleName,
            List<FieldDefinition> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        grammar.append(ruleName).append(" ::= ")
                .append(joinAlternatives(fields.stream().map(FieldDefinition::name).toList()))
                .append("\n");
    }

    private static String nominalConditionAlternatives(List<FieldDefinition> fields, String suffix) {
        StringJoiner joiner = new StringJoiner(" | ");
        for (FieldDefinition field : fields) {
            String valueRule = field.values().isEmpty() ? "<nominal_value>" : fieldValueRule(field);
            joiner.add(field.name() + " <nominal_condition> " + valueRule + suffix);
        }
        return joiner.toString();
    }

    private static String fieldValueRule(FieldDefinition field) {
        return "<" + toRuleName(field.name()) + "_value>";
    }

    private static String toRuleName(String token) {
        StringBuilder sanitized = new StringBuilder();
        for (char c : token.toCharArray()) {
            sanitized.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sanitized.toString();
    }

    private static Map<FieldType, List<FieldDefinition>> groupAndValidate(List<FieldDefinition> fields) {
        Objects.requireNonNull(fields, "fields cannot be null");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("At least one field is required");
        }

        Map<FieldType, List<FieldDefinition>> fieldsByType = new EnumMap<>(FieldType.class);
        Set<String> seenNames = new HashSet<>();
        for (FieldDefinition field : fields) {
            Objects.requireNonNull(field, "field definition cannot be null");
            if (!seenNames.add(field.name())) {
                throw new IllegalArgumentException("Duplicate field name: " + field.name());
            }
            fieldsByType.computeIfAbsent(field.type(), ignored -> new ArrayList<>()).add(field);
        }
        return fieldsByType;
    }

    private static boolean has(Map<FieldType, List<FieldDefinition>> fieldsByType, FieldType type) {
        return fieldsByType.containsKey(type) && !fieldsByType.get(type).isEmpty();
    }

    private static String windowAlternatives(int minWindow, int maxWindow) {
        StringJoiner joiner = new StringJoiner(" | ");
        for (int i = minWindow; i <= maxWindow; i++) {
            joiner.add(Integer.toString(i));
        }
        return joiner.toString();
    }

    private static String joinAlternatives(List<String> alternatives) {
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("Cannot join an empty alternative list");
        }
        return String.join(" | ", alternatives);
    }

    private static List<String> copyGrammarTokens(List<String> tokens, String description) {
        Objects.requireNonNull(tokens, description + " list cannot be null");
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException(description + " list cannot be empty");
        }
        for (String token : tokens) {
            FieldDefinition.requireGrammarToken(token, description);
        }
        return List.copyOf(tokens);
    }
}
