package grammar;

import grammar.utils.CSVAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class GrammarGeneratorOnlyFilters {

    private static final Logger logger = LoggerFactory.getLogger(GrammarGeneratorOnlyFilters.class);

    public static void main(String[] args) throws IOException {

        final String grammarPath = "src/main/resources/grammars/airQuality/airQuality_generated-grammar-filters.bnf";
        final String csvPath = "datasets/airQuality_parallel.csv";
        final String keyColumn = "SensorID";

        List<String> excludedColumns = new ArrayList<>(List.of("timestamp", "ID"));
        if (keyColumn != null && !keyColumn.isEmpty()) {
            excludedColumns.add(keyColumn);
        }

        // Extract attributes from a CSV file
        List<String> attributes = CSVAnalyzer.extractAttributes(csvPath, excludedColumns);
        // Grammar generation
        generateGrammar(attributes, grammarPath);
    }

    // Generate a grammar to define operators like filters as strings and save the grammar in a file
    public static void generateGrammar(List<String> attributes, String filePath) {
        StringBuilder sb = new StringBuilder();

        sb.append("<pipeline> ::= <operator> | <operator> <pipeline>\n");
        sb.append("<operator> ::= <filter>\n");

        // Operator definition
        sb.append("<filter> ::= <attribute> <condition> <value>\n");

        sb.append("<attribute> ::= ");
        StringJoiner attrJoiner = new StringJoiner(" | ");
        for (String attribute : attributes) {
            attrJoiner.add("'" + attribute + "'");
        }
        sb.append(attrJoiner).append("\n");

        sb.append("<condition> ::= lt | gt\n");
        sb.append("<value> ::= <dig> . <dig> <dig> E <sign> <dig>\n");
        sb.append("<dig> ::= 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9\n");
        sb.append("<sign> ::= + | -\n");

        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(sb.toString());
            logger.info("Grammar generated successfully: {}", filePath);
        } catch (IOException e) {
            logger.error("Error writing grammar to {}", filePath, e);
            throw new RuntimeException(e);
        }
    }
}
