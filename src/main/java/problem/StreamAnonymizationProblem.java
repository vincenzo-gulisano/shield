package problem;

import event.DataLoader;
import event.EventFactory;
import event.GenericEvent;
import io.github.ericmedvet.jgea.core.distance.Distance;
import io.github.ericmedvet.jgea.core.problem.SimpleMOProblem;
import mappers.QueryRepresentation;
import metrics.performance.PerformanceSimilarity;
import metrics.performance.utils.StreamStatsWindow;
import metrics.privacy.*;
import metrics.results.F1Score;
import problem.utils.PrivacyMetricChoice;
import query.LiebreAnonymizationQuery;
import query.MainQueryAirQuality;
import query.MainQueryGeoLife;
import query.MainQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import query.LiebreContext;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

// Define the multi-objective optimization problem
public class StreamAnonymizationProblem implements SimpleMOProblem<QueryRepresentation, Double> {

    private static final Logger logger = LoggerFactory.getLogger(StreamAnonymizationProblem.class);

    // Define a static counter for unique query ID
    private static final AtomicLong queryCounter = new AtomicLong(0);

    static {
        // Notify the Terminator not to end after the first query has completed
        LiebreContext.setSingleQueryExecution(false);
    }

    // Define the objective to maximize for the multi-objective optimization
    private final static SequencedMap<String, Comparator<Double>> OBJECTIVES = new TreeMap<>(
            Map.ofEntries(
                    Map.entry("privacy", ((Comparator<Double>) Double::compareTo).reversed()),
                    Map.entry("results-similarity", ((Comparator<Double>) Double::compareTo).reversed()),
                    Map.entry("performance-similarity", ((Comparator<Double>) Double::compareTo).reversed())
            ));

    private final Distance<List<GenericEvent>> RESULTS_SIMILARITY;
    private final Distance<StreamStatsWindow> PERFORMANCE_SIMILARITY;

    private final KAnonymityPrivacyCardinality K_ANONYMITY_PRIVACY_CARDINALITY;

    private final String inputCsvPath;
    private final String keyColumn;
    private final List<GenericEvent> originalStream;
    private final List<GenericEvent> originalResults; // Ground truth results, calculated once in the constructor
    private final StreamStatsWindow originalStats;

    private final PrivacyMetricChoice privacyMetricChoice;
    private final List<String> attributes;
    private final long minTs;
    private final long maxTs;
    private final boolean isGeoLife;

    public StreamAnonymizationProblem(String inputCsvPath, String keyColumn, PrivacyMetricChoice privacyMetric, boolean isFilterOnly) throws Exception {
        this.inputCsvPath = inputCsvPath;
        this.keyColumn = keyColumn;

        String p = inputCsvPath.toLowerCase();
        if (p.contains("geolife")) isGeoLife = true;
        else if (p.contains("airquality")) isGeoLife = false;
        else throw new IllegalArgumentException("Unknown dataset in path: " + inputCsvPath);

        // Load the original stream of events from the CSV file
        DataLoader loader = new DataLoader(inputCsvPath, keyColumn);
        DataLoader.LoadResult result = loader.load();
        EventFactory.setNumericAttributes(new HashSet<>(result.numericAttributes()));

        this.originalStream = result.events();

        if (this.originalStream.isEmpty()) {
            throw new IllegalArgumentException(
                    "Empty input dataset: Problem cannot be initialized"
            );
        }

        this.attributes = result.numericAttributes();
        this.privacyMetricChoice = privacyMetric;
        this.minTs = originalStream.stream().mapToLong(GenericEvent::getTimestamp).min().getAsLong();
        this.maxTs = originalStream.stream().mapToLong(GenericEvent::getTimestamp).max().getAsLong();

        logger.info("Timestamp detected: minTs={}, maxTs={}", minTs, maxTs);

        K_ANONYMITY_PRIVACY_CARDINALITY = new KAnonymityPrivacyCardinality(this.originalStream, 50, this.attributes);
        // Execute the main query
        MainQueryResult baselineOutcome = isGeoLife
                ? MainQueryGeoLife.process(this.originalStream, "original", this.minTs, this.maxTs)
                : MainQueryAirQuality.process(this.originalStream, "original", this.minTs, this.maxTs);

        this.originalResults = baselineOutcome.events();
        this.originalStats = baselineOutcome.statsWindow();

        PERFORMANCE_SIMILARITY = new PerformanceSimilarity(this.originalStats, isFilterOnly);

        if (isGeoLife) {
            RESULTS_SIMILARITY = new F1Score(0.10, List.of("avg_X", "avg_Y"));
        } else {
            RESULTS_SIMILARITY = new F1Score(0.15, List.of("CO(GT)", "NO2(GT)"));
        }

        logger.info("Ground Truth generated");
    }

    @Override
    public SequencedMap<String, Comparator<Double>> comparators() {
        return OBJECTIVES;
    }

    @Override
    public Function<QueryRepresentation, SequencedMap<String, Double>> qualityFunction() {
        return intermediateRepr -> {
            // Build the results map
            SequencedMap<String, Double> qualities = new TreeMap<>();
            Long counter = queryCounter.getAndIncrement();
            String queryId = String.valueOf(counter);
            try {
                // Create an executable Liebre query and execute this anonymization query
                LiebreAnonymizationQuery liebreExecutor = new LiebreAnonymizationQuery();
                List<GenericEvent> modifiedEvents = liebreExecutor.processAnonymizationQuery(intermediateRepr, this.inputCsvPath, this.keyColumn);

                // Case with empty modified datastream
                if (modifiedEvents.isEmpty()) {
                    double privacyScoreEmpty;
                    // Based on the user choice, calculate the correct privacy metric
                    switch (privacyMetricChoice) {
                        case K_ANONYMITY_CARDINALITY_MAX -> privacyScoreEmpty = K_ANONYMITY_PRIVACY_CARDINALITY.applyWithMax(this.originalStream, modifiedEvents);
                        case K_ANONYMITY_CARDINALITY_Q99 -> privacyScoreEmpty = K_ANONYMITY_PRIVACY_CARDINALITY.applyWithQuantile99(this.originalStream, modifiedEvents);
                        case K_ANONYMITY_CARDINALITY -> privacyScoreEmpty = K_ANONYMITY_PRIVACY_CARDINALITY.apply(this.originalStream, modifiedEvents);
                        default -> privacyScoreEmpty = K_ANONYMITY_PRIVACY_CARDINALITY.apply(this.originalStream, modifiedEvents);
                    }
                    qualities.put("privacy", privacyScoreEmpty);
                    qualities.put("results-similarity", 0.0);
                    StreamStatsWindow emptyStats = new StreamStatsWindow(
                            originalStats.streamNames(),
                            originalStats.minTimestamp(),
                            originalStats.maxTimestamp(),
                            originalStats.getResolutionMillis());
                    qualities.put("performance-similarity", PERFORMANCE_SIMILARITY.apply(this.originalStats, emptyStats));
                    return qualities;
                }

                // Based on the user choice, calculate the correct privacy metric
                double finalPrivacyScore;
                switch (privacyMetricChoice) {
                    case K_ANONYMITY_CARDINALITY_MAX:
                        finalPrivacyScore = K_ANONYMITY_PRIVACY_CARDINALITY.applyWithMax(this.originalStream, modifiedEvents);
                        break;
                    case K_ANONYMITY_CARDINALITY_Q99:
                        finalPrivacyScore = K_ANONYMITY_PRIVACY_CARDINALITY.applyWithQuantile99(this.originalStream, modifiedEvents);
                        break;
                    case K_ANONYMITY_CARDINALITY:
                    default:
                        finalPrivacyScore = K_ANONYMITY_PRIVACY_CARDINALITY.apply(this.originalStream, modifiedEvents);
                        break;
                }

                MainQueryResult modifiedOutcome = isGeoLife
                        ? MainQueryGeoLife.process(modifiedEvents, queryId, this.minTs, this.maxTs)
                        : MainQueryAirQuality.process(modifiedEvents, queryId, this.minTs, this.maxTs);

                StreamStatsWindow modifiedStats = modifiedOutcome.statsWindow();
                qualities.put("performance-similarity", PERFORMANCE_SIMILARITY.apply(originalStats, modifiedStats));
                qualities.put("results-similarity", RESULTS_SIMILARITY.apply(originalResults, modifiedOutcome.events()));
                qualities.put("privacy", finalPrivacyScore);
                return qualities;

            } catch (Exception e) {
                logger.error("Error during fitness evaluation", e);
                qualities.put("results-similarity", 0.0);
                qualities.put("performance-similarity", 0.0);
                qualities.put("privacy", 0.0);
                return qualities;
            }
        };
    }
}

