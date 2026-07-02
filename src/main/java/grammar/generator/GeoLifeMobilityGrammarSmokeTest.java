package grammar.generator;

import grammar.generator.TypedGrammarGenerator.OperatorOrdering;
import grammar.generator.TypedGrammarGenerator.Options;
import grammar.generator.TypedGrammarGenerator.PipelineMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GeoLifeMobilityGrammarSmokeTest {

    private static final Path GEOLIFE_GRAMMAR_DIR = Path.of("src/main/resources/grammars/geolife");
    private static final List<FieldDefinition> GEOLIFE_FIELDS = List.of(
            FieldDefinition.of("f1", FieldType.CONTINUOUS_NUMERIC),
            FieldDefinition.of("f2", FieldType.CONTINUOUS_NUMERIC));
    private static final List<String> PROBABILITIES =
            List.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0");
    private static final List<String> PERCENTAGES = List.of("0.01", "0.05", "0.10", "0.25");
    private static final List<String> NUMERIC_VALUES = List.of("urir", "drir");
    private static final List<String> NOMINAL_VALUES = List.of("ucr", "dcr");

    private GeoLifeMobilityGrammarSmokeTest() {
    }

    public static void main(String[] args) throws IOException {
        assertMatches("geolife.mobility.01.bnf", mobility01Grammar());
        System.exit(0);
    }

    public static String mobility01Grammar() {
        return TypedGrammarGenerator.generate(GEOLIFE_FIELDS, mobility01Options());
    }

    private static Options mobility01Options() {
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
        String existingGrammar = Files.readString(GEOLIFE_GRAMMAR_DIR.resolve(grammarFile));
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
}
