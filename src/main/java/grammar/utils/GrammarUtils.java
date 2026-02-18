package grammar.utils;

import java.util.StringJoiner;

public class GrammarUtils {

    private static final int DECIMAL_PRECISION_DIGITS = 1;

    // Generate a rule for the integer part with a number of digits between minIntDigits and maxIntDigits
    public static void generateIntegerRule(StringBuilder sb, String ruleName, CSVAnalyzer.AttributeStats stats) {
        sb.append(ruleName).append(" ::= ");
        StringJoiner options = new StringJoiner(" | ");

        for (int i = stats.minIntDigits(); i <= stats.maxIntDigits(); i++) {
            StringJoiner digits = new StringJoiner(" ");
            if (i > 1) {
                digits.add("<non_zero_digit>");
                for (int j = 1; j < i; j++) digits.add("<digit>");
            } else {
                digits.add("<digit>");
            }
            options.add(digits.toString());
        }

        sb.append(options).append("\n");
    }

    // Generate a rule for the fixed fractional part
    public static void generateFixedFractionRule(StringBuilder sb, String ruleName) {
        sb.append(ruleName).append(" ::= ");
        StringJoiner digits = new StringJoiner(" ");
        for (int i = 0; i < DECIMAL_PRECISION_DIGITS; i++) {
            digits.add("<digit>");
        }
        sb.append(digits).append("\n");
    }

    // Helper method to clean an attribute name
    public static String cleanAttribute(String attributeName) {
        // Replace invalid character with an underscore
        String cleaned = attributeName.replaceAll("[^a-zA-Z0-9]+", "_");
        // Remove underscore at the end or at the beginning of the string
        return cleaned.replaceAll("^_+|_+$", "");
    }

}
