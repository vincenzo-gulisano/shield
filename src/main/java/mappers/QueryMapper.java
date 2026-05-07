/*
 * Copyright 2026 eric
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mappers;

import io.github.ericmedvet.jgea.core.InvertibleMapper;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.graph.LinkedHashGraph;
import io.github.ericmedvet.jgea.core.representation.tree.Tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mappers.QueryMapper.Arc;
import mappers.QueryMapper.OperatorRepresentation;

public class QueryMapper implements InvertibleMapper<Tree<String>, Graph<OperatorRepresentation, Arc>> {

  private static final Logger logger = LoggerFactory.getLogger(QueryMapper.class);

  @Override
  public Tree<String> exampleFor(
      Graph<OperatorRepresentation, Arc> exampleGraph) {
    return Tree.of("<pipeline>");
  }

  @Override
  public Function<Tree<String>, Graph<OperatorRepresentation, Arc>> mapperFor(
      Graph<OperatorRepresentation, Arc> exampleGraph) {
    return tree -> {

      // TODO build the graph by adding nodes and parsing the tree
      Graph<OperatorRepresentation, Arc> g = new LinkedHashGraph<>();
      Map<String, Integer> operatorCounters = new HashMap<>();
      OperatorRepresentation sourceNode = new SimpleOperatorStep("Source");
      g.addNode(sourceNode);
      OperatorRepresentation finalNode = parsePipelineNode(tree, sourceNode, g, operatorCounters);
      OperatorRepresentation sinkNode = new SimpleOperatorStep("Sink");
      g.addNode(sinkNode);
      g.setArcValue(finalNode, sinkNode, Arc.DEFAULT_ARC);
      // logger.info("Input tree:\n{}\n", prettyPrintTree(tree));
      // logger.info("Resulting graph:\n{}\n",prettyPrintGraph(g));
      return g;
    };
  }

  private OperatorRepresentation parsePipelineNode(Tree<String> pipelineNode, OperatorRepresentation previousNode,
      Graph<OperatorRepresentation, Arc> g, Map<String, Integer> operatorCounters) {

    if (previousNode == null) {
      throw new IllegalArgumentException("Previous node cannot be null when parsing a <pipeline> node");
    }

    if (pipelineNode.nChildren() > 2) {
      throw new IllegalArgumentException(
          "A <pipeline> node can have at most 2 children: an <operator> and an optional <pipeline>");
    }

    // List<OperatorRepresentation> steps = new ArrayList<>();

    // Search for the two possible child node of a <pipeline> node: <operator> or
    // another <pipeline>
    Tree<String> operatorNode = pipelineNode.child(0);
    if (!operatorNode.content().equals("<operator>")) {
      throw new IllegalArgumentException("The first child of a <pipeline> node must be an <operator> node");
    }
    Tree<String> nextPipelineNode = pipelineNode.nChildren() > 1 ? pipelineNode.child(1) : null;
    if (nextPipelineNode != null && !nextPipelineNode.content().equals("<pipeline>")) {
      throw new IllegalArgumentException("The second child of a <pipeline> node must be a <pipeline> node");
    }

    OperatorRepresentation step = parseOperatorNode(operatorNode, previousNode, g, operatorCounters);
    if (step == null) {
      throw new IllegalArgumentException("Failed to parse <operator> node in the grammar tree");
    }
    // g.addNode(step);
    // g.setArcValue(previousNode, step, Arc.DEFAULT_ARC);
    // previousNode = step;

    if (nextPipelineNode != null) {
      return parsePipelineNode(nextPipelineNode, step, g, operatorCounters);
    }

    return step;

  }

  private OperatorRepresentation parseOperatorNode(Tree<String> operatorNode, OperatorRepresentation previousNode,
      Graph<OperatorRepresentation, Arc> g, Map<String, Integer> operatorCounters) {
    Tree<String> specificOpNode = operatorNode.child(0);

    return switch (specificOpNode.content()) {
      case "<filter>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(numberedRepresentation(parseFilterNode(specificOpNode), operatorCounters)), previousNode, g);
      case "<map_duplicate>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(numberedRepresentation(parseMapDuplicateNode(specificOpNode), operatorCounters)), previousNode, g);
      case "<map_noise>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(numberedRepresentation(parseMapNoiseNode(specificOpNode), operatorCounters)), previousNode, g);
      case "<map_rir>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(numberedRepresentation(parseMapRIRNode(specificOpNode), operatorCounters)), previousNode, g);
      case "<map_aggregate>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(numberedRepresentation(parseMapAggregateNode(specificOpNode), operatorCounters)), previousNode, g);
      case "<fork_ops_join>" -> parseForkJoinNode(specificOpNode, previousNode, g, operatorCounters);
      default ->
        throw new IllegalArgumentException("Unknown operator type found in grammar tree: " + specificOpNode.content());
    };
  }

  private static String numberedRepresentation(String representation, Map<String, Integer> operatorCounters) {
    int parenthesisIndex = representation.indexOf('(');
    String operatorName = parenthesisIndex >= 0 ? representation.substring(0, parenthesisIndex) : representation;
    int n = operatorCounters.merge(operatorName, 1, Integer::sum);
    return operatorName + "(" + n + ")";
  }

  private OperatorRepresentation addSimpleOperatorStepToGraph(SimpleOperatorStep step,
      OperatorRepresentation previousNode, Graph<OperatorRepresentation, Arc> g) {
    g.addNode(step);
    if (previousNode == null) {
      throw new IllegalArgumentException(
          "Previous node cannot be null when adding a simple operator step to the graph");
    }
    g.setArcValue(previousNode, step, Arc.DEFAULT_ARC);
    return step;
  }

  private String parseFilterNode(Tree<String> specificOpNode) {
    return "Filter()";
  }

  private String parseMapDuplicateNode(Tree<String> specificOpNode) {
    return "MapDuplicate()";
  }

  private String parseMapNoiseNode(Tree<String> specificOpNode) {
    return "MapNoise()";
  }

  private String parseMapRIRNode(Tree<String> specificOpNode) {
    return "MapRIR()";
  }

  private String parseMapAggregateNode(Tree<String> specificOpNode) {
    return "MapAggregate()";
  }

  private OperatorRepresentation parseForkJoinNode(Tree<String> specificOpNode,
      OperatorRepresentation previousNode, Graph<OperatorRepresentation, Arc> g, Map<String, Integer> operatorCounters) {
    if (specificOpNode.nChildren() != 2) {
      throw new IllegalArgumentException(
          "A <fork_ops_join> node must have exactly 2 children, representing the two branches of the fork");
    }
    if (!specificOpNode.child(0).content().equals("<pipeline>")
        || !specificOpNode.child(1).content().equals("<pipeline>")) {
      throw new IllegalArgumentException("The children of a <fork_ops_join> node must be <pipeline> nodes");
    }

    Tree<String> leftPipelineNode = specificOpNode.child(0);
    Tree<String> rightPipelineNode = specificOpNode.child(1);

    SimpleOperatorStep forkOp = new SimpleOperatorStep(numberedRepresentation("FORK", operatorCounters));
    g.addNode(forkOp);
    g.setArcValue(previousNode, forkOp, Arc.DEFAULT_ARC);

    OperatorRepresentation leftBranch = parsePipelineNode(leftPipelineNode,forkOp, g, operatorCounters);
    OperatorRepresentation rightBranch = parsePipelineNode(rightPipelineNode,forkOp, g, operatorCounters);

    SimpleOperatorStep joinOp = new SimpleOperatorStep(numberedRepresentation("JOIN", operatorCounters));
    g.addNode(joinOp);
    g.setArcValue(leftBranch, joinOp, Arc.DEFAULT_ARC);
    g.setArcValue(rightBranch, joinOp, Arc.DEFAULT_ARC);
    
    return joinOp;

  }

  public enum Arc {
    DEFAULT_ARC
  }

  public static String prettyPrintTree(Tree<String> tree) {
    StringBuilder sb = new StringBuilder("\n");
    appendTreeOperators(sb, tree, 0);
    return sb.append('\n').toString();
  }

  private static void appendTreeOperators(StringBuilder sb, Tree<String> node, int level) {
    String content = node.content();
    if (content.equals("<pipeline>") || content.equals("<operator>")) {
      for (Tree<String> child : node) {
        appendTreeOperators(sb, child, level);
      }
      return;
    }

    if (isOperatorNode(content)) {
      sb.append("\t".repeat(level)).append(operatorName(content)).append('\n');
      if (content.equals("<fork_ops_join>")) {
        for (int i = 0; i < node.nChildren(); i++) {
          if (i > 0) {
            sb.append('\n');
          }
          appendTreeOperators(sb, node.child(i), level + 1);
        }
      }
    }
  }

  private static boolean isOperatorNode(String content) {
    return switch (content) {
      case "<filter>", "<map_duplicate>", "<map_noise>", "<map_rir>", "<map_aggregate>", "<fork_ops_join>" -> true;
      default -> false;
    };
  }

  private static String operatorName(String content) {
    return content.substring(1, content.length() - 1);
  }

  public static String prettyPrintGraph(Graph<OperatorRepresentation, Arc> graph) {

    StringBuilder sb = new StringBuilder("\n");
    for ( Graph.Arc<OperatorRepresentation> arc : graph.arcs()) {
      sb.append(arc).append('\n');
    }
    return sb.toString();
  }

  public interface OperatorRepresentation {
    public String getRepresentation();
  }

  record SimpleOperatorStep(String representation) implements OperatorRepresentation {

    @Override
    public String getRepresentation() {
      return representation;
    }

    @Override
    public final String toString() {
        return representation;
    }
  }

}
