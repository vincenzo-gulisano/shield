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
import java.util.function.Function;
import mappers.QueryMapper.Arc;
import mappers.QueryMapper.OperatorRepresentation;

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
      g.addNode(null);
      g.setArcValue(null, null, Arc.DEFAULT_ARC);
      return g;
    };
  }

  public enum Arc {DEFAULT_ARC}

  public interface OperatorRepresentation {}
}