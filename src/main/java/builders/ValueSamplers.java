package builders;

import io.github.ericmedvet.jnb.core.Cacheable;
import io.github.ericmedvet.jnb.core.Discoverable;
import io.github.ericmedvet.jnb.core.Param;
import usecase.common.TupleFieldValueSampler;
import usecase.nhanes.NhanesTupleLoader;

@Discoverable(prefixTemplate = "anonym.valueSampler")
public class ValueSamplers {

    private ValueSamplers() {
    }

    @Cacheable
    public static TupleFieldValueSampler nhanesTupleFieldValueSampler(
            @Param(value = "inputCsvPath", dS = "datasets/nhanes.csv") String inputCsvPath,
            @Param(value = "seed", dI = 0) int seed) {
        return new TupleFieldValueSampler(NhanesTupleLoader.load(inputCsvPath), seed);
    }
}
