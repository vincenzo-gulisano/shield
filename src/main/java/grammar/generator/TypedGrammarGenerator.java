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
     * Generation options for a typed grammar.
     *
     * @param includeForks whether to include {@code <fork_ops_join>} as a possible operator
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
            boolean includeForks,
            int minWindow,
            int maxWindow,
            List<String> probabilities,
            List<String> percentages,
            List<String> numericValues,
            List<String> nominalFallbackValues) {

        public Options {
            if (minWindow > maxWindow) {
                throw new IllegalArgumentException("minWindow cannot be greater than maxWindow");
            }
            probabilities = copyGrammarTokens(probabilities, "probability");
            percentages = copyGrammarTokens(percentages, "percentage");
            numericValues = copyGrammarTokens(numericValues, "numeric value");
            nominalFallbackValues = copyGrammarTokens(nominalFallbackValues, "nominal fallback value");
        }

        /**
         * Default choices matching the existing NHANES grammar style where possible.
         */
        public static Options defaults() {
            return new Options(
                    true,
                    3,
                    10,
                    List.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"),
                    List.of("0.01", "0.05", "0.10", "0.25"),
                    List.of("urir", "drir"), // uniform random in range; distribution-based random in range
                    List.of("ucr", "dcr")); // uniform categorical random; distribution-based categorical random
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

        List<String> operatorRules = operatorRules(fieldsByType, options);
        if (operatorRules.isEmpty()) {
            throw new IllegalArgumentException("No operators can be generated for the provided fields");
        }

        StringBuilder grammar = new StringBuilder();
        grammar.append("<pipeline> ::= <operator> | <operator> <pipeline>\n");
        grammar.append("<operator> ::= ").append(joinAlternatives(operatorRules)).append("\n");

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

    private static List<String> operatorRules(
            Map<FieldType, List<FieldDefinition>> fieldsByType,
            Options options) {
        List<String> operatorRules = new ArrayList<>();
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            operatorRules.add("<filter_discrete_numeric>");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            operatorRules.add("<filter_continuous_numeric>");
        }
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            operatorRules.add("<filter_nominal>");
        }
        operatorRules.add("<map_duplicate>");
        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            operatorRules.add("<map_noise_nominal>");
            operatorRules.add("<map_rir_nominal>");
        }
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            operatorRules.add("<map_noise_discrete_numeric>");
            operatorRules.add("<map_rir_discrete_numeric>");
            operatorRules.add("<map_aggregate_discrete_numeric>");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            operatorRules.add("<map_noise_continuous_numeric>");
            operatorRules.add("<map_rir_continuous_numeric>");
            operatorRules.add("<map_aggregate_continuous_numeric>");
        }
        if (options.includeForks()) {
            operatorRules.add("<fork_ops_join>");
        }
        return operatorRules;
    }

    private static void appendOperatorDefinitions(
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
                    .append(nominalFilterAlternatives(fieldsByType.get(FieldType.NOMINAL_CATEGORICAL)))
                    .append("\n");
        }

        grammar.append("<map_duplicate> ::= <probability>\n");

        if (has(fieldsByType, FieldType.NOMINAL_CATEGORICAL)) {
            grammar.append("<map_noise_nominal> ::= <nominal_attribute> <probability>\n");
            grammar.append("<map_rir_nominal> ::= <nominal_attribute>\n");
        }
        if (has(fieldsByType, FieldType.DISCRETE_NUMERIC)) {
            grammar.append("<map_noise_discrete_numeric> ::= <discrete_numeric_attribute> <percentage>\n");
            grammar.append("<map_rir_discrete_numeric> ::= <discrete_numeric_attribute>\n");
            grammar.append("<map_aggregate_discrete_numeric> ::= <discrete_numeric_attribute> <numeric_agg_fun> <window_size>\n");
        }
        if (has(fieldsByType, FieldType.CONTINUOUS_NUMERIC)) {
            grammar.append("<map_noise_continuous_numeric> ::= <continuous_numeric_attribute> <percentage>\n");
            grammar.append("<map_rir_continuous_numeric> ::= <continuous_numeric_attribute>\n");
            grammar.append("<map_aggregate_continuous_numeric> ::= <continuous_numeric_attribute> <numeric_agg_fun> <window_size>\n");
        }
        if (options.includeForks()) {
            grammar.append("<fork_ops_join> ::= <pipeline> <pipeline>\n");
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

    private static String nominalFilterAlternatives(List<FieldDefinition> fields) {
        StringJoiner joiner = new StringJoiner(" | ");
        for (FieldDefinition field : fields) {
            String valueRule = field.values().isEmpty() ? "<nominal_value>" : fieldValueRule(field);
            joiner.add(field.name() + " <nominal_condition> " + valueRule);
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
