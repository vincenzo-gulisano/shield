package usecase.analysis.performance;

import component.source.SourceFunction;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import query.LiebreAnonymizationQueryFromGraph;
import usecase.common.Tuple;
import usecase.geolife.mobility.GeoLifeMobilityContributorCondition;
import usecase.geolife.mobility.GeoLifeMobilityMainQuery;
import usecase.geolife.mobility.GeoLifeTupleReader;
import usecase.lcl.flow.LclFlowAllFieldsMainQuery;
import usecase.lcl.flow.LclFlowContributorCondition;
import usecase.lcl.flow.LclFlowTupleReader;

public final class BenchmarkRankedSolutionQueries {

    private static final long TIMEOUT_EXTRA_MILLIS = 30_000L;
    private static final List<String> INDEX_HEADER = List.of(
            "run",
            "id",
            "ranking_mode",
            "selection_rank",
            "seed",
            "source_csv",
            "source_row",
            "original_rank",
            "repetition",
            "status",
            "error",
            "per_second_csv",
            "graph_hash",
            "rate_mode",
            "rate_start_per_s",
            "rate_final_per_s",
            "rate_steps",
            "rate_step_millis",
            "rate_bucket_millis",
            "duration_ms",
            "warm_up_millis",
            "cool_down_millis",
            "input_count",
            "output_count",
            "input_throughput_per_s",
            "output_throughput_per_s",
            "avg_latency_ms",
            "min_latency_ms",
            "max_latency_ms");

    private BenchmarkRankedSolutionQueries() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        List<Map<String, String>> rows = readCsv(options.manifestPath()).rows();
        Map<String, RateSettings> ratePlan = options.ratePlanPath() == null
                ? Map.of()
                : readRatePlan(options.ratePlanPath(), options.warmUpMillis(), options.coolDownMillis());
        List<Tuple> inputTuples = loadInputTuples(options.useCase(), options.inputCsvPath());
        initializeContributorCondition(options.useCase(), inputTuples);

        Files.createDirectories(options.outputDir());
        Path indexPath = options.outputDir().resolve("index.csv");
        try (BufferedWriter indexWriter = Files.newBufferedWriter(indexPath, StandardCharsets.UTF_8)) {
            writeCsvRow(indexWriter, INDEX_HEADER);
            int run = 0;
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Map<String, String> row = rows.get(rowIndex);
                String individual = required(row, "individual", rowIndex + 2);
                Graph<OperatorRepresentation, ArcType> graph = QueryMapper.parseGraphFromString(individual);
                String graphHash = shortHash(individual);
                RateSettings rateSettings = rateSettingsFor(row, rowIndex + 2, options.fixedRateSettings(), ratePlan);
                for (int repetition = 1; repetition <= options.repetitions(); repetition++) {
                    run++;
                    Path perSecondPath = options.outputDir().resolve(runFileName(run, row, repetition));
                    Map<String, String> indexRow = runOne(
                            run,
                            row,
                            repetition,
                            graph,
                            graphHash,
                            inputTuples,
                            options.minRunMillis(),
                            options.warmUpMillis(),
                            options.coolDownMillis(),
                            options.timeoutExtraMillis(),
                            rateSettings,
                            perSecondPath);
                    writeCsvRow(indexWriter, values(indexRow, INDEX_HEADER));
                    indexWriter.flush();
                    System.out.printf(
                            Locale.ROOT,
                            "Run %d/%d %s rep %d -> %s%n",
                            run,
                            rows.size() * options.repetitions(),
                            row.getOrDefault("id", ""),
                            repetition,
                            indexRow.get("status"));
                }
            }
        }
        System.out.println("Wrote benchmark index to " + indexPath);
        System.exit(0);
    }

    private static Map<String, String> runOne(
            int run,
            Map<String, String> manifestRow,
            int repetition,
            Graph<OperatorRepresentation, ArcType> graph,
            String graphHash,
            List<Tuple> inputTuples,
            long minRunMillis,
            long warmUpMillis,
            long coolDownMillis,
            long timeoutExtraMillis,
            RateSettings rateSettings,
            Path perSecondPath) {
        SecondStatsRecorder stats = new SecondStatsRecorder(warmUpMillis, coolDownMillis);
        Map<String, String> row = baseIndexRow(run, manifestRow, repetition, perSecondPath, graphHash, rateSettings);
        long runMillis = rateSettings == null ? minRunMillis : rateSettings.totalRunMillis();
        stats.start();
        try {
            SourceFunction<Tuple> sourceFunction = rateSettings == null
                    ? new LoopingTupleSourceFunction(inputTuples, runMillis, stats)
                    : new SteppedRateLoopingTupleSourceFunction(
                            inputTuples,
                            rateSettings.startRatePerSecond(),
                            rateSettings.finalRatePerSecond(),
                            rateSettings.steps(),
                            rateSettings.stepMillis(),
                            rateSettings.bucketMillis(),
                            stats);
            new LiebreAnonymizationQueryFromGraph().processAnonymizationQuery(
                    graph,
                    sourceFunction,
                    new LatencyRecordingSinkFunction(stats),
                    runMillis + timeoutExtraMillis);
            row.put("status", "ok");
        } catch (Exception e) {
            row.put("status", "error");
            row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            stats.stop();
        }
        try {
            stats.writePerSecondCsv(perSecondPath);
        } catch (IOException e) {
            row.put("status", "error");
            row.put("error", appendError(row.get("error"), "Cannot write per-second CSV: " + e.getMessage()));
        }
        addSummary(row, stats.summary());
        return row;
    }

    private static Map<String, String> baseIndexRow(
            int run,
            Map<String, String> manifestRow,
            int repetition,
            Path perSecondPath,
            String graphHash,
            RateSettings rateSettings) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("run", Integer.toString(run));
        row.put("id", manifestRow.getOrDefault("id", ""));
        row.put("ranking_mode", manifestRow.getOrDefault("ranking_mode", ""));
        row.put("selection_rank", manifestRow.getOrDefault("selection_rank", ""));
        row.put("seed", manifestRow.getOrDefault("seed", ""));
        row.put("source_csv", manifestRow.getOrDefault("source_csv", ""));
        row.put("source_row", manifestRow.getOrDefault("source_row", ""));
        row.put("original_rank", manifestRow.getOrDefault("original_rank", ""));
        row.put("repetition", Integer.toString(repetition));
        row.put("status", "");
        row.put("error", "");
        row.put("per_second_csv", perSecondPath.toString());
        row.put("graph_hash", graphHash);
        row.put("rate_mode", rateSettings == null ? "unlimited" : "stepped");
        row.put("rate_start_per_s", rateSettings == null ? "" : formatDouble(rateSettings.startRatePerSecond()));
        row.put("rate_final_per_s", rateSettings == null ? "" : formatDouble(rateSettings.finalRatePerSecond()));
        row.put("rate_steps", rateSettings == null ? "" : Integer.toString(rateSettings.steps()));
        row.put("rate_step_millis", rateSettings == null ? "" : Long.toString(rateSettings.stepMillis()));
        row.put("rate_bucket_millis", rateSettings == null ? "" : Long.toString(rateSettings.bucketMillis()));
        return row;
    }

    private static void addSummary(Map<String, String> row, SecondStatsRecorder.RunSummary summary) {
        row.put("duration_ms", Long.toString(summary.durationMillis()));
        row.put("warm_up_millis", Long.toString(summary.warmUpMillis()));
        row.put("cool_down_millis", Long.toString(summary.coolDownMillis()));
        row.put("input_count", Long.toString(summary.inputCount()));
        row.put("output_count", Long.toString(summary.outputCount()));
        row.put("input_throughput_per_s", summary.inputThroughputPerSecond());
        row.put("output_throughput_per_s", summary.outputThroughputPerSecond());
        row.put("avg_latency_ms", summary.averageLatencyMillis());
        row.put("min_latency_ms", summary.minLatencyMillis());
        row.put("max_latency_ms", summary.maxLatencyMillis());
    }

    private static List<Tuple> loadInputTuples(UseCase useCase, String inputCsvPath) {
        List<Tuple> tuples = switch (useCase) {
            case LCL_FLOW -> LclFlowTupleReader.loadUnchecked(inputCsvPath);
            case GEOLIFE_MOBILITY -> GeoLifeTupleReader.loadUnchecked(inputCsvPath);
        };
        return tuples.stream()
                .sorted(Comparator.comparingLong(Tuple::getTimestamp).thenComparing(Tuple::getKey))
                .toList();
    }

    private static void initializeContributorCondition(UseCase useCase, List<Tuple> inputTuples) {
        long minTimestamp = inputTuples.stream().mapToLong(Tuple::getTimestamp).min().orElseThrow();
        long maxTimestamp = inputTuples.stream().mapToLong(Tuple::getTimestamp).max().orElseThrow();
        switch (useCase) {
            case LCL_FLOW -> LclFlowContributorCondition.initializeFromProvenance(
                    inputTuples,
                    LclFlowAllFieldsMainQuery.Settings.defaults()
                            .withInstrumentationRange(minTimestamp, maxTimestamp));
            case GEOLIFE_MOBILITY -> {
                GeoLifeMobilityMainQuery.Settings settings = GeoLifeMobilityMainQuery.Settings.defaults();
                long maxWindowSizeMillis = Math.max(
                        settings.userWindowSizeMillis(),
                        settings.cellWindowSizeMillis());
                GeoLifeMobilityContributorCondition.initializeFromProvenance(
                        inputTuples,
                        settings.withInstrumentationRange(minTimestamp - maxWindowSizeMillis, maxTimestamp));
            }
        }
    }

    private static String runFileName(int run, Map<String, String> row, int repetition) {
        return String.format(
                Locale.ROOT,
                "run-%04d-%s-%s-r%s-rep%02d.csv",
                run,
                cleanFilePart(row.getOrDefault("id", "id")),
                cleanFilePart(row.getOrDefault("ranking_mode", "ranking")),
                cleanFilePart(row.getOrDefault("selection_rank", "0")),
                repetition);
    }

    private static String cleanFilePart(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isBlank() ? "x" : cleaned;
    }

    private static String required(Map<String, String> row, String column, int sourceRow) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing column '" + column + "' at manifest row " + sourceRow);
        }
        return value;
    }

    private static String shortHash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String appendError(String existing, String added) {
        if (existing == null || existing.isBlank()) {
            return added;
        }
        return existing + "; " + added;
    }

    private static Map<String, RateSettings> readRatePlan(
            Path path,
            long warmUpMillis,
            long coolDownMillis) throws IOException {
        List<Map<String, String>> rows = readCsv(path).rows();
        Map<String, RateSettings> ratePlan = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int sourceRow = i + 2;
            String id = required(row, "id", sourceRow);
            RateSettings settings = rateSettingsFromRow(row, sourceRow);
            validateRunWindow(settings.totalRunMillis(), warmUpMillis, coolDownMillis);
            ratePlan.put(id, settings);
        }
        return ratePlan;
    }

    private static RateSettings rateSettingsFor(
            Map<String, String> manifestRow,
            int sourceRow,
            RateSettings fixedRateSettings,
            Map<String, RateSettings> ratePlan) {
        if (fixedRateSettings != null) {
            return fixedRateSettings;
        }
        if (ratePlan.isEmpty()) {
            return null;
        }
        String id = required(manifestRow, "id", sourceRow);
        RateSettings rateSettings = ratePlan.get(id);
        if (rateSettings == null) {
            throw new IllegalArgumentException("No rate-plan row for id '" + id + "' at manifest row " + sourceRow);
        }
        return rateSettings;
    }

    private static RateSettings parseFixedRateSettings(Map<String, String> values) {
        boolean anyRateArgument = values.containsKey("rate-start-per-second")
                || values.containsKey("rate-final-per-second")
                || values.containsKey("rate-steps")
                || values.containsKey("rate-step-seconds");
        if (!anyRateArgument) {
            return null;
        }
        if (!values.containsKey("rate-start-per-second")
                || !values.containsKey("rate-final-per-second")
                || !values.containsKey("rate-steps")
                || !values.containsKey("rate-step-seconds")) {
            throw new IllegalArgumentException(
                    "Stepped runs require --rate-start-per-second, --rate-final-per-second, "
                            + "--rate-steps, and --rate-step-seconds");
        }
        return new RateSettings(
                Double.parseDouble(values.get("rate-start-per-second")),
                Double.parseDouble(values.get("rate-final-per-second")),
                Integer.parseInt(values.get("rate-steps")),
                1000L * Long.parseLong(values.get("rate-step-seconds")),
                Long.parseLong(values.getOrDefault("rate-bucket-millis", "50")));
    }

    private static RateSettings rateSettingsFromRow(Map<String, String> row, int sourceRow) {
        return new RateSettings(
                Double.parseDouble(required(row, "start_rate_per_s", sourceRow)),
                Double.parseDouble(required(row, "final_rate_per_s", sourceRow)),
                Integer.parseInt(required(row, "steps", sourceRow)),
                1000L * Long.parseLong(required(row, "step_seconds", sourceRow)),
                Long.parseLong(row.getOrDefault("bucket_millis", "50")));
    }

    private static void validateRunWindow(long runMillis, long warmUpMillis, long coolDownMillis) {
        if (runMillis <= warmUpMillis + coolDownMillis) {
            throw new IllegalArgumentException("Run duration must be longer than warm-up + cool-down");
        }
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static CsvTable readCsv(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("Empty CSV file: " + path);
            }
            List<String> header = parseCsvLine(headerLine);
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
                            "Malformed CSV row " + lineNumber + ": expected "
                                    + header.size() + " fields, got " + values.size());
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), values.get(i));
                }
                rows.add(row);
            }
            return new CsvTable(rows);
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

    private static List<String> values(Map<String, String> row, List<String> header) {
        List<String> values = new ArrayList<>(header.size());
        for (String column : header) {
            values.add(row.getOrDefault(column, ""));
        }
        return values;
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

    private record CsvTable(List<Map<String, String>> rows) {
    }

    private record Options(
            UseCase useCase,
            Path manifestPath,
            Path outputDir,
            String inputCsvPath,
            int repetitions,
            long minRunMillis,
            long warmUpMillis,
            long coolDownMillis,
            long timeoutExtraMillis,
            Path ratePlanPath,
            RateSettings fixedRateSettings) {

        private static Options parse(String[] args) {
            Map<String, String> values = parseNamedArgs(args);
            if (values.containsKey("help")) {
                printUsageAndExit();
            }
            UseCase useCase = UseCase.fromString(required(values, "use-case"));
            long minRunMillis = 1000L * Long.parseLong(values.getOrDefault("min-run-seconds", "30"));
            long warmUpMillis = Long.parseLong(values.getOrDefault("warm-up-millis", "0"));
            long coolDownMillis = Long.parseLong(values.getOrDefault("cool-down-millis", "0"));
            long timeoutExtraMillis = 1000L * Long.parseLong(values.getOrDefault(
                    "timeout-extra-seconds",
                    Long.toString(TIMEOUT_EXTRA_MILLIS / 1000L)));
            Path ratePlanPath = values.containsKey("rate-plan") ? Path.of(values.get("rate-plan")) : null;
            RateSettings fixedRateSettings = parseFixedRateSettings(values);
            if (ratePlanPath != null && fixedRateSettings != null) {
                throw new IllegalArgumentException("Use either --rate-plan or explicit stepped-rate arguments, not both");
            }
            if (ratePlanPath == null) {
                validateRunWindow(
                        fixedRateSettings == null ? minRunMillis : fixedRateSettings.totalRunMillis(),
                        warmUpMillis,
                        coolDownMillis);
            }
            return new Options(
                    useCase,
                    Path.of(required(values, "manifest")),
                    Path.of(required(values, "output-dir")),
                    values.getOrDefault("input-csv-path", defaultInputCsvPath(useCase)),
                    Integer.parseInt(values.getOrDefault("repetitions", "3")),
                    minRunMillis,
                    warmUpMillis,
                    coolDownMillis,
                    timeoutExtraMillis,
                    ratePlanPath,
                    fixedRateSettings);
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
                        usecase.analysis.performance.BenchmarkRankedSolutionQueries \\
                        --use-case lcl-flow|geolife-mobility \\
                        --manifest selected_queries.csv \\
                        --output-dir output/benchmark \\
                        [--input-csv-path datasets/...] \\
                        [--repetitions 3] \\
                        [--min-run-seconds 30] \\
                        [--warm-up-millis 0] \\
                        [--cool-down-millis 0] \\
                        [--timeout-extra-seconds 30] \\
                        [--rate-plan rate-plan.csv] \\
                        [--rate-start-per-second 100 --rate-final-per-second 1000 \\
                         --rate-steps 6 --rate-step-seconds 30 --rate-bucket-millis 50]
                    """);
            System.exit(0);
        }
    }

    private record RateSettings(
            double startRatePerSecond,
            double finalRatePerSecond,
            int steps,
            long stepMillis,
            long bucketMillis) {

        private RateSettings {
            if (startRatePerSecond <= 0.0d || finalRatePerSecond <= 0.0d) {
                throw new IllegalArgumentException("rates must be positive");
            }
            if (steps <= 0) {
                throw new IllegalArgumentException("steps must be positive");
            }
            if (stepMillis <= 0L) {
                throw new IllegalArgumentException("stepMillis must be positive");
            }
            if (bucketMillis <= 0L) {
                throw new IllegalArgumentException("bucketMillis must be positive");
            }
        }

        private long totalRunMillis() {
            return steps * stepMillis;
        }
    }

    private enum UseCase {
        LCL_FLOW("lcl-flow"),
        GEOLIFE_MOBILITY("geolife-mobility");

        private final String id;

        UseCase(String id) {
            this.id = id;
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
