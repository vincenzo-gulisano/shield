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

import static mappers.utils.TreeUtils.findFirstTerminal;

import io.github.ericmedvet.jgea.core.InvertibleMapper;
import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jgea.core.representation.graph.LinkedHashGraph;
import io.github.ericmedvet.jgea.core.representation.tree.Tree;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import mappers.QueryMapper.Arc;
import mappers.QueryMapper.OperatorRepresentation;
import mappers.QueryRepresentation.OperatorNode;

public class QueryMapper implements InvertibleMapper<Tree<String>, Graph<OperatorRepresentation, Arc>> {

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
      parsePipelineNode(tree, sourceNode, g);
      // g.setArcValue(null, null, Arc.DEFAULT_ARC);
      return g;
    };
  }

  private void parsePipelineNode(Tree<String> pipelineNode, OperatorRepresentation previousNode,
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

    OperatorRepresentation step = parseOperatorNode(operatorNode);
    if (step == null) {
      throw new IllegalArgumentException("Failed to parse <operator> node in the grammar tree");
    }
    g.addNode(step);
    g.setArcValue(previousNode, step, Arc.DEFAULT_ARC);

    if (nextPipelineNode != null) {
      parsePipelineNode(nextPipelineNode, step, g);
    }

    // return steps;
  }

  private List<OperatorRepresentation> parsePipelineNode(Tree<String> pipelineNode) {
    List<OperatorRepresentation> steps = new ArrayList<>();
    Tree<String> currentNode = pipelineNode;
    while (currentNode != null) {
      if (currentNode.nChildren() > 2) {
        throw new IllegalArgumentException(
            "A <pipeline> node can have at most 2 children: an <operator> and an optional <pipeline>");
      }
      Tree<String> operatorNode = currentNode.child(0);
      if (!operatorNode.content().equals("<operator>")) {
        throw new IllegalArgumentException("The first child of a <pipeline> node must be an <operator> node");
      }
      steps.add(parseOperatorNode(operatorNode));

      currentNode = currentNode.nChildren() > 1 ? currentNode.child(1) : null;
      if (currentNode != null && !currentNode.content().equals("<pipeline>")) {
        throw new IllegalArgumentException("The second child of a <pipeline> node must be a <pipeline> node");
      }
    }
    return steps;
  }

  private OperatorRepresentation parseOperatorNode(Tree<String> operatorNode) {
    Tree<String> specificOpNode = operatorNode.child(0);

    return switch (specificOpNode.content()) {
      case "<filter>" -> new SimpleOperatorStep(parseFilterNode(specificOpNode));
      case "<map_duplicate>" -> new SimpleOperatorStep(parseMapDuplicateNode(specificOpNode));
      case "<map_noise>" -> new SimpleOperatorStep(parseMapNoiseNode(specificOpNode));
      case "<map_rir>" -> new SimpleOperatorStep(parseMapRIRNode(specificOpNode));
      case "<map_aggregate>" -> new SimpleOperatorStep(parseMapAggregateNode(specificOpNode));
      case "<fork_ops_join>" -> parseForkJoinNode(specificOpNode);
      default ->
        throw new IllegalArgumentException("Unknown operator type found in grammar tree: " + specificOpNode.content());
    };
  }

  private String parseFilterNode(Tree<String> specificOpNode) {
    return "Filter()";
  }

  private String parseMapDuplicateNode(Tree<String> specificOpNode) {
    return "MapDuplicate()";
  }

  private String parseMapNoiseNode(Tree<String> specificOpNode) {
    String attribute = null;
    String percentageString = null;
    for (Tree<String> child : specificOpNode) {
      switch (child.content()) {
        case "<attribute>" -> attribute = findFirstTerminal(child);
        case "<percentage>" -> percentageString = findFirstTerminal(child);
        default -> {
        }
      }
    }
    if (attribute == null || percentageString == null) {
      throw new IllegalArgumentException("A <map_noise> node must contain <attribute> and <percentage> children");
    }
    double percentage = Double.parseDouble(percentageString);
    return String.format("MapNoise(attribute=%s, percentage=%.2f)", attribute.replace("'", ""), percentage);
  }

  private String parseMapRIRNode(Tree<String> specificOpNode) {
    return "MapRIR()";
  }

  private String parseMapAggregateNode(Tree<String> specificOpNode) {
    return "MapAggregate()";
  }

  private OperatorRepresentation parseForkJoinNode(Tree<String> specificOpNode) {
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

    List<OperatorRepresentation> leftBranch = parsePipelineNode(leftPipelineNode);
    List<OperatorRepresentation> rightBranch = parsePipelineNode(rightPipelineNode);

    return new ForkJoinStep(leftBranch, rightBranch);
  }

  public enum Arc {
    DEFAULT_ARC
  }

  public interface OperatorRepresentation {
    public String getRepresentation();
  }

  record SimpleOperatorStep(String representation)
      implements OperatorRepresentation {

    @Override
    public String getRepresentation() {
      return representation;
    }
  }

  record ForkJoinStep(
      List<OperatorRepresentation> leftBranch,
      List<OperatorRepresentation> rightBranch) implements OperatorRepresentation {

    @Override
    public String getRepresentation() {
      return "ForkJoin("
          + leftBranch.stream().map(OperatorRepresentation::getRepresentation).reduce((a, b) -> a + "," + b).orElse("")
          + " | "
          + rightBranch.stream().map(OperatorRepresentation::getRepresentation).reduce((a, b) -> a + "," + b).orElse("")
          + ")";
    }
  }

}
