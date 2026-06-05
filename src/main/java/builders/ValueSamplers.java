package builders;

import io.github.ericmedvet.jnb.core.Cacheable;
import io.github.ericmedvet.jnb.core.Discoverable;
import io.github.ericmedvet.jnb.core.Param;
import java.util.List;
import usecase.common.Tuple;
import usecase.common.TupleFieldValueSampler;
import usecase.lcl.LclTupleLoader;
import usecase.lcl.flow.LclFlowTupleReader;
import usecase.nhanes.NhanesTupleLoader;

@Discoverable(prefixTemplate = "anonym.valueSampler")
public class ValueSamplers {

    private ValueSamplers() {
    }

    @Cacheable
    public static TupleFieldValueSampler nhanesTupleFieldValueSampler(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("seed") int seed) {
        List<Tuple> tuples = NhanesTupleLoader.load(inputCsvPath);
        return new TupleFieldValueSampler(tuples, seed);
    }

    @Cacheable
    public static TupleFieldValueSampler lclTupleFieldValueSampler(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("seed") int seed) {
        List<Tuple> tuples = LclTupleLoader.load(inputCsvPath);
        return new TupleFieldValueSampler(tuples, seed);
    }

    @Cacheable
    public static TupleFieldValueSampler lclFlowTupleFieldValueSampler(
            @Param("inputCsvPath") String inputCsvPath,
            @Param("seed") int seed) {
        List<Tuple> tuples = LclFlowTupleReader.loadUnchecked(inputCsvPath);
        return new TupleFieldValueSampler(tuples, seed);
    }
}
