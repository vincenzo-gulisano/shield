package builders;

import io.github.ericmedvet.jnb.core.Cacheable;
import io.github.ericmedvet.jnb.core.Discoverable;
import io.github.ericmedvet.jnb.core.Param;
import java.util.List;
import usecase.common.Tuple;
import usecase.common.TupleFieldValueSampler;
import usecase.lcl.flow.LclFlowTupleReader;

@Discoverable(prefixTemplate = "anonym.valueSampler")
public class ValueSamplers {

    private ValueSamplers() {
    }

    @Cacheable
    public static TupleFieldValueSampler lclFlowTupleFieldValueSampler(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("seed") int seed) {
        List<Tuple> tuples = LclFlowTupleReader.loadUnchecked(inputCsvPath);
        return new TupleFieldValueSampler(tuples, seed);
    }
}
