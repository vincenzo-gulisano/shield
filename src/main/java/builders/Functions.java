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
import java.util.function.Function;

@Discoverable(prefixTemplate = "anonym.function|f")
public class Functions {

  private Functions() {
  }

  @Cacheable
  public static <X, N, A> NamedFunction<X, Integer> graphBu(
      @Param(value = "name", dS = "graph.bu") String name,
      @Param(value = "of", dNPM = "f.identity()") Function<X, Graph<N,A>> beforeF
  ) {
    Function<Graph<N,A>, Integer> f = g -> g.size();
    return NamedFunction.from(f, name).compose(beforeF);
  }


}