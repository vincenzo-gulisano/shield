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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import component.operator.in1.filter.FilterFunction;
import component.operator.in1.filter.FilterOperator;
import component.operator.in1.map.FlatMapFunction;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import query.utils.MapAggregateFunction;
import query.utils.MapDuplicateFunction;
import query.utils.MapNoiseFunction;
import query.utils.RIRMap;
import usecase.common.Tuple;

public class QueryMapper implements InvertibleMapper<Tree<String>, Graph<OperatorRepresentation, ArcType>> {

  private static final Logger logger = LoggerFactory.getLogger(QueryMapper.class);

  @Override
  public Tree<String> exampleFor(
      Graph<OperatorRepresentation, ArcType> exampleGraph) {
    return Tree.of("<pipeline>");
  }

  @Override
  public Function<Tree<String>, Graph<OperatorRepresentation, ArcType>> mapperFor(
      Graph<OperatorRepresentation, ArcType> exampleGraph) {
    return tree -> {

      Graph<OperatorRepresentation, ArcType> g = new LinkedHashGraph<>();
      Map<String, Integer> operatorCounters = new HashMap<>();
      OperatorRepresentation sourceNode = new Source("source");
      g.addNode(sourceNode);
      OperatorRepresentation finalNode = parsePipelineNode(tree, sourceNode, g, operatorCounters);
      OperatorRepresentation sinkNode = new Sink("sink");
      g.addNode(sinkNode);
      g.setArcValue(finalNode, sinkNode, ArcType.DEFAULT_ARC);
      logger.info("Input tree:\n{}\n", prettyPrintTree(tree));
      logger.info("Resulting graph:\n{}\n", prettyPrintGraph(g));
      return g;
    };
  }

  private OperatorRepresentation parsePipelineNode(Tree<String> pipelineNode, OperatorRepresentation previousNode,
      Graph<OperatorRepresentation, ArcType> g, Map<String, Integer> operatorCounters) {

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
      Graph<OperatorRepresentation, ArcType> g, Map<String, Integer> operatorCounters) {
    Tree<String> specificOpNode = operatorNode.child(0);

    switch (specificOpNode.content()) {
      case "<filter>" -> {
        String id = "F" + operatorCounters.merge("Filter", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseFilterNode(specificOpNode, id),
            previousNode, g);
      }
      case "<map_duplicate>" -> {
        String id = "MD" + operatorCounters.merge("MapDuplicate", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapDuplicateNode(specificOpNode, id),
            previousNode, g);
      }
      case "<map_noise>" -> {
        String id = "MN" + operatorCounters.merge("MapNoise", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapNoiseNode(specificOpNode, id),
            previousNode, g);
      }
      case "<map_rir>" -> {
        String id = "MR" + operatorCounters.merge("MapRIR", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapRIRNode(specificOpNode, id),
            previousNode, g);
      }
      case "<map_aggregate>" -> {
        String id = "MA" + operatorCounters.merge("MapAggregate", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapAggregateNode(specificOpNode, id),
            previousNode, g);
      }
      case "<fork_ops_join>" -> {
        return parseForkJoinNode(specificOpNode, previousNode, g, operatorCounters);
      }
      default ->
        throw new IllegalArgumentException("Unknown operator type found in grammar tree: " + specificOpNode.content());
    }
  }

  // private static String numberedRepresentation(String representation,
  // Map<String, Integer> operatorCounters) {
  // int parenthesisIndex = representation.indexOf('(');
  // String operatorName = parenthesisIndex >= 0 ? representation.substring(0,
  // parenthesisIndex) : representation;
  // int n = operatorCounters.merge(operatorName, 1, Integer::sum);
  // return operatorName + "(" + n + ")";
  // }

  private OperatorRepresentation addSimpleOperatorStepToGraph(OperatorRepresentation step,
      OperatorRepresentation previousNode, Graph<OperatorRepresentation, ArcType> g) {
    g.addNode(step);
    if (previousNode == null) {
      throw new IllegalArgumentException(
          "Previous node cannot be null when adding a simple operator step to the graph");
    }
    g.setArcValue(previousNode, step, ArcType.DEFAULT_ARC);
    return step;
  }

  private OperatorRepresentation parseFilterNode(Tree<String> specificOpNode, String id) {
    StringBuilder valueStringBuilder = new StringBuilder();
    specificOpNode.child(2).childStream().forEach(c -> {
      if (c.nChildren() == 0) {
        valueStringBuilder.append(c.content());
      } else {
        valueStringBuilder.append(c.child(0).content());
      }
    }); // value attribute
    return new FilterOperator(id, specificOpNode.child(0).child(0).content(),
        specificOpNode.child(1).child(0).content(), Double.parseDouble(valueStringBuilder.toString()));
  }

  private OperatorRepresentation parseMapDuplicateNode(Tree<String> specificOpNode, String id) {
    return new MapDuplicate(id, Double.parseDouble(specificOpNode.child(0).child(0).content()));
  }

  private OperatorRepresentation parseMapNoiseNode(Tree<String> specificOpNode, String id) {
    return new MapNoise(id, specificOpNode.child(0).child(0).content(),
        Double.parseDouble(specificOpNode.child(1).child(0).content()));

  }

  private OperatorRepresentation parseMapRIRNode(Tree<String> specificOpNode, String id) {
    return new MapRIR(id, specificOpNode.child(0).child(0).content());
  }

  private OperatorRepresentation parseMapAggregateNode(Tree<String> specificOpNode, String id) {
    return new MapAggregate(id, specificOpNode.child(0).child(0).content(),
        MapAggregateAggregatorFunction.valueOf(specificOpNode.child(1).child(0).content().toUpperCase()),
        Integer.valueOf(specificOpNode.child(2).child(0).content()));
  }

  private OperatorRepresentation parseForkJoinNode(Tree<String> specificOpNode,
      OperatorRepresentation previousNode, Graph<OperatorRepresentation, ArcType> g,
      Map<String, Integer> operatorCounters) {
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

    String forkId = "FK" + operatorCounters.merge("Fork", 1, Integer::sum);

    OperatorRepresentation forkOp = new Fork(forkId);
    g.addNode(forkOp);
    g.setArcValue(previousNode, forkOp, ArcType.DEFAULT_ARC);

    OperatorRepresentation leftBranch = parsePipelineNode(leftPipelineNode, forkOp, g, operatorCounters);
    OperatorRepresentation rightBranch = parsePipelineNode(rightPipelineNode, forkOp, g, operatorCounters);

    String unionId = "U" + operatorCounters.merge("Union", 1, Integer::sum);

    OperatorRepresentation joinOp = new Union(unionId);
    g.addNode(joinOp);
    g.setArcValue(leftBranch, joinOp, ArcType.DEFAULT_ARC);
    g.setArcValue(rightBranch, joinOp, ArcType.DEFAULT_ARC);

    return joinOp;

  }

  public enum ArcType {
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

  public static String prettyPrintGraph(Graph<OperatorRepresentation, ArcType> graph) {

    StringBuilder sb = new StringBuilder("\n");
    for (Graph.Arc<OperatorRepresentation> arc : graph.arcs()) {
      sb.append(arc).append('\n');
    }
    return sb.toString();
  }

  // public enum OperatorType {
  // FILTER,
  // MAP_DUPLICATE,
  // MAP_NOISE,
  // MAP_RIR,
  // MAP_AGGREGATE,
  // FORK,
  // UNION,
  // SOURCE,
  // SINK
  // }

  public enum MapAggregateAggregatorFunction {
    MIN,
    AVG,
    MAX
  }

  public sealed interface OperatorRepresentation
      permits FilterOperator, MapDuplicate, MapNoise, MapRIR, MapAggregate, Fork, Union, Source, Sink {
    public String getID();
  }

  public record FilterOperator(String id, String field, String condition, double value)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public FilterFunction<Tuple> createFilterFunction() {
      switch (condition) {
        case "lt":
          return t -> t.lookup(field) < value;
        case "gt":
          return t -> t.lookup(field) > value;
        default:
          throw new IllegalArgumentException("Unkown condition " + condition + " for filter " + getID());
      }
    }
  }

  public record MapDuplicate(String id, double probability) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public FlatMapFunction<Tuple, Tuple> createMapFunction() {
      return new MapDuplicateFunction(probability);
    }
  }

  public record MapNoise(String id, String field, double probability) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public MapNoiseFunction createMapFunction() {
      return new MapNoiseFunction(field, probability);
    }
  }

  public record MapRIR(String id, String field) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public RIRMap createRIRMap() {
      return new RIRMap(field);
    }
  }

  public record MapAggregate(String id, String field, MapAggregateAggregatorFunction aggFunction, int windowSize)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public MapAggregateFunction createMapFunction() {
      return new MapAggregateFunction(field, aggFunction, windowSize);
    }
  }

  public record Fork(String id) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }
  }

  public record Union(String id) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }
  }

  public record Source(String id) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }
  }

  public record Sink(String id) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }
  }

  public static FilterOperator parseFilterOperatorFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "FilterOperator");
    return new FilterOperator(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        requiredParam(params, "condition", representation),
        Double.parseDouble(requiredParam(params, "value", representation)));
  }

  public static MapDuplicate parseMapDuplicateFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapDuplicate");
    return new MapDuplicate(
        requiredParam(params, "id", representation),
        Double.parseDouble(requiredParam(params, "probability", representation)));
  }

  public static MapNoise parseMapNoiseFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapNoise");
    return new MapNoise(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        Double.parseDouble(requiredParam(params, "probability", representation)));
  }

  public static MapRIR parseMapRIRFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapRIR");
    return new MapRIR(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation));
  }

  public static MapAggregate parseMapAggregateFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapAggregate");
    return new MapAggregate(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        MapAggregateAggregatorFunction.valueOf(requiredParam(params, "aggFunction", representation)),
        Integer.parseInt(requiredParam(params, "windowSize", representation)));
  }

  public static Fork parseForkFromString(String representation) {
    return new Fork(requiredParam(parseOperatorParams(representation, "Fork"), "id", representation));
  }

  public static Union parseUnionFromString(String representation) {
    return new Union(requiredParam(parseOperatorParams(representation, "Union"), "id", representation));
  }

  public static Source parseSourceFromString(String representation) {
    return new Source(requiredParam(parseOperatorParams(representation, "Source"), "id", representation));
  }

  public static Sink parseSinkFromString(String representation) {
    return new Sink(requiredParam(parseOperatorParams(representation, "Sink"), "id", representation));
  }

  private static Map<String, String> parseOperatorParams(String representation, String expectedOperatorName) {
    String prefix = expectedOperatorName + "[";
    if (!representation.startsWith(prefix) || !representation.endsWith("]")) {
      throw new IllegalArgumentException(
          "Expected " + expectedOperatorName + "[...] representation, found: " + representation);
    }

    Map<String, String> params = new HashMap<>();
    String paramsString = representation.substring(prefix.length(), representation.length() - 1);
    if (paramsString.isBlank()) {
      return params;
    }

    for (String param : paramsString.split(",")) {
      String[] keyValue = param.trim().split("=", 2);
      if (keyValue.length != 2) {
        throw new IllegalArgumentException("Malformed parameter '" + param + "' in: " + representation);
      }
      params.put(keyValue[0].trim(), keyValue[1].trim());
    }
    return params;
  }

  private static String requiredParam(Map<String, String> params, String name, String representation) {
    String value = params.get(name);
    if (value == null) {
      throw new IllegalArgumentException("Missing parameter '" + name + "' in: " + representation);
    }
    return value;
  }

}
