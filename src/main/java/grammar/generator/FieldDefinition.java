package grammar.generator;

import java.util.List;
import java.util.Objects;

/**
 * Metadata for one tuple field used by {@link TypedGrammarGenerator}.
 *
 * <p>The optional {@code values} list is mainly useful for nominal categorical fields. When values
 * are provided, the generated grammar can emit field-specific categorical filter values, such as
 * {@code <f1_value> ::= 1 | 2}. When values are omitted, the generator falls back to generic
 * categorical value tokens configured in {@link TypedGrammarGenerator.Options}.
 */
public record FieldDefinition(String name, FieldType type, List<String> values) {

    public FieldDefinition {
        name = requireGrammarToken(name, "field name");
        type = Objects.requireNonNull(type, "type cannot be null");
        values = List.copyOf(Objects.requireNonNull(values, "values cannot be null"));
        for (String value : values) {
            requireGrammarToken(value, "field value");
        }
    }

    /**
     * Build a field definition without an explicit domain.
     */
    public static FieldDefinition of(String name, FieldType type) {
        return new FieldDefinition(name, type, List.of());
    }

    /**
     * Build a field definition with an explicit list of valid values.
     */
    public static FieldDefinition withValues(String name, FieldType type, String... values) {
        return new FieldDefinition(name, type, List.of(values));
    }

    static String requireGrammarToken(String token, String description) {
        Objects.requireNonNull(token, description + " cannot be null");
        String stripped = token.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(description + " cannot be blank");
        }
        if (!stripped.equals(token) || stripped.chars().anyMatch(Character::isWhitespace) || stripped.contains("|")
                || stripped.contains("<") || stripped.contains(">")) {
            throw new IllegalArgumentException(
                    description + " must be a single grammar token without whitespace or BNF separators: " + token);
        }
        return stripped;
    }
}
