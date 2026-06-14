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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import component.operator.in1.filter.FilterFunction;
import component.operator.in1.map.FlatMapFunction;
import component.operator.in1.map.MapFunction;
import mappers.QueryMapper.ArcType;
import mappers.QueryMapper.OperatorRepresentation;
import query.utils.CategoricalNoiseFunction;
import query.utils.CategoricalRIRMap;
import query.utils.ConditionPairwiseFieldSwapFunction;
import query.utils.ConditionPartitionFieldShuffleFunction;
import query.utils.ConditionPreservingNoiseFunction;
import query.utils.ConditionPreservingRIRMap;
import query.utils.ConditionalTupleRouterOperator;
import query.utils.DiscreteNumericNoiseFunction;
import query.utils.DiscreteNumericRIRMap;
import query.utils.MapAggregateFunction;
import query.utils.MapDuplicateFunction;
import query.utils.MapNoiseFunction;
import query.utils.RIRMap;
import query.utils.TimestampGroupFieldShuffleFunction;
import query.utils.TimestampPairwiseFieldSwapFunction;
import query.utils.TupleConditionSpec;
import query.utils.TupleFieldType;
import usecase.common.FieldValueSampler;
import usecase.common.Tuple;

import static query.utils.OperatorUtils.requireFinite;

public class QueryMapper implements InvertibleMapper<Tree<String>, Graph<OperatorRepresentation, ArcType>> {

  private static final Logger logger = LoggerFactory.getLogger(QueryMapper.class);
  private static final String URIR = "urir";
  private static final String DRIR = "drir";
  private static final String UCR = "ucr";
  private static final String DCR = "dcr";

  private final FieldValueSampler fieldValueSampler;

  public QueryMapper() {
    this.fieldValueSampler = null;
  }

  public QueryMapper(FieldValueSampler fieldValueSampler) {
    this.fieldValueSampler = Objects.requireNonNull(fieldValueSampler);
  }

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
      if (logger.isDebugEnabled()) {
        logger.debug("Input tree:\n{}\n", prettyPrintTree(tree));
        logger.debug("Resulting graph:\n{}\n", prettyPrintGraph(g));
      }
      return g;
    };
  }

  private OperatorRepresentation parsePipelineNode(Tree<String> pipelineNode, OperatorRepresentation previousNode,
      Graph<OperatorRepresentation, ArcType> g, Map<String, Integer> operatorCounters) {
    if (previousNode == null) {
      throw new IllegalArgumentException("Previous node cannot be null when parsing a <pipeline> node");
    }
    if (pipelineNode.content().equals("<empty_pipeline>")) {
      return previousNode;
    }

    if (pipelineNode.nChildren() == 1 && isPipelineNode(pipelineNode.child(0).content())) {
      return parsePipelineNode(pipelineNode.child(0), previousNode, g, operatorCounters);
    }

    if (pipelineNode.nChildren() > 2) {
      throw new IllegalArgumentException(
          "A pipeline node can have at most 2 children: an operator and an optional pipeline");
    }

    Tree<String> operatorNode = pipelineNode.child(0);
    Tree<String> nextPipelineNode = pipelineNode.nChildren() > 1 ? pipelineNode.child(1) : null;
    if (nextPipelineNode != null && !isPipelineNode(nextPipelineNode.content())) {
      throw new IllegalArgumentException("The second child of a pipeline node must be a pipeline node");
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
    Tree<String> specificOpNode = unwrapOperatorNode(operatorNode);

    switch (specificOpNode.content()) {
      case "<filter>", "<filter_discrete_numeric>", "<filter_continuous_numeric>", "<filter_nominal>" -> {
        String id = "F" + operatorCounters.merge("Filter", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseFilterNode(specificOpNode, id),
            previousNode, g);
      }
      case "<filter_query_condition>" -> {
        String id = "FQ" + operatorCounters.merge("FilterQueryCondition", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseFilterQueryConditionNode(specificOpNode, id),
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
            parseMapNoiseNode(specificOpNode, id, FieldSemanticType.CONTINUOUS_NUMERIC),
            previousNode, g);
      }
      case "<map_noise_continuous_numeric>" -> {
        String id = "MN" + operatorCounters.merge("MapNoise", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapNoiseNode(specificOpNode, id, FieldSemanticType.CONTINUOUS_NUMERIC),
            previousNode, g);
      }
      case "<map_noise_discrete_numeric>" -> {
        String id = "MN" + operatorCounters.merge("MapNoise", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapNoiseNode(specificOpNode, id, FieldSemanticType.DISCRETE_NUMERIC),
            previousNode, g);
      }
      case "<map_noise_nominal>" -> {
        String id = "MN" + operatorCounters.merge("MapNoise", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapNoiseNode(specificOpNode, id, FieldSemanticType.NOMINAL_CATEGORICAL),
            previousNode, g);
      }
      case "<map_condition_preserving_noise_continuous_numeric>" -> {
        String id = "MCN" + operatorCounters.merge("MapConditionPreservingNoise", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapConditionPreservingNoiseNode(specificOpNode, id, FieldSemanticType.CONTINUOUS_NUMERIC),
            previousNode, g);
      }
      case "<map_condition_preserving_noise_discrete_numeric>" -> {
        String id = "MCN" + operatorCounters.merge("MapConditionPreservingNoise", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapConditionPreservingNoiseNode(specificOpNode, id, FieldSemanticType.DISCRETE_NUMERIC),
            previousNode, g);
      }
      case "<map_condition_preserving_noise_nominal>" -> {
        String id = "MCN" + operatorCounters.merge("MapConditionPreservingNoise", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapConditionPreservingNoiseNode(specificOpNode, id, FieldSemanticType.NOMINAL_CATEGORICAL),
            previousNode, g);
      }
      case "<map_rir>" -> {
        String id = "MR" + operatorCounters.merge("MapRIR", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapRIRNode(specificOpNode, id, FieldSemanticType.CONTINUOUS_NUMERIC),
            previousNode, g);
      }
      case "<map_rir_continuous_numeric>" -> {
        String id = "MR" + operatorCounters.merge("MapRIR", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapRIRNode(specificOpNode, id, FieldSemanticType.CONTINUOUS_NUMERIC),
            previousNode, g);
      }
      case "<map_rir_discrete_numeric>" -> {
        String id = "MR" + operatorCounters.merge("MapRIR", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapRIRNode(specificOpNode, id, FieldSemanticType.DISCRETE_NUMERIC),
            previousNode, g);
      }
      case "<map_rir_nominal>" -> {
        String id = "MR" + operatorCounters.merge("MapRIR", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapRIRNode(specificOpNode, id, FieldSemanticType.NOMINAL_CATEGORICAL),
            previousNode, g);
      }
      case "<map_condition_preserving_rir_continuous_numeric>" -> {
        String id = "MCR" + operatorCounters.merge("MapConditionPreservingRIR", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapConditionPreservingRIRNode(specificOpNode, id, FieldSemanticType.CONTINUOUS_NUMERIC),
            previousNode, g);
      }
      case "<map_condition_preserving_rir_discrete_numeric>" -> {
        String id = "MCR" + operatorCounters.merge("MapConditionPreservingRIR", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapConditionPreservingRIRNode(specificOpNode, id, FieldSemanticType.DISCRETE_NUMERIC),
            previousNode, g);
      }
      case "<map_condition_preserving_rir_nominal>" -> {
        String id = "MCR" + operatorCounters.merge("MapConditionPreservingRIR", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapConditionPreservingRIRNode(specificOpNode, id, FieldSemanticType.NOMINAL_CATEGORICAL),
            previousNode, g);
      }
      case "<map_aggregate>", "<map_aggregate_discrete_numeric>", "<map_aggregate_continuous_numeric>" -> {
        String id = "MA" + operatorCounters.merge("MapAggregate", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapAggregateNode(specificOpNode, id),
            previousNode, g);
      }
      case "<map_timestamp_pairwise_swap>", "<map_timestamp_pairwise_swap_nominal>",
          "<map_timestamp_pairwise_swap_discrete_numeric>", "<map_timestamp_pairwise_swap_continuous_numeric>" -> {
        String id = "MTS" + operatorCounters.merge("MapTimestampPairwiseSwap", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapTimestampPairwiseSwapNode(specificOpNode, id),
            previousNode, g);
      }
      case "<map_timestamp_group_shuffle>", "<map_timestamp_group_shuffle_nominal>",
          "<map_timestamp_group_shuffle_discrete_numeric>", "<map_timestamp_group_shuffle_continuous_numeric>" -> {
        String id = "MTG" + operatorCounters.merge("MapTimestampGroupShuffle", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapTimestampGroupShuffleNode(specificOpNode, id),
            previousNode, g);
      }
      case "<map_condition_pairwise_swap_nominal>", "<map_condition_pairwise_swap_discrete_numeric>",
          "<map_condition_pairwise_swap_continuous_numeric>" -> {
        String id = "MCPS" + operatorCounters.merge("MapConditionPairwiseSwap", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapConditionPairwiseSwapNode(specificOpNode, id),
            previousNode, g);
      }
      case "<map_condition_partition_shuffle_nominal>", "<map_condition_partition_shuffle_discrete_numeric>",
          "<map_condition_partition_shuffle_continuous_numeric>" -> {
        String id = "MCGS" + operatorCounters.merge("MapConditionPartitionShuffle", 1, Integer::sum);
        return addSimpleOperatorStepToGraph(
            parseMapConditionPartitionShuffleNode(specificOpNode, id),
            previousNode, g);
      }
      case "<fork_ops_join>" -> {
        return parseForkJoinNode(specificOpNode, previousNode, g, operatorCounters);
      }
      case "<contributor_root>", "<query_condition_fork_sorted>", "<query_condition_fork_unsorted>" -> {
        return parseQueryConditionForkJoinNode(specificOpNode, previousNode, g, operatorCounters);
      }
      case "<fork_discrete_numeric>", "<fork_continuous_numeric>", "<fork_nominal>",
          "<fork_discrete_numeric_sorted>", "<fork_continuous_numeric_sorted>", "<fork_nominal_sorted>",
          "<fork_discrete_numeric_unsorted>", "<fork_continuous_numeric_unsorted>", "<fork_nominal_unsorted>" -> {
        return parseConditionalForkJoinNode(specificOpNode, previousNode, g, operatorCounters);
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
    String field = parseTokenNode(specificOpNode.child(0));
    String condition = parseTokenNode(specificOpNode.child(1));
    String value = parseValueNode(specificOpNode.child(2));
    if (isSampledValue(value)) {
      return new FilterOperator(id, field, condition, sampleFilterValue(value, field));
    }

    return new FilterOperator(id, field, condition, Double.parseDouble(value));
  }

  private OperatorRepresentation parseFilterQueryConditionNode(Tree<String> specificOpNode, String id) {
    return new FilterQueryCondition(
        id,
        parseQueryConditionId(specificOpNode.child(0)),
        parseConditionKeepNode(specificOpNode.child(1)));
  }

  private static String parseTokenNode(Tree<String> node) {
    if (node.nChildren() == 0) {
      return node.content();
    }
    if (node.nChildren() == 1 && node.child(0).nChildren() == 0) {
      return node.child(0).content();
    }
    throw new IllegalArgumentException("Expected terminal or unary token node, got: " + node.content());
  }

  private String parseValueNode(Tree<String> valueNode) {
    if (valueNode.nChildren() == 0) {
      return valueNode.content();
    }
    StringBuilder valueStringBuilder = new StringBuilder();
    valueNode.childStream().forEach(c -> {
      if (c.nChildren() == 0) {
        valueStringBuilder.append(c.content());
      } else {
        valueStringBuilder.append(c.child(0).content());
      }
    }); // value attribute
    return valueStringBuilder.toString();
  }

  private double sampleFilterValue(String value, String field) {
    if (fieldValueSampler == null) {
      throw new IllegalStateException(
          "Value token '" + value + "' requires a FieldValueSampler configured on QueryMapper");
    }
    return switch (value) {
      case URIR -> fieldValueSampler.urir(field);
      case DRIR -> fieldValueSampler.drir(field);
      case UCR -> fieldValueSampler.ucr(field);
      case DCR -> fieldValueSampler.dcr(field);
      default -> throw new IllegalArgumentException("Unknown sampled filter value: " + value);
    };
  }

  private static boolean isSampledValue(String value) {
    return value.equals(URIR) || value.equals(DRIR) || value.equals(UCR) || value.equals(DCR);
  }

  private OperatorRepresentation parseMapDuplicateNode(Tree<String> specificOpNode, String id) {
    return new MapDuplicate(id, Double.parseDouble(specificOpNode.child(0).child(0).content()));
  }

  private OperatorRepresentation parseMapNoiseNode(Tree<String> specificOpNode, String id, FieldSemanticType fieldType) {
    return new MapNoise(id, specificOpNode.child(0).child(0).content(),
        Double.parseDouble(specificOpNode.child(1).child(0).content()), fieldType);

  }

  private OperatorRepresentation parseMapConditionPreservingNoiseNode(
      Tree<String> specificOpNode, String id, FieldSemanticType fieldType) {
    return new MapConditionPreservingNoise(
        id,
        parseTokenNode(specificOpNode.child(0)),
        Double.parseDouble(parseTokenNode(specificOpNode.child(1))),
        parseQueryConditionId(specificOpNode.child(2)),
        fieldType);
  }

  private OperatorRepresentation parseMapRIRNode(Tree<String> specificOpNode, String id, FieldSemanticType fieldType) {
    return new MapRIR(id, specificOpNode.child(0).child(0).content(), fieldType);
  }

  private OperatorRepresentation parseMapConditionPreservingRIRNode(
      Tree<String> specificOpNode, String id, FieldSemanticType fieldType) {
    return new MapConditionPreservingRIR(
        id,
        parseTokenNode(specificOpNode.child(0)),
        parseQueryConditionId(specificOpNode.child(1)),
        fieldType);
  }

  private OperatorRepresentation parseMapAggregateNode(Tree<String> specificOpNode, String id) {
    return new MapAggregate(id, specificOpNode.child(0).child(0).content(),
        MapAggregateAggregatorFunction.valueOf(specificOpNode.child(1).child(0).content().toUpperCase()),
        Integer.valueOf(specificOpNode.child(2).child(0).content()));
  }

  private OperatorRepresentation parseMapTimestampPairwiseSwapNode(Tree<String> specificOpNode, String id) {
    return new MapTimestampPairwiseSwap(id, parseTokenNode(specificOpNode.child(0)).replace("'", ""));
  }

  private OperatorRepresentation parseMapTimestampGroupShuffleNode(Tree<String> specificOpNode, String id) {
    return new MapTimestampGroupShuffle(id, parseTokenNode(specificOpNode.child(0)).replace("'", ""));
  }

  private OperatorRepresentation parseMapConditionPairwiseSwapNode(Tree<String> specificOpNode, String id) {
    return new MapConditionPairwiseSwap(
        id,
        parseTokenNode(specificOpNode.child(0)).replace("'", ""),
        parseQueryConditionId(specificOpNode.child(1)));
  }

  private OperatorRepresentation parseMapConditionPartitionShuffleNode(Tree<String> specificOpNode, String id) {
    return new MapConditionPartitionShuffle(
        id,
        parseTokenNode(specificOpNode.child(0)).replace("'", ""),
        parseQueryConditionId(specificOpNode.child(1)));
  }

  private static String parseQueryConditionId(Tree<String> conditionNode) {
    return parseTokenNode(conditionNode).replace("'", "");
  }

  private static boolean parseConditionKeepNode(Tree<String> keepNode) {
    return switch (parseTokenNode(keepNode)) {
      case "keep_true" -> true;
      case "keep_false" -> false;
      default -> throw new IllegalArgumentException("Unknown condition keep token: " + parseTokenNode(keepNode));
    };
  }

  private OperatorRepresentation parseForkJoinNode(Tree<String> specificOpNode,
      OperatorRepresentation previousNode, Graph<OperatorRepresentation, ArcType> g,
      Map<String, Integer> operatorCounters) {
    if (specificOpNode.nChildren() != 2) {
      throw new IllegalArgumentException(
          "A <fork_ops_join> node must have exactly 2 children, representing the two branches of the fork");
    }
    if (!isPipelineNode(specificOpNode.child(0).content())
        || !isPipelineNode(specificOpNode.child(1).content())) {
      throw new IllegalArgumentException("The children of a <fork_ops_join> node must be pipeline nodes");
    }

    Tree<String> leftPipelineNode = specificOpNode.child(0);
    Tree<String> rightPipelineNode = specificOpNode.child(1);

    String forkId = "FK" + operatorCounters.merge("Fork", 1, Integer::sum);

    OperatorRepresentation forkOp = new Fork(forkId);
    g.addNode(forkOp);
    g.setArcValue(previousNode, forkOp, ArcType.DEFAULT_ARC);

    return parseBranchPipelinesAndJoin(leftPipelineNode, rightPipelineNode, forkOp, g, operatorCounters);

  }

  private OperatorRepresentation parseConditionalForkJoinNode(Tree<String> specificOpNode,
      OperatorRepresentation previousNode, Graph<OperatorRepresentation, ArcType> g,
      Map<String, Integer> operatorCounters) {
    if (specificOpNode.nChildren() != 5) {
      throw new IllegalArgumentException(
          specificOpNode.content() + " must have a field, condition, value, and two <pipeline> children");
    }
    if (!isPipelineNode(specificOpNode.child(3).content())
        || !isPipelineNode(specificOpNode.child(4).content())) {
      throw new IllegalArgumentException("The last two children of " + specificOpNode.content()
          + " must be pipeline nodes");
    }

    String field = parseTokenNode(specificOpNode.child(0));
    String condition = parseTokenNode(specificOpNode.child(1));
    String valueToken = parseValueNode(specificOpNode.child(2));
    double value = isSampledValue(valueToken) ? sampleFilterValue(valueToken, field) : Double.parseDouble(valueToken);

    String forkId = "CFK" + operatorCounters.merge("ConditionalFork", 1, Integer::sum);
    OperatorRepresentation forkOp = new ConditionalFork(forkId, field, condition, value);
    g.addNode(forkOp);
    g.setArcValue(previousNode, forkOp, ArcType.DEFAULT_ARC);

    return parseBranchPipelinesAndJoin(
        specificOpNode.child(3), specificOpNode.child(4), forkOp, g, operatorCounters);
  }

  private OperatorRepresentation parseQueryConditionForkJoinNode(Tree<String> specificOpNode,
      OperatorRepresentation previousNode, Graph<OperatorRepresentation, ArcType> g,
      Map<String, Integer> operatorCounters) {
    if (specificOpNode.nChildren() != 3) {
      throw new IllegalArgumentException(
          specificOpNode.content() + " must have a query condition and two pipeline children");
    }
    if (!isPipelineNode(specificOpNode.child(1).content())
        || !isPipelineNode(specificOpNode.child(2).content())) {
      throw new IllegalArgumentException("The last two children of " + specificOpNode.content()
          + " must be pipeline nodes");
    }

    String forkId = "QCF" + operatorCounters.merge("QueryConditionFork", 1, Integer::sum);
    OperatorRepresentation forkOp = new QueryConditionFork(forkId, parseQueryConditionId(specificOpNode.child(0)));
    g.addNode(forkOp);
    g.setArcValue(previousNode, forkOp, ArcType.DEFAULT_ARC);

    return parseBranchPipelinesAndJoin(
        specificOpNode.child(1), specificOpNode.child(2), forkOp, g, operatorCounters);
  }

  private OperatorRepresentation parseBranchPipelinesAndJoin(
      Tree<String> leftPipelineNode,
      Tree<String> rightPipelineNode,
      OperatorRepresentation forkOp,
      Graph<OperatorRepresentation, ArcType> g,
      Map<String, Integer> operatorCounters) {
    if (isEmptyPipelineNode(leftPipelineNode) && isEmptyPipelineNode(rightPipelineNode)) {
      throw new IllegalArgumentException("A fork cannot have two empty branches");
    }

    String unionId = "U" + operatorCounters.merge("Union", 1, Integer::sum);
    OperatorRepresentation joinOp = new Union(unionId);
    g.addNode(joinOp);

    OperatorRepresentation leftBranch = null;
    if (isEmptyPipelineNode(leftPipelineNode)) {
      g.setArcValue(forkOp, joinOp, ArcType.DEFAULT_ARC);
    } else {
      leftBranch = parsePipelineNode(leftPipelineNode, forkOp, g, operatorCounters);
    }

    OperatorRepresentation rightBranch = null;
    if (isEmptyPipelineNode(rightPipelineNode)) {
      g.setArcValue(forkOp, joinOp, ArcType.DEFAULT_ARC);
    } else {
      rightBranch = parsePipelineNode(rightPipelineNode, forkOp, g, operatorCounters);
    }

    if (leftBranch != null) {
      g.setArcValue(leftBranch, joinOp, ArcType.DEFAULT_ARC);
    }
    if (rightBranch != null) {
      g.setArcValue(rightBranch, joinOp, ArcType.DEFAULT_ARC);
    }

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
    if (isPipelineNode(content) || isOperatorWrapperNode(content)) {
      for (Tree<String> child : node) {
        appendTreeOperators(sb, child, level);
      }
      return;
    }

    if (isOperatorNode(content)) {
      sb.append("\t".repeat(level)).append(operatorName(content)).append('\n');
      if (content.equals("<fork_ops_join>") || content.startsWith("<fork_")) {
        int printedPipelines = 0;
        for (int i = 0; i < node.nChildren(); i++) {
          if (!node.child(i).content().equals("<pipeline>")) {
            continue;
          }
          if (printedPipelines > 0) {
            sb.append('\n');
          }
          appendTreeOperators(sb, node.child(i), level + 1);
          printedPipelines++;
        }
      }
    }
  }

  private static boolean isOperatorNode(String content) {
    return switch (content) {
      case "<filter>", "<filter_discrete_numeric>", "<filter_continuous_numeric>", "<filter_nominal>",
          "<filter_query_condition>", "<map_duplicate>", "<map_noise>", "<map_noise_nominal>",
          "<map_noise_discrete_numeric>", "<map_noise_continuous_numeric>",
          "<map_condition_preserving_noise_nominal>", "<map_condition_preserving_noise_discrete_numeric>",
          "<map_condition_preserving_noise_continuous_numeric>", "<map_rir>", "<map_rir_nominal>",
          "<map_rir_discrete_numeric>", "<map_rir_continuous_numeric>", "<map_condition_preserving_rir_nominal>",
          "<map_condition_preserving_rir_discrete_numeric>", "<map_condition_preserving_rir_continuous_numeric>",
          "<map_aggregate>", "<map_aggregate_discrete_numeric>",
          "<map_aggregate_continuous_numeric>", "<map_timestamp_pairwise_swap>",
          "<map_timestamp_pairwise_swap_nominal>", "<map_timestamp_pairwise_swap_discrete_numeric>",
          "<map_timestamp_pairwise_swap_continuous_numeric>", "<map_timestamp_group_shuffle>",
          "<map_timestamp_group_shuffle_nominal>", "<map_timestamp_group_shuffle_discrete_numeric>",
          "<map_timestamp_group_shuffle_continuous_numeric>", "<map_condition_pairwise_swap_nominal>",
          "<map_condition_pairwise_swap_discrete_numeric>", "<map_condition_pairwise_swap_continuous_numeric>",
          "<map_condition_partition_shuffle_nominal>", "<map_condition_partition_shuffle_discrete_numeric>",
          "<map_condition_partition_shuffle_continuous_numeric>", "<fork_ops_join>", "<fork_discrete_numeric>",
          "<fork_continuous_numeric>", "<fork_nominal>", "<contributor_root>", "<fork_discrete_numeric_sorted>",
          "<fork_continuous_numeric_sorted>", "<fork_nominal_sorted>", "<fork_discrete_numeric_unsorted>",
          "<fork_continuous_numeric_unsorted>", "<fork_nominal_unsorted>", "<query_condition_fork_sorted>",
          "<query_condition_fork_unsorted>" -> true;
      default -> false;
    };
  }

  private static boolean isPipelineNode(String content) {
    return content.equals("<pipeline>")
        || content.equals("<sorted_pipeline>")
        || content.equals("<unsorted_pipeline>")
        || content.equals("<empty_pipeline>");
  }

  private static boolean isEmptyPipelineNode(Tree<String> node) {
    return node.content().equals("<empty_pipeline>");
  }

  private static boolean isOperatorWrapperNode(String content) {
    return content.equals("<operator>")
        || content.equals("<sorted_operator>")
        || content.equals("<unsorted_operator>")
        || content.equals("<ordinary_operator>")
        || content.equals("<timestamp_sensitive_operator>")
        || content.equals("<sorted_fork>")
        || content.equals("<unsorted_fork>");
  }

  private static Tree<String> unwrapOperatorNode(Tree<String> operatorNode) {
    Tree<String> current = operatorNode;
    while (!isOperatorNode(current.content())) {
      if (current.nChildren() != 1) {
        throw new IllegalArgumentException("Expected operator node or unary operator wrapper, got: "
            + current.content());
      }
      current = current.child(0);
    }
    return current;
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

  public static String fieldUsedBy(Object node) {
    if (node instanceof QueryMapper.FilterOperator filterOperator) {
      return filterOperator.field();
    }
    if (node instanceof QueryMapper.FilterQueryCondition filterQueryCondition) {
      return TupleConditionSpec.fromId(filterQueryCondition.conditionId()).field();
    }
    if (node instanceof QueryMapper.MapNoise mapNoise) {
      return mapNoise.field();
    }
    if (node instanceof QueryMapper.MapConditionPreservingNoise mapConditionPreservingNoise) {
      return mapConditionPreservingNoise.field();
    }
    if (node instanceof QueryMapper.MapRIR mapRIR) {
      return mapRIR.field();
    }
    if (node instanceof QueryMapper.MapConditionPreservingRIR mapConditionPreservingRIR) {
      return mapConditionPreservingRIR.field();
    }
    if (node instanceof QueryMapper.MapAggregate mapAggregate) {
      return mapAggregate.field();
    }
    if (node instanceof QueryMapper.MapTimestampPairwiseSwap mapTimestampPairwiseSwap) {
      return mapTimestampPairwiseSwap.field();
    }
    if (node instanceof QueryMapper.MapTimestampGroupShuffle mapTimestampGroupShuffle) {
      return mapTimestampGroupShuffle.field();
    }
    if (node instanceof QueryMapper.MapConditionPairwiseSwap mapConditionPairwiseSwap) {
      return mapConditionPairwiseSwap.field();
    }
    if (node instanceof QueryMapper.MapConditionPartitionShuffle mapConditionPartitionShuffle) {
      return mapConditionPartitionShuffle.field();
    }
    if (node instanceof QueryMapper.ConditionalFork conditionalFork) {
      return conditionalFork.field();
    }
    if (node instanceof QueryMapper.QueryConditionFork queryConditionFork) {
      return TupleConditionSpec.fromId(queryConditionFork.conditionId()).field();
    }
    return null;
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

  public enum FieldSemanticType {
    CONTINUOUS_NUMERIC,
    DISCRETE_NUMERIC,
    NOMINAL_CATEGORICAL
  }

  private static long deterministicSeed(Object... parts) {
    long seed = 1125899906842597L;
    for (Object part : parts) {
      seed = 31L * seed + Objects.hashCode(part);
    }
    return seed;
  }

  private static TupleFieldType toTupleFieldType(FieldSemanticType fieldType) {
    return switch (fieldType) {
      case CONTINUOUS_NUMERIC -> TupleFieldType.CONTINUOUS_NUMERIC;
      case DISCRETE_NUMERIC -> TupleFieldType.DISCRETE_NUMERIC;
      case NOMINAL_CATEGORICAL -> TupleFieldType.NOMINAL_CATEGORICAL;
    };
  }

  public sealed interface OperatorRepresentation
      permits FilterOperator, FilterQueryCondition, MapDuplicate, MapNoise, MapConditionPreservingNoise,
      MapRIR, MapConditionPreservingRIR, MapAggregate, MapTimestampPairwiseSwap, MapTimestampGroupShuffle,
      MapConditionPairwiseSwap, MapConditionPartitionShuffle, Fork, ConditionalFork, QueryConditionFork,
      Union, Source, Sink {
    public String getID();
  }

  public record FilterOperator(String id, String field, String condition, double value)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public FilterFunction<Tuple> createFilterFunction() {
      return createTupleCondition(field, condition, value);
    }
  }

  public record FilterQueryCondition(String id, String conditionId, boolean keepMatching)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public FilterFunction<Tuple> createFilterFunction() {
      TupleConditionSpec conditionSpec = TupleConditionSpec.fromId(conditionId);
      return tuple -> conditionSpec.test(tuple) == keepMatching;
    }
  }

  private static FilterFunction<Tuple> createTupleCondition(String field, String condition, double value) {
    double conditionValue = requireFinite(field, value);
    return switch (condition) {
      case "lt" -> t -> requireFinite(field, t.lookup(field)) < conditionValue;
      case "gt" -> t -> requireFinite(field, t.lookup(field)) > conditionValue;
      case "eq" -> t -> Double.compare(requireFinite(field, t.lookup(field)), conditionValue) == 0;
      case "neq" -> t -> Double.compare(requireFinite(field, t.lookup(field)), conditionValue) != 0;
      default -> throw new IllegalArgumentException("Unknown condition " + condition);
    };
  }

  public record MapDuplicate(String id, double probability) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public FlatMapFunction<Tuple, Tuple> createMapFunction() {
      return new MapDuplicateFunction(probability, deterministicSeed("MapDuplicate", id, probability));
    }
  }

  public record MapNoise(String id, String field, double probability, FieldSemanticType fieldType)
      implements OperatorRepresentation {
    public MapNoise(String id, String field, double probability) {
      this(id, field, probability, FieldSemanticType.CONTINUOUS_NUMERIC);
    }

    @Override
    public String getID() {
      return id;
    }

    public MapFunction<Tuple, Tuple> createMapFunction() {
      long seed = deterministicSeed("MapNoise", id, field, probability, fieldType);
      return switch (fieldType) {
        case CONTINUOUS_NUMERIC -> new MapNoiseFunction(field, probability, seed);
        case DISCRETE_NUMERIC -> new DiscreteNumericNoiseFunction(field, probability, seed);
        case NOMINAL_CATEGORICAL -> new CategoricalNoiseFunction(field, probability, seed);
      };
    }
  }

  public record MapConditionPreservingNoise(
      String id, String field, double probability, String conditionId, FieldSemanticType fieldType)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public MapFunction<Tuple, Tuple> createMapFunction() {
      return new ConditionPreservingNoiseFunction(
          field,
          probability,
          TupleConditionSpec.fromId(conditionId),
          toTupleFieldType(fieldType),
          deterministicSeed("MapConditionPreservingNoise", id, field, probability, conditionId, fieldType));
    }
  }

  public record MapRIR(String id, String field, FieldSemanticType fieldType) implements OperatorRepresentation {
    public MapRIR(String id, String field) {
      this(id, field, FieldSemanticType.CONTINUOUS_NUMERIC);
    }

    @Override
    public String getID() {
      return id;
    }

    public MapFunction<Tuple, Tuple> createRIRMap() {
      long seed = deterministicSeed("MapRIR", id, field, fieldType);
      return switch (fieldType) {
        case CONTINUOUS_NUMERIC -> new RIRMap(field, seed);
        case DISCRETE_NUMERIC -> new DiscreteNumericRIRMap(field, seed);
        case NOMINAL_CATEGORICAL -> new CategoricalRIRMap(field, seed);
      };
    }
  }

  public record MapConditionPreservingRIR(String id, String field, String conditionId, FieldSemanticType fieldType)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public MapFunction<Tuple, Tuple> createRIRMap() {
      return new ConditionPreservingRIRMap(
          field,
          TupleConditionSpec.fromId(conditionId),
          toTupleFieldType(fieldType),
          deterministicSeed("MapConditionPreservingRIR", id, field, conditionId, fieldType));
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

  public record MapTimestampPairwiseSwap(String id, String field) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public TimestampPairwiseFieldSwapFunction createMapFunction() {
      return new TimestampPairwiseFieldSwapFunction(field);
    }
  }

  public record MapTimestampGroupShuffle(String id, String field) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public TimestampGroupFieldShuffleFunction createMapFunction() {
      return new TimestampGroupFieldShuffleFunction(
          field, deterministicSeed("MapTimestampGroupShuffle", id, field));
    }
  }

  public record MapConditionPairwiseSwap(String id, String field, String conditionId) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public ConditionPairwiseFieldSwapFunction createMapFunction() {
      return new ConditionPairwiseFieldSwapFunction(field, TupleConditionSpec.fromId(conditionId));
    }
  }

  public record MapConditionPartitionShuffle(String id, String field, String conditionId)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public ConditionPartitionFieldShuffleFunction createMapFunction() {
      return new ConditionPartitionFieldShuffleFunction(
          field,
          TupleConditionSpec.fromId(conditionId),
          deterministicSeed("MapConditionPartitionShuffle", id, field, conditionId));
    }
  }

  public record Fork(String id) implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }
  }

  public record ConditionalFork(String id, String field, String condition, double value)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public ConditionalTupleRouterOperator createRouterOperator() {
      return new ConditionalTupleRouterOperator(id, createTupleCondition(field, condition, value));
    }
  }

  public record QueryConditionFork(String id, String conditionId)
      implements OperatorRepresentation {
    @Override
    public String getID() {
      return id;
    }

    public ConditionalTupleRouterOperator createRouterOperator() {
      TupleConditionSpec conditionSpec = TupleConditionSpec.fromId(conditionId);
      return new ConditionalTupleRouterOperator(id, conditionSpec::test);
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

  public static Graph<OperatorRepresentation, ArcType> parseGraphFromString(String representation) {
    String graphString = String.join(" ", representation.trim().split("\\s+"));
    int nodesStart = graphString.indexOf("nodes=[");
    int arcsStart = graphString.indexOf("], arcs={");
    if (nodesStart < 0 || arcsStart < 0) {
      throw new IllegalArgumentException("Expected graph string with nodes=[...] and arcs={...}: " + representation);
    }

    String nodesString = graphString.substring(nodesStart + "nodes=[".length(), arcsStart);
    int arcsContentStart = arcsStart + "], arcs={".length();
    int arcsContentEnd = graphString.endsWith("}}") ? graphString.length() - 2 : graphString.length() - 1;
    if (arcsContentEnd < arcsContentStart) {
      throw new IllegalArgumentException("Malformed arcs section in graph string: " + representation);
    }
    String arcsString = graphString.substring(arcsContentStart, arcsContentEnd);

    Graph<OperatorRepresentation, ArcType> graph = new LinkedHashGraph<>();
    Map<String, OperatorRepresentation> nodesByRepresentation = new HashMap<>();
    for (String nodeString : splitTopLevel(nodesString, ',')) {
      OperatorRepresentation node = parseOperatorRepresentationFromString(nodeString);
      nodesByRepresentation.put(nodeString, node);
      graph.addNode(node);
    }

    if (!arcsString.isBlank()) {
      for (String arcString : splitTopLevel(arcsString, ',')) {
        int arrowIndex = arcString.indexOf("->");
        int valueIndex = arcString.lastIndexOf('=');
        if (arrowIndex < 0 || valueIndex < 0 || valueIndex < arrowIndex) {
          throw new IllegalArgumentException("Malformed arc representation: " + arcString);
        }

        String sourceString = arcString.substring(0, arrowIndex).trim();
        String targetString = arcString.substring(arrowIndex + 2, valueIndex).trim();
        ArcType arcType = ArcType.valueOf(arcString.substring(valueIndex + 1).trim());

        OperatorRepresentation source = nodesByRepresentation.computeIfAbsent(sourceString,
            QueryMapper::parseOperatorRepresentationFromString);
        OperatorRepresentation target = nodesByRepresentation.computeIfAbsent(targetString,
            QueryMapper::parseOperatorRepresentationFromString);
        graph.addNode(source);
        graph.addNode(target);
        graph.setArcValue(source, target, arcType);
      }
    }

    return graph;
  }

  public static OperatorRepresentation parseOperatorRepresentationFromString(String representation) {
    String operatorName = representation.substring(0, representation.indexOf('['));
    return switch (operatorName) {
      case "FilterOperator" -> parseFilterOperatorFromString(representation);
      case "FilterQueryCondition" -> parseFilterQueryConditionFromString(representation);
      case "MapDuplicate" -> parseMapDuplicateFromString(representation);
      case "MapNoise" -> parseMapNoiseFromString(representation);
      case "MapConditionPreservingNoise" -> parseMapConditionPreservingNoiseFromString(representation);
      case "MapRIR" -> parseMapRIRFromString(representation);
      case "MapConditionPreservingRIR" -> parseMapConditionPreservingRIRFromString(representation);
      case "MapAggregate" -> parseMapAggregateFromString(representation);
      case "MapTimestampPairwiseSwap" -> parseMapTimestampPairwiseSwapFromString(representation);
      case "MapTimestampGroupShuffle" -> parseMapTimestampGroupShuffleFromString(representation);
      case "MapConditionPairwiseSwap" -> parseMapConditionPairwiseSwapFromString(representation);
      case "MapConditionPartitionShuffle" -> parseMapConditionPartitionShuffleFromString(representation);
      case "Fork" -> parseForkFromString(representation);
      case "ConditionalFork" -> parseConditionalForkFromString(representation);
      case "QueryConditionFork" -> parseQueryConditionForkFromString(representation);
      case "Union" -> parseUnionFromString(representation);
      case "Source" -> parseSourceFromString(representation);
      case "Sink" -> parseSinkFromString(representation);
      default -> throw new IllegalArgumentException("Unknown operator representation: " + representation);
    };
  }

  public static FilterOperator parseFilterOperatorFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "FilterOperator");
    return new FilterOperator(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        requiredParam(params, "condition", representation),
        Double.parseDouble(requiredParam(params, "value", representation)));
  }

  public static FilterQueryCondition parseFilterQueryConditionFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "FilterQueryCondition");
    return new FilterQueryCondition(
        requiredParam(params, "id", representation),
        requiredParam(params, "conditionId", representation),
        Boolean.parseBoolean(requiredParam(params, "keepMatching", representation)));
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
        Double.parseDouble(requiredParam(params, "probability", representation)),
        optionalFieldSemanticType(params, representation));
  }

  public static MapConditionPreservingNoise parseMapConditionPreservingNoiseFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapConditionPreservingNoise");
    return new MapConditionPreservingNoise(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        Double.parseDouble(requiredParam(params, "probability", representation)),
        requiredParam(params, "conditionId", representation),
        optionalFieldSemanticType(params, representation));
  }

  public static MapRIR parseMapRIRFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapRIR");
    return new MapRIR(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        optionalFieldSemanticType(params, representation));
  }

  public static MapConditionPreservingRIR parseMapConditionPreservingRIRFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapConditionPreservingRIR");
    return new MapConditionPreservingRIR(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        requiredParam(params, "conditionId", representation),
        optionalFieldSemanticType(params, representation));
  }

  public static MapAggregate parseMapAggregateFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapAggregate");
    return new MapAggregate(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        MapAggregateAggregatorFunction.valueOf(requiredParam(params, "aggFunction", representation)),
        Integer.parseInt(requiredParam(params, "windowSize", representation)));
  }

  public static MapTimestampPairwiseSwap parseMapTimestampPairwiseSwapFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapTimestampPairwiseSwap");
    return new MapTimestampPairwiseSwap(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation));
  }

  public static MapTimestampGroupShuffle parseMapTimestampGroupShuffleFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapTimestampGroupShuffle");
    return new MapTimestampGroupShuffle(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation));
  }

  public static MapConditionPairwiseSwap parseMapConditionPairwiseSwapFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapConditionPairwiseSwap");
    return new MapConditionPairwiseSwap(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        requiredParam(params, "conditionId", representation));
  }

  public static MapConditionPartitionShuffle parseMapConditionPartitionShuffleFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "MapConditionPartitionShuffle");
    return new MapConditionPartitionShuffle(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        requiredParam(params, "conditionId", representation));
  }

  public static Fork parseForkFromString(String representation) {
    return new Fork(requiredParam(parseOperatorParams(representation, "Fork"), "id", representation));
  }

  public static ConditionalFork parseConditionalForkFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "ConditionalFork");
    return new ConditionalFork(
        requiredParam(params, "id", representation),
        requiredParam(params, "field", representation),
        requiredParam(params, "condition", representation),
        Double.parseDouble(requiredParam(params, "value", representation)));
  }

  public static QueryConditionFork parseQueryConditionForkFromString(String representation) {
    Map<String, String> params = parseOperatorParams(representation, "QueryConditionFork");
    return new QueryConditionFork(
        requiredParam(params, "id", representation),
        requiredParam(params, "conditionId", representation));
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

  private static List<String> splitTopLevel(String text, char separator) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '[' || c == '{' || c == '(') {
        depth++;
      } else if (c == ']' || c == '}' || c == ')') {
        depth--;
      } else if (c == separator && depth == 0) {
        addNonBlankPart(parts, text.substring(start, i));
        start = i + 1;
      }
    }
    addNonBlankPart(parts, text.substring(start));
    return parts;
  }

  private static void addNonBlankPart(List<String> parts, String part) {
    String trimmed = part.trim();
    if (!trimmed.isEmpty()) {
      parts.add(trimmed);
    }
  }

  private static String requiredParam(Map<String, String> params, String name, String representation) {
    String value = params.get(name);
    if (value == null) {
      throw new IllegalArgumentException("Missing parameter '" + name + "' in: " + representation);
    }
    return value;
  }

  private static FieldSemanticType optionalFieldSemanticType(Map<String, String> params, String representation) {
    String value = params.get("fieldType");
    if (value == null) {
      return FieldSemanticType.CONTINUOUS_NUMERIC;
    }
    try {
      return FieldSemanticType.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown fieldType '" + value + "' in: " + representation, e);
    }
  }

}
