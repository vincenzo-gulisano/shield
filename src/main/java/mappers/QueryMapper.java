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
import java.util.HashSet;
import java.util.List;
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
      OperatorRepresentation sourceNode = new SimpleOperatorStep("Source");
      g.addNode(sourceNode);
      OperatorRepresentation finalNode = parsePipelineNode(tree, sourceNode, g);
      OperatorRepresentation sinkNode = new SimpleOperatorStep("Sink");
      g.addNode(sinkNode);
      g.setArcValue(finalNode, sinkNode, Arc.DEFAULT_ARC);
      logger.info("Resulting graph:\n{}\n",prettyPrint(g));
      return g;
    };
  }

  private OperatorRepresentation parsePipelineNode(Tree<String> pipelineNode, OperatorRepresentation previousNode,
      Graph<OperatorRepresentation, Arc> g) {

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

    OperatorRepresentation step = parseOperatorNode(operatorNode, previousNode, g);
    if (step == null) {
      throw new IllegalArgumentException("Failed to parse <operator> node in the grammar tree");
    }
    // g.addNode(step);
    // g.setArcValue(previousNode, step, Arc.DEFAULT_ARC);
    // previousNode = step;

    if (nextPipelineNode != null) {
      return parsePipelineNode(nextPipelineNode, step, g);
    }

    return step;

  }

  private OperatorRepresentation parseOperatorNode(Tree<String> operatorNode, OperatorRepresentation previousNode,
      Graph<OperatorRepresentation, Arc> g) {
    Tree<String> specificOpNode = operatorNode.child(0);

    return switch (specificOpNode.content()) {
      case "<filter>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(parseFilterNode(specificOpNode)), previousNode, g);
      case "<map_duplicate>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(parseMapDuplicateNode(specificOpNode)), previousNode, g);
      case "<map_noise>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(parseMapNoiseNode(specificOpNode)), previousNode, g);
      case "<map_rir>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(parseMapRIRNode(specificOpNode)), previousNode, g);
      case "<map_aggregate>" ->
        addSimpleOperatorStepToGraph(new SimpleOperatorStep(parseMapAggregateNode(specificOpNode)), previousNode, g);
      case "<fork_ops_join>" -> parseForkJoinNode(specificOpNode, previousNode, g);
      default ->
        throw new IllegalArgumentException("Unknown operator type found in grammar tree: " + specificOpNode.content());
    };
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
      OperatorRepresentation previousNode, Graph<OperatorRepresentation, Arc> g) {
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

    SimpleOperatorStep forkOp = new SimpleOperatorStep("FORK");
    g.addNode(forkOp);
    g.setArcValue(previousNode, forkOp, Arc.DEFAULT_ARC);

    OperatorRepresentation leftBranch = parsePipelineNode(leftPipelineNode,forkOp, g);
    OperatorRepresentation rightBranch = parsePipelineNode(rightPipelineNode,forkOp, g);

    SimpleOperatorStep joinOp = new SimpleOperatorStep("JOIN");
    g.addNode(joinOp);
    g.setArcValue(leftBranch, joinOp, Arc.DEFAULT_ARC);
    g.setArcValue(rightBranch, joinOp, Arc.DEFAULT_ARC);
    
    return joinOp;

    // return new ForkJoinStep(leftBranch, rightBranch);
  }

  public enum Arc {
    DEFAULT_ARC
  }

  public static String prettyPrint(Graph<OperatorRepresentation, Arc> graph) {
    List<OperatorRepresentation> roots = graph.nodes().stream()
        .filter(node -> graph.predecessors(node).isEmpty())
        .toList();
    if (roots.isEmpty()) {
      roots = new ArrayList<>(graph.nodes());
    }

    List<StringBuilder> lines = new ArrayList<>();
    for (int i = 0; i < roots.size(); i++) {
      if (i > 0) {
        lines.add(new StringBuilder());
      }
      StringBuilder line = new StringBuilder();
      lines.add(line);
      appendHorizontalPath(line, lines, graph, roots.get(i), new HashSet<>(), false);
    }

    StringBuilder sb = new StringBuilder("\n");
    for (StringBuilder line : lines) {
      sb.append(line).append('\n');
    }
    return sb.append('\n').toString();
  }

  private static void appendHorizontalPath(StringBuilder line, List<StringBuilder> lines,
      Graph<OperatorRepresentation, Arc> graph, OperatorRepresentation firstNode, Set<OperatorRepresentation> path,
      boolean stopAtJoin) {
    OperatorRepresentation node = firstNode;
    while (node != null) {
      line.append(node.getRepresentation());
      if (!path.add(node)) {
        line.append(" (cycle)");
        return;
      }
      if (stopAtJoin && graph.predecessors(node).size() > 1) {
        return;
      }

      List<OperatorRepresentation> successors = new ArrayList<>(graph.successors(node));
      if (successors.isEmpty()) {
        return;
      }

      if (successors.size() > 1) {
        int branchIndent = line.length() + 4;
        for (int i = 1; i < successors.size(); i++) {
          StringBuilder branchLine = new StringBuilder();
          appendSpaces(branchLine, branchIndent);
          branchLine.append("\\_ ");
          lines.add(branchLine);
          appendHorizontalPath(branchLine, lines, graph, successors.get(i), new HashSet<>(path), true);
        }
      }

      line.append(" -- ");
      node = successors.get(0);
    }
  }

  private static void appendSpaces(StringBuilder sb, int n) {
    for (int i = 0; i < n; i++) {
      sb.append(' ');
    }
  }

  public interface OperatorRepresentation {
    public String getRepresentation();
  }

  private static final class SimpleOperatorStep implements OperatorRepresentation {

    private final String representation;

    private SimpleOperatorStep(String representation) {
      this.representation = representation;
    }

    @Override
    public String getRepresentation() {
      return representation;
    }

    @Override
    public final String toString() {
        return representation;
    }
  }

  // record ForkJoinStep(
  //     List<OperatorRepresentation> leftBranch,
  //     List<OperatorRepresentation> rightBranch) implements OperatorRepresentation {

  //   @Override
  //   public String getRepresentation() {
  //     return "ForkJoin("
  //         + leftBranch.stream().map(OperatorRepresentation::getRepresentation).reduce((a, b) -> a + "," + b).orElse("")
  //         + " | "
  //         + rightBranch.stream().map(OperatorRepresentation::getRepresentation).reduce((a, b) -> a + "," + b).orElse("")
  //         + ")";
  //   }
  // }

}
