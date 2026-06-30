package grammar.generator;

import grammar.generator.TypedGrammarGenerator.OperatorOrdering;
import grammar.generator.TypedGrammarGenerator.Options;
import grammar.generator.TypedGrammarGenerator.PipelineMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class LclFlowExistingGrammarSmokeTest {

    private static final Path LCL_GRAMMAR_DIR = Path.of("src/main/resources/grammars/lcl");
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

    private static final List<String> PROBABILITIES =
            List.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0");
    private static final List<String> PERCENTAGES = List.of("0.01", "0.05", "0.10", "0.25");
    private static final List<String> NUMERIC_VALUES = List.of("urir", "drir");
    private static final List<String> NOMINAL_VALUES = List.of("ucr", "dcr");

    private LclFlowExistingGrammarSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> generated = Map.of(
                "lcl.flow.complete.bnf",
                TypedGrammarGenerator.generate(LCL_FIELDS, completeOptions()),
                "lcl.flow.timestamp-group-shuffle.bnf",
                TypedGrammarGenerator.generate(
                        LCL_FIELDS,
                        Options.timestampAwareDefaults().withSortedOperatorWrappers(true)),
                "lcl.flow.typed-aggregate-sampled-values.bnf",
                TypedGrammarGenerator.generate(LCL_FIELDS, typedAggregateSampledValuesOptions()),
                "lcl.flow.query-aware.bnf",
                TypedGrammarGenerator.generate(LCL_FIELDS, Options.queryConditionAwareDefaults()),
                "lcl.flow.optional-provenance-fork-all-ops.bnf",
                TypedGrammarGenerator.generate(LCL_FIELDS, Options.queryConditionAwareAllOperatorsDefaults()),
                "lcl.flow.contributor-fork-both-branches-all-ops.bnf",
                TypedGrammarGenerator.generate(
                        LCL_FIELDS,
                        Options.queryConditionAwareAllOperatorsDefaults().withContributorRoot()),
                "lcl.flow.old-aggregate-old-values.bnf",
                oldAggregateOldValuesGrammar());

        for (Map.Entry<String, String> entry : generated.entrySet()) {
            assertMatches(entry.getKey(), entry.getValue());
        }
        System.exit(0);
    }

    private static Options completeOptions() {
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
                false,
                false,
                false,
                3,
                10,
                PROBABILITIES,
                PERCENTAGES,
                NUMERIC_VALUES,
                NOMINAL_VALUES);
    }

    private static Options typedAggregateSampledValuesOptions() {
        return new Options(
                false,
                PipelineMode.FLAT,
                OperatorOrdering.TYPE_GROUPED,
                false,
                false,
                false,
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                3,
                10,
                PROBABILITIES,
                PERCENTAGES,
                NUMERIC_VALUES,
                NOMINAL_VALUES);
    }

    private static void assertMatches(String grammarFile, String generatedGrammar) throws IOException {
        String existingGrammar = Files.readString(LCL_GRAMMAR_DIR.resolve(grammarFile));
        if (!existingGrammar.equals(generatedGrammar)) {
            throw new IllegalStateException(diffMessage(grammarFile, existingGrammar, generatedGrammar));
        }
    }

    private static String diffMessage(String grammarFile, String existingGrammar, String generatedGrammar) {
        String[] existingLines = existingGrammar.split("\\R", -1);
        String[] generatedLines = generatedGrammar.split("\\R", -1);
        int maxLines = Math.max(existingLines.length, generatedLines.length);
        for (int i = 0; i < maxLines; i++) {
            String existing = i < existingLines.length ? existingLines[i] : "<missing>";
            String generated = i < generatedLines.length ? generatedLines[i] : "<missing>";
            if (!existing.equals(generated)) {
                return "Generated grammar does not match " + grammarFile
                        + " at line " + (i + 1)
                        + System.lineSeparator()
                        + "existing : " + existing
                        + System.lineSeparator()
                        + "generated: " + generated;
            }
        }
        return "Generated grammar does not match " + grammarFile;
    }

    private static String oldAggregateOldValuesGrammar() {
        return String.join("\n",
                "<pipeline> ::= <operator> | <operator> <pipeline>",
                "<operator> ::= <filter> | <map_duplicate> | <map_noise> | <map_aggregate>",
                "<filter> ::= <attribute> <condition> <value>",
                "<map_duplicate> ::= <probability>",
                "<map_noise> ::= <attribute> <percentage>",
                "<map_aggregate> ::= <attribute> <agg_fun> <window_size>",
                "<attribute> ::= f1 | f2 | f3 | f4 | f5 | f6 | f7 | f8 | f9 | f10 | f11",
                "<condition> ::= lt | gt",
                "<value> ::= <dig> . <dig> <dig> E <sign> <dig>",
                "<dig> ::= 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9",
                "<sign> ::= + | -",
                "<agg_fun> ::= min | avg | max",
                "<window_size> ::= 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10",
                "<probability> ::= 0.1 | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 | 1.0",
                "<percentage> ::= 0.01 | 0.05 | 0.10 | 0.25")
                + "\n";
    }
}
