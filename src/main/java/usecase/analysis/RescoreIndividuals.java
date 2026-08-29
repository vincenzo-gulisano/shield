package usecase.analysis;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Function;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import metrics.privacy.LinkageAttackPrivacy;
import problem.utils.PrivacyMetricChoice;
import usecase.geolife.mobility.GeoLifeMobilityStreamAnonymizationProblem;
import usecase.geolife.mobility.GeoLifeTupleReader;
import usecase.lcl.flow.LclFlowStreamAnonymizationProblem;
import usecase.lcl.flow.LclFlowTupleReader;

public final class RescoreIndividuals {

    private static final List<String> REQUIRED_COLUMNS = List.of("individual");
    private static final List<String> RESCORE_COLUMNS = List.of(
            "rescore_use_case",
            "rescore_input_csv_path",
            "rescore_privacy_metric",
            "rescore_k",
            "rescore_linkage_true_rank_max",
            "rescored_privacy",
            "rescored_semantics",
            "rescored_fidelity",
            "rescored_min_privacy_semantics_fidelity",
            "rescored_distance_from_perfect");

    private RescoreIndividuals() {
    }

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        CsvTable table = readCsv(options.manifestPath());
        validateRequiredColumns(table.header());
        Function<Graph<OperatorRepresentation, ArcType>, SequencedMap<String, Double>> qualityFunction =
                qualityFunction(options);
        Map<String, Map<String, Double>> cache = new HashMap<>();
        List<Map<String, String>> rescoredRows = new ArrayList<>(table.rows().size());

        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            Map<String, String> row = table.rows().get(rowIndex);
            String individual = row.getOrDefault("individual", "").trim();
            if (individual.isEmpty()) {
                throw new IllegalArgumentException("Empty individual string at input row " + (rowIndex + 2));
            }

            Map<String, Double> qualities = cache.get(individual);
            if (qualities == null) {
                Graph<OperatorRepresentation, ArcType> graph = QueryMapper.parseGraphFromString(individual);
                qualities = new LinkedHashMap<>(qualityFunction.apply(graph));
                cache.put(individual, qualities);
            }
            rescoredRows.add(rescoredRow(row, options, qualities));
            if ((rowIndex + 1) % 10 == 0 || rowIndex + 1 == table.rows().size()) {
                System.out.printf(
                        Locale.ROOT,
                        "Rescored %d/%d rows (%d unique graphs)%n",
                        rowIndex + 1,
                        table.rows().size(),
                        cache.size());
            }
        }

        writeCsv(options.outputPath(), outputHeader(table.header()), rescoredRows);
        System.out.printf(
                Locale.ROOT,
                "Wrote %d rescored rows to %s%n",
                rescoredRows.size(),
                options.outputPath());
        System.exit(0);
    }

    private static Function<Graph<OperatorRepresentation, ArcType>, SequencedMap<String, Double>> qualityFunction(
            Options options) {
        return switch (options.useCase()) {
            case LCL_FLOW -> new LclFlowStreamAnonymizationProblem(
                    options.inputCsvPath(),
                    options.privacyMetric(),
                    options.semanticsF1Threshold(),
                    options.k(),
                    options.linkageTrueRankMax()).qualityFunction();
            case GEOLIFE_MOBILITY -> new GeoLifeMobilityStreamAnonymizationProblem(
                    options.inputCsvPath(),
                    options.privacyMetric(),
                    options.semanticsF1Threshold(),
                    options.k(),
                    options.linkageTrueRankMax()).qualityFunction();
        };
    }

    private static Map<String, String> rescoredRow(
            Map<String, String> row,
            Options options,
            Map<String, Double> qualities) {
        double privacy = requiredQuality(qualities, "privacy");
        double semantics = requiredQuality(qualities, "semantics");
        double fidelity = requiredQuality(qualities, "fidelity");
        double min = Math.min(privacy, Math.min(semantics, fidelity));
        double distance = Math.sqrt(
                Math.pow(1.0d - privacy, 2.0d)
                        + Math.pow(1.0d - semantics, 2.0d)
                        + Math.pow(1.0d - fidelity, 2.0d));

        Map<String, String> output = new LinkedHashMap<>(row);
        output.put("rescore_use_case", options.useCase().id());
        output.put("rescore_input_csv_path", options.inputCsvPath());
        output.put("rescore_privacy_metric", options.privacyMetric().name());
        output.put("rescore_k", Integer.toString(options.k()));
        output.put("rescore_linkage_true_rank_max", Integer.toString(options.linkageTrueRankMax()));
        output.put("rescored_privacy", formatDouble(privacy));
        output.put("rescored_semantics", formatDouble(semantics));
        output.put("rescored_fidelity", formatDouble(fidelity));
        output.put("rescored_min_privacy_semantics_fidelity", formatDouble(min));
        output.put("rescored_distance_from_perfect", formatDouble(distance));
        return output;
    }

    private static double requiredQuality(Map<String, Double> qualities, String name) {
        Double value = qualities.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Quality map is missing '" + name + "': " + qualities);
        }
        return value;
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.12g", value);
    }

    private static List<String> outputHeader(List<String> inputHeader) {
        LinkedHashSet<String> header = new LinkedHashSet<>(inputHeader);
        header.addAll(RESCORE_COLUMNS);
        return List.copyOf(header);
    }

    private static void validateRequiredColumns(List<String> header) {
        for (String column : REQUIRED_COLUMNS) {
            if (!header.contains(column)) {
                throw new IllegalArgumentException("Manifest is missing required column: " + column);
            }
        }
    }

    private static CsvTable readCsv(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("Empty manifest CSV: " + path);
            }
            List<String> header = parseCsvLine(headerLine).stream()
                    .map(String::trim)
                    .toList();
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> values = parseCsvLine(line);
                if (values.size() != header.size()) {
                    throw new IllegalArgumentException(
                            "Malformed CSV row " + lineNumber + " in " + path + ": expected "
                                    + header.size() + " fields, got " + values.size());
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), values.get(i));
                }
                rows.add(row);
            }
            return new CsvTable(header, rows);
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted CSV field: " + line);
        }
        values.add(current.toString());
        return values;
    }

    private static void writeCsv(
            Path path,
            List<String> header,
            List<Map<String, String>> rows) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writeCsvRow(writer, header);
            for (Map<String, String> row : rows) {
                List<String> values = new ArrayList<>(header.size());
                for (String column : header) {
                    values.add(row.getOrDefault(column, ""));
                }
                writeCsvRow(writer, values);
            }
        }
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escapeCsv(values.get(i)));
        }
        writer.newLine();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record CsvTable(List<String> header, List<Map<String, String>> rows) {
    }

    private record Options(
            UseCase useCase,
            Path manifestPath,
            Path outputPath,
            String inputCsvPath,
            PrivacyMetricChoice privacyMetric,
            double semanticsF1Threshold,
            int k,
            int linkageTrueRankMax) {

        private static Options parse(String[] args) {
            Map<String, String> values = parseNamedArgs(args);
            if (values.containsKey("help")) {
                printUsageAndExit();
            }
            UseCase useCase = UseCase.fromString(required(values, "use-case"));
            return new Options(
                    useCase,
                    Path.of(required(values, "manifest")),
                    Path.of(required(values, "output")),
                    values.getOrDefault("input-csv-path", defaultInputCsvPath(useCase)),
                    PrivacyMetricChoice.valueOf(values.getOrDefault(
                            "privacy-metric",
                            PrivacyMetricChoice.LINKAGE_ATTACK_TRUE_RANK_SCORE.name())),
                    Double.parseDouble(values.getOrDefault("semantics-f1-threshold", "0.02")),
                    Integer.parseInt(values.getOrDefault("k", "20")),
                    Integer.parseInt(values.getOrDefault(
                            "linkage-true-rank-max",
                            Integer.toString(LinkageAttackPrivacy.DEFAULT_TRUE_RANK_MAX))));
        }

        private static Map<String, String> parseNamedArgs(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Expected --name argument, got: " + arg);
                }
                String key = arg.substring(2);
                if ("help".equals(key)) {
                    values.put(key, "true");
                    continue;
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for argument: " + arg);
                }
                values.put(key, args[++i]);
            }
            return values;
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required argument: --" + key);
            }
            return value;
        }

        private static String defaultInputCsvPath(UseCase useCase) {
            return switch (useCase) {
                case LCL_FLOW -> LclFlowTupleReader.DEFAULT_RESOURCE;
                case GEOLIFE_MOBILITY -> GeoLifeTupleReader.DEFAULT_RESOURCE;
            };
        }

        private static void printUsageAndExit() {
            System.out.println("""
                    Usage:
                      java -cp target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar \\
                        usecase.analysis.RescoreIndividuals \\
                        --use-case lcl-flow|geolife-mobility \\
                        --manifest selected_queries.csv \\
                        --output rescored.csv \\
                        [--input-csv-path datasets/...] \\
                        [--privacy-metric LINKAGE_ATTACK_TRUE_RANK_SCORE] \\
                        [--k 20] \\
                        [--semantics-f1-threshold 0.02] \\
                        [--linkage-true-rank-max 50]
                    """);
            System.exit(0);
        }
    }

    private enum UseCase {
        LCL_FLOW("lcl-flow"),
        GEOLIFE_MOBILITY("geolife-mobility");

        private final String id;

        UseCase(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }

        private static UseCase fromString(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (UseCase useCase : values()) {
                if (useCase.id.equals(normalized)) {
                    return useCase;
                }
            }
            throw new IllegalArgumentException("Unknown use case: " + value);
        }
    }
}
