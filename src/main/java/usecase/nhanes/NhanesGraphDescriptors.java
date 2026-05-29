package usecase.nhanes;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import mappers.QueryMapper;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;

public final class NhanesGraphDescriptors {

    public static final int DEMOGRAPHICS = 1;
    public static final int SOCIOECONOMIC = 1 << 1;
    public static final int ANTHROPOMETRIC = 1 << 2;

    private NhanesGraphDescriptors() {
    }

    /**
     * Return a three-bit mask describing which NHANES field categories are used by a graph.
     *
     * <p>Bit 0: demographics ({@code f1}, {@code f2}, {@code f3}); bit 1: socioeconomic
     * ({@code f4}); bit 2: anthropometric/body ({@code f5}, {@code f6}).
     */
    public static int fieldCategoryMask(Graph<OperatorRepresentation, ArcType> graph) {
        int mask = 0;
        for (OperatorRepresentation node : graph.nodes()) {
            String field = QueryMapper.fieldUsedBy(node);
            if (field != null) {
                mask |= categoryFor(field);
            }
        }
        return mask;
    }

    private static int categoryFor(String field) {
        return switch (field) {
            case "f1", "f2", "f3" -> DEMOGRAPHICS;
            case "f4" -> SOCIOECONOMIC;
            case "f5", "f6" -> ANTHROPOMETRIC;
            default -> throw new IllegalArgumentException("Unknown NHANES field category for field: " + field);
        };
    }
}
