package usecase.lcl;

import grammar.generator.FieldType;
import java.util.List;
import java.util.Map;
import problem.utils.PrivacyMetricChoice;
import usecase.common.analysis.TupleStreamAnonymizationProblem;

public class LclStreamAnonymizationProblem extends TupleStreamAnonymizationProblem {

    public static final List<String> LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES =
            List.of("f1", "f2", "f3");
    public static final Map<String, FieldType> LINKAGE_ATTACK_QUASI_IDENTIFIER_TYPES = Map.of(
            "f1", FieldType.CONTINUOUS_NUMERIC,
            "f2", FieldType.CONTINUOUS_NUMERIC,
            "f3", FieldType.CONTINUOUS_NUMERIC);

    public LclStreamAnonymizationProblem(
            String inputCsvPath,
            PrivacyMetricChoice privacyMetric,
            double fidelityF1Threshold,
            double semanticsF1Threshold,
            int k) {

        super(
                "LCL",
                inputCsvPath,
                LclTupleLoader.load(inputCsvPath),
                MainQuery::process,
                privacyMetric,
                fidelityF1Threshold,
                semanticsF1Threshold,
                k,
                LINKAGE_ATTACK_QUASI_IDENTIFIER_ATTRIBUTES,
                LINKAGE_ATTACK_QUASI_IDENTIFIER_TYPES);
    }
}
