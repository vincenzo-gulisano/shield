package builders;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jnb.core.Cacheable;
import io.github.ericmedvet.jnb.core.Discoverable;
import io.github.ericmedvet.jnb.core.Param;
import io.github.ericmedvet.jnb.datastructure.NamedFunction;
import java.util.function.Function;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import usecase.nhanes.NhanesGraphDescriptors;

@Discoverable(prefixTemplate = "nhanes.function|f")
public class NhanesFunctions {

    private NhanesFunctions() {
    }

    @Cacheable
    public static <X> NamedFunction<X, Integer> fieldCategoryMask(
            @Param(value = "name", dS = "nhanes.field.category.mask") String name,
            @Param(value = "of", dNPM = "f.identity()")
                    Function<X, Graph<OperatorRepresentation, ArcType>> beforeF) {
        Function<Graph<OperatorRepresentation, ArcType>, Integer> f =
                NhanesGraphDescriptors::fieldCategoryMask;
        return NamedFunction.from(f, name).compose(beforeF);
    }
}
