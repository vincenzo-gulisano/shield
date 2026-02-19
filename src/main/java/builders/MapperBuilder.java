package builders;

import io.github.ericmedvet.jnb.core.Cacheable;
import io.github.ericmedvet.jnb.core.Discoverable;
import mappers.Mapper;

@Discoverable(prefixTemplate = "anonym.mapper")
public class MapperBuilder {

    private MapperBuilder() {
    }

    // Create a Mapper
    @SuppressWarnings("unused")
    @Cacheable
    public static Mapper treeToQueryMapper(){
        return new Mapper();
    }
}