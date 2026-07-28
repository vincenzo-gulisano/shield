package mappers;

import io.github.ericmedvet.jgea.core.InvertibleMapper;
import io.github.ericmedvet.jnb.datastructure.Tree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// JGEA mapper that orchestrates the mapping from genotype (a Tree<String> produced by the grammar) to a phenotype (a QueryRepresentation)
public class Mapper implements InvertibleMapper<Tree<String>, QueryRepresentation> {

    private static final Logger logger = LoggerFactory.getLogger(Mapper.class);

    public Mapper() {}

    @Override
    public Function<Tree<String>, QueryRepresentation> mapperFor(QueryRepresentation queryRepresentation) {
        return tree -> {
            try {
                List<QueryRepresentation.OperatorNode> operators = new ArrayList<>();
                // Start recursive parsing from the root of the tree
                TreeToRepresentation treeParser = new TreeToRepresentation();
                treeParser.parsePipelineNode(tree, operators);
                return new QueryRepresentation(operators);

            } catch (Exception e) {
                logger.error("Error during mapping process", e);
                return null;
            }
        };
    }

    @Override
    public Tree<String> exampleFor(QueryRepresentation queryRepresentation) {
        return new Tree<>("<pipeline>");
    }
}