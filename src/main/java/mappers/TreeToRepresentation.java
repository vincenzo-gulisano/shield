package mappers;

import io.github.ericmedvet.jgea.core.representation.tree.Tree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static mappers.utils.TreeUtils.findFirstTerminal;

// Class responsible for parsing the grammar derivation tree (genotype) and translating it
// into a structured QueryRepresentation (phenotype)
public class TreeToRepresentation {

    private static final Logger logger = LoggerFactory.getLogger(TreeToRepresentation.class);

    // Parse a node <pipeline> recursively and add found operators to a list
    public void parsePipelineNode(Tree<String> pipelineNode, List<QueryRepresentation.OperatorNode> operators) {

        // Search for the two possible child node of a <pipeline> node: <operator> or another <pipeline>
        Tree<String> operatorNode = null;
        Tree<String> nextPipelineNode = null;

        for (Tree<String> child : pipelineNode) {
            if ("<operator>".equals(child.content())) {
                operatorNode = child;
            } else if ("<pipeline>".equals(child.content())) {
                nextPipelineNode = child;
            }
        }

        // The tree is invalid if there isn't a node <operator>
        if (operatorNode == null) {
            return;
        }

        Tree<String> specificOpNode = operatorNode.child(0);
        QueryRepresentation.OperatorNode operator = null;

        // Call the correct parsing method based on the operator type
        switch (specificOpNode.content()) {
            case "<filter>" -> operator = parseFilterNode(specificOpNode);
            case "<map_duplicate>" -> operator = parseMapDuplicateNode(specificOpNode);
            case "<map_noise>" -> operator = parseMapNoiseNode(specificOpNode);
            case "<map_aggregate>" -> operator = parseMapAggregateNode(specificOpNode);
            default -> logger.warn("Unknown operator type found in grammar tree: {}", specificOpNode.content());
        }

        if (operator != null) {
            operators.add(operator);
        }

        // Check if the pipeline is finished otherwise recursively calls the method and continue parsing the pipeline
        if (nextPipelineNode != null) {
            parsePipelineNode(nextPipelineNode, operators);
        }
    }


    // Parse a single filter node
    private QueryRepresentation.OperatorNode parseFilterNode(Tree<String> filterNode) {
        String attribute = null;
        String conditionString = null;
        Tree<String> valueNode = null;

        // Search for the children in the node
        for (Tree<String> child : filterNode) {
            switch (child.content()) {
                case "<attribute>" -> attribute = findFirstTerminal(child);
                case "<condition>" -> conditionString = findFirstTerminal(child);
                case "<value>" -> valueNode = child;
            }
        }

        if (attribute == null || conditionString == null || valueNode == null)
            return null;

        // Mapping from tree to Pipeline Representation
        try {
            // Collect all the leaves (digit and .) under the node <value> and join them to reconstruct the number
            List<String> leaves = valueNode.visitLeaves();
            String valueString = String.join("", leaves).replace("'", "");
            double value = Double.parseDouble(valueString);
            attribute = attribute.replace("'", "");
            // Create the specific arguments object for a filter
            QueryRepresentation.Condition condition = QueryRepresentation.Condition.fromString(conditionString);
            QueryRepresentation.FilterArgs args = new QueryRepresentation.FilterArgs(attribute, condition, value);

            return new QueryRepresentation.OperatorNode(QueryRepresentation.Operator.FILTER, args);
        } catch (Exception e) {
            logger.warn("Error parsing filter node", e);
            return null;
        }
    }

    // Parse a single duplicate map node
    private QueryRepresentation.OperatorNode parseMapDuplicateNode(Tree<String> mapNode) {
        try{
            // Search for the child <probability> in the node
            Tree<String> probNode = mapNode.child(0);
            String probString = findFirstTerminal(probNode);
            Double probValue = Double.parseDouble(probString);
            // Create the specific arguments object for a duplicate map
            QueryRepresentation.MapDuplicateArgs args = new QueryRepresentation.MapDuplicateArgs(probValue);

            return new QueryRepresentation.OperatorNode(QueryRepresentation.Operator.MAP_DUPLICATE, args);
        } catch (Exception e) {
            logger.warn("Error parsing duplicate map node", e);
            return null;
        }
    }

    // Parse a single noise map node
    private QueryRepresentation.OperatorNode parseMapNoiseNode(Tree<String> mapNode) {

        String attribute = null;
        String percentageString = null;

        // Search for the children in the node
        for (Tree<String> child : mapNode) {
            switch (child.content()) {
                case "<attribute>" -> attribute = findFirstTerminal(child);
                case "<percentage>" -> percentageString = findFirstTerminal(child);
            }
        }

        if (attribute == null || percentageString == null) return null;

        try{
            double percentage = Double.parseDouble(percentageString);
            attribute = attribute.replace("'", "");
            // Create the specific arguments object for a noise map
            QueryRepresentation.MapNoiseArgs args = new QueryRepresentation.MapNoiseArgs(attribute, percentage);
            return new QueryRepresentation.OperatorNode(QueryRepresentation.Operator.MAP_NOISE, args);
        } catch (Exception e) {
            logger.warn("Error parsing noise map node", e);
            return null;
        }
    }

    // Parse a single aggregate map node
    private QueryRepresentation.OperatorNode parseMapAggregateNode(Tree<String> mapNode) {

        String attribute = null;
        String functionString = null;
        String windowString = null;

        // Search for the children in the node
        for (Tree<String> child : mapNode) {
            switch (child.content()) {
                case "<attribute>" -> attribute = findFirstTerminal(child);
                case "<agg_fun>" -> functionString = findFirstTerminal(child);
                case "<window_size>" -> windowString = findFirstTerminal(child);
            }
        }

        if (attribute == null || functionString == null || windowString == null) {
            return null;
        }

        try {
            attribute = attribute.replace("'", "");

            QueryRepresentation.AggregationFunction function =
                    QueryRepresentation.AggregationFunction.fromString(functionString);

            int windowSize = Integer.parseInt(windowString);

            // Create the specific arguments object for a map aggregate
            QueryRepresentation.MapAggregateArgs args =
                    new QueryRepresentation.MapAggregateArgs(attribute, function, windowSize);

            return new QueryRepresentation.OperatorNode(
                    QueryRepresentation.Operator.MAP_AGGREGATE,
                    args
            );

        } catch (Exception e) {
            logger.warn("Error parsing aggregate map node", e);
            return null;
        }
    }

}
