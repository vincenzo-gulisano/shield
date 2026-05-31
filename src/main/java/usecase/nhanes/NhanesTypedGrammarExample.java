package usecase.nhanes;

import grammar.generator.FieldDefinition;
import grammar.generator.FieldType;
import grammar.generator.TypedGrammarGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Small executable example that generates a typed NHANES grammar.
 *
 * <p>Run without arguments to print the grammar to standard output. Pass one argument to write the
 * generated grammar to that path. This class is only a preview helper; no existing configuration
 * uses the generated grammar yet.
 */
public final class NhanesTypedGrammarExample {

    private NhanesTypedGrammarExample() {
    }

    public static void main(String[] args) throws IOException {
        String grammar = TypedGrammarGenerator.generate(fields(), TypedGrammarGenerator.Options.defaults());
        if (args.length == 0) {
            System.out.print(grammar);
            return;
        }
        if (args.length == 1) {
            TypedGrammarGenerator.write(fields(), TypedGrammarGenerator.Options.defaults(), Path.of(args[0]));
            return;
        }
        throw new IllegalArgumentException("Usage: NhanesTypedGrammarExample [outputPath]");
    }

    /**
     * NHANES field metadata for the current six-field tuple loader.
     */
    public static List<FieldDefinition> fields() {
        return List.of(
                FieldDefinition.withValues("f1", FieldType.NOMINAL_CATEGORICAL, "1", "2"),
                FieldDefinition.of("f2", FieldType.DISCRETE_NUMERIC),
                FieldDefinition.withValues("f3", FieldType.NOMINAL_CATEGORICAL, "1", "2", "3", "4", "6", "7"),
                FieldDefinition.of("f4", FieldType.CONTINUOUS_NUMERIC),
                FieldDefinition.of("f5", FieldType.CONTINUOUS_NUMERIC),
                FieldDefinition.of("f6", FieldType.CONTINUOUS_NUMERIC));
    }
}
