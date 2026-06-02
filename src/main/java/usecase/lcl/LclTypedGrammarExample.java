package usecase.lcl;

import grammar.generator.FieldDefinition;
import grammar.generator.FieldType;
import grammar.generator.TypedGrammarGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class LclTypedGrammarExample {

    private LclTypedGrammarExample() {
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
        throw new IllegalArgumentException("Usage: LclTypedGrammarExample [outputPath]");
    }

    public static List<FieldDefinition> fields() {
        return List.of(
                FieldDefinition.of("f1", FieldType.CONTINUOUS_NUMERIC),
                FieldDefinition.of("f2", FieldType.CONTINUOUS_NUMERIC),
                FieldDefinition.of("f3", FieldType.CONTINUOUS_NUMERIC));
    }
}
