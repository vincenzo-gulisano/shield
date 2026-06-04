package grammar;

import grammar.utils.CSVAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class GrammarGeneratorAggregate {


    private static final Logger logger = LoggerFactory.getLogger(GrammarGeneratorAggregate.class);

    public static void main(String[] args) throws IOException {

        final String grammarPath = "src/main/resources/grammars/airQuality/airQuality_generated-grammar-aggregate.bnf";
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
        final int minWindow = 3;
        final int maxWindow = 10;

        StringBuilder sb = new StringBuilder();

        sb.append("<pipeline> ::= <operator> | <operator> <pipeline>\n");
        sb.append("<operator> ::= <filter> | <map_duplicate> | <map_noise> | <map_aggregate>\n");

        // Operator definition
        sb.append("<filter> ::= <attribute> <condition> <value>\n");
        sb.append("<map_duplicate> ::= <probability>\n");
        sb.append("<map_noise> ::= <attribute> <percentage>\n");
        sb.append("<map_aggregate> ::= <attribute> <agg_fun> <window_size>\n");

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

        sb.append("<agg_fun> ::= min | avg | max\n");
        sb.append("<window_size> ::= ");
        StringJoiner wJoiner = new StringJoiner(" | ");
        for (int w = minWindow; w <= maxWindow; w++) {
            wJoiner.add(Integer.toString(w));
        }
        sb.append(wJoiner).append("\n");

        sb.append("<probability> ::= 0.1 | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 | 1.0\n");
        sb.append("<percentage> ::= 0.01 | 0.05 | 0.10 | 0.25\n");

        try (FileWriter fw = new FileWriter(filePath)) {
            fw.write(sb.toString());
            logger.info("Grammar generated successfully: {}", filePath);
        } catch (IOException e) {
            logger.error("Error writing grammar to {}", filePath, e);
            throw new RuntimeException(e);
        }
    }

}
