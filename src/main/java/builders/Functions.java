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

package builders;

import io.github.ericmedvet.jgea.core.representation.graph.Graph;
import io.github.ericmedvet.jnb.core.Cacheable;
import io.github.ericmedvet.jnb.core.Discoverable;
import io.github.ericmedvet.jnb.core.Param;
import io.github.ericmedvet.jnb.datastructure.NamedFunction;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import mappers.QueryMapper;

@Discoverable(prefixTemplate = "anonym.function|f")
public class Functions {

  private Functions() {
  }

  /*
   * Returns a bitmap that represents which anonymization operator types are present in a graph.
   * The graph is traversed once; each supported operator type sets its corresponding bit.
   * Multiple nodes of the same type still set the bit only once.
   *
   * Bit layout:
   * bit 0 = FilterOperator
   * bit 1 = MapDuplicate
   * bit 2 = MapNoise
   * bit 3 = MapRIR
   * bit 4 = MapAggregate
   * bit 5 = Fork or ConditionalFork
   *
   * Source, Sink, and Union are structural nodes and do not contribute to the bitmap.
   *
   * All possible returned values for the 6 supported operator types:
   *  0 = ()
   *  1 = (FilterOperator)
   *  2 = (MapDuplicate)
   *  3 = (FilterOperator, MapDuplicate)
   *  4 = (MapNoise)
   *  5 = (FilterOperator, MapNoise)
   *  6 = (MapDuplicate, MapNoise)
   *  7 = (FilterOperator, MapDuplicate, MapNoise)
   *  8 = (MapRIR)
   *  9 = (FilterOperator, MapRIR)
   * 10 = (MapDuplicate, MapRIR)
   * 11 = (FilterOperator, MapDuplicate, MapRIR)
   * 12 = (MapNoise, MapRIR)
   * 13 = (FilterOperator, MapNoise, MapRIR)
   * 14 = (MapDuplicate, MapNoise, MapRIR)
   * 15 = (FilterOperator, MapDuplicate, MapNoise, MapRIR)
   * 16 = (MapAggregate)
   * 17 = (FilterOperator, MapAggregate)
   * 18 = (MapDuplicate, MapAggregate)
   * 19 = (FilterOperator, MapDuplicate, MapAggregate)
   * 20 = (MapNoise, MapAggregate)
   * 21 = (FilterOperator, MapNoise, MapAggregate)
   * 22 = (MapDuplicate, MapNoise, MapAggregate)
   * 23 = (FilterOperator, MapDuplicate, MapNoise, MapAggregate)
   * 24 = (MapRIR, MapAggregate)
   * 25 = (FilterOperator, MapRIR, MapAggregate)
   * 26 = (MapDuplicate, MapRIR, MapAggregate)
   * 27 = (FilterOperator, MapDuplicate, MapRIR, MapAggregate)
   * 28 = (MapNoise, MapRIR, MapAggregate)
   * 29 = (FilterOperator, MapNoise, MapRIR, MapAggregate)
   * 30 = (MapDuplicate, MapNoise, MapRIR, MapAggregate)
   * 31 = (FilterOperator, MapDuplicate, MapNoise, MapRIR, MapAggregate)
   * 32 = (Fork)
   * 33 = (FilterOperator, Fork)
   * 34 = (MapDuplicate, Fork)
   * 35 = (FilterOperator, MapDuplicate, Fork)
   * 36 = (MapNoise, Fork)
   * 37 = (FilterOperator, MapNoise, Fork)
   * 38 = (MapDuplicate, MapNoise, Fork)
   * 39 = (FilterOperator, MapDuplicate, MapNoise, Fork)
   * 40 = (MapRIR, Fork)
   * 41 = (FilterOperator, MapRIR, Fork)
   * 42 = (MapDuplicate, MapRIR, Fork)
   * 43 = (FilterOperator, MapDuplicate, MapRIR, Fork)
   * 44 = (MapNoise, MapRIR, Fork)
   * 45 = (FilterOperator, MapNoise, MapRIR, Fork)
   * 46 = (MapDuplicate, MapNoise, MapRIR, Fork)
   * 47 = (FilterOperator, MapDuplicate, MapNoise, MapRIR, Fork)
   * 48 = (MapAggregate, Fork)
   * 49 = (FilterOperator, MapAggregate, Fork)
   * 50 = (MapDuplicate, MapAggregate, Fork)
   * 51 = (FilterOperator, MapDuplicate, MapAggregate, Fork)
   * 52 = (MapNoise, MapAggregate, Fork)
   * 53 = (FilterOperator, MapNoise, MapAggregate, Fork)
   * 54 = (MapDuplicate, MapNoise, MapAggregate, Fork)
   * 55 = (FilterOperator, MapDuplicate, MapNoise, MapAggregate, Fork)
   * 56 = (MapRIR, MapAggregate, Fork)
   * 57 = (FilterOperator, MapRIR, MapAggregate, Fork)
   * 58 = (MapDuplicate, MapRIR, MapAggregate, Fork)
   * 59 = (FilterOperator, MapDuplicate, MapRIR, MapAggregate, Fork)
   * 60 = (MapNoise, MapRIR, MapAggregate, Fork)
   * 61 = (FilterOperator, MapNoise, MapRIR, MapAggregate, Fork)
   * 62 = (MapDuplicate, MapNoise, MapRIR, MapAggregate, Fork)
   * 63 = (FilterOperator, MapDuplicate, MapNoise, MapRIR, MapAggregate, Fork)
   */
  @Cacheable
  public static <X, N, A> NamedFunction<X, Integer> graphOperatorsBitmapEntry(
      @Param(value = "name", dS = "graph.operators.bitmap.entry") String name,
      @Param(value = "of", dNPM = "f.identity()") Function<X, Graph<N, A>> beforeF) {
    Function<Graph<N, A>, Integer> f = g -> {
      int bitmap = 0;
      for (N node : g.nodes()) {
        bitmap |= operatorBit(node);
      }
      return bitmap;
    };
    return NamedFunction.from(f, name).compose(beforeF);
  }

  @Cacheable
  public static <X, N, A> NamedFunction<X, Integer> graphForksCount(
      @Param(value = "name", dS = "graph.forks.count") String name,
      @Param(value = "of", dNPM = "f.identity()") Function<X, Graph<N, A>> beforeF) {
    Function<Graph<N, A>, Integer> f = g -> {
      int count = 0;
      for (N node : g.nodes()) {
        if (node instanceof QueryMapper.Fork || node instanceof QueryMapper.ConditionalFork) {
          count++;
        }
      }
      return count;
    };
    return NamedFunction.from(f, name).compose(beforeF);
  }

  @Cacheable
  public static <X, N, A> NamedFunction<X, Integer> graphFiltersCount(
      @Param(value = "name", dS = "graph.filters.count") String name,
      @Param(value = "of", dNPM = "f.identity()") Function<X, Graph<N, A>> beforeF) {
    Function<Graph<N, A>, Integer> f = g -> {
      int count = 0;
      for (N node : g.nodes()) {
        if (node instanceof QueryMapper.FilterOperator) {
          count++;
        }
      }
      return count;
    };
    return NamedFunction.from(f, name).compose(beforeF);
  }

  @Cacheable
  public static <X, N, A> NamedFunction<X, Integer> graphUsedFieldsCount(
      @Param(value = "name", dS = "graph.used.fields.count") String name,
      @Param(value = "of", dNPM = "f.identity()") Function<X, Graph<N, A>> beforeF) {
    Function<Graph<N, A>, Integer> f = g -> {
      Set<String> fields = new HashSet<>();
      for (N node : g.nodes()) {
        String field = QueryMapper.fieldUsedBy(node);
        if (field != null) {
          fields.add(field);
        }
      }
      return fields.size();
    };
    return NamedFunction.from(f, name).compose(beforeF);
  }

  private static int operatorBit(Object node) {
    if (node instanceof QueryMapper.FilterOperator) {
      return 1 << 0;
    }
    if (node instanceof QueryMapper.MapDuplicate) {
      return 1 << 1;
    }
    if (node instanceof QueryMapper.MapNoise) {
      return 1 << 2;
    }
    if (node instanceof QueryMapper.MapRIR) {
      return 1 << 3;
    }
    if (node instanceof QueryMapper.MapAggregate) {
      return 1 << 4;
    }
    if (node instanceof QueryMapper.Fork || node instanceof QueryMapper.ConditionalFork) {
      return 1 << 5;
    }
    return 0;
  }

}
