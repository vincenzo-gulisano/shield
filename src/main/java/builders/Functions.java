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
   * bit 6 = MapTimestampPairwiseSwap
   * bit 7 = MapTimestampGroupShuffle
   *
   * Source, Sink, and Union are structural nodes and do not contribute to the bitmap.
   *
   * Returned values are bitwise OR combinations of the supported operator-type bits.
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
  public static <X, N, A> NamedFunction<X, Integer> graphFilterDuplicateCategory(
      @Param(value = "name", dS = "graph.filter.duplicate.category") String name,
      @Param(value = "of", dNPM = "f.identity()") Function<X, Graph<N, A>> beforeF) {
    Function<Graph<N, A>, Integer> f = g -> {
      boolean hasFilter = false;
      boolean hasDuplicate = false;
      for (N node : g.nodes()) {
        hasFilter |= node instanceof QueryMapper.FilterOperator;
        hasDuplicate |= node instanceof QueryMapper.MapDuplicate;
      }
      return (hasFilter ? 1 : 0) + (hasDuplicate ? 2 : 0);
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
  public static <X, N, A> NamedFunction<X, Integer> graphOperatorsCountExceptSourceSink(
      @Param(value = "name", dS = "graph.operators.count.except.source.sink") String name,
      @Param(value = "of", dNPM = "f.identity()") Function<X, Graph<N, A>> beforeF) {
    Function<Graph<N, A>, Integer> f = g -> {
      int count = 0;
      for (N node : g.nodes()) {
        if (!(node instanceof QueryMapper.Source) && !(node instanceof QueryMapper.Sink)) {
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
    if (node instanceof QueryMapper.MapTimestampPairwiseSwap) {
      return 1 << 6;
    }
    if (node instanceof QueryMapper.MapTimestampGroupShuffle) {
      return 1 << 7;
    }
    return 0;
  }

}
