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

import io.github.ericmedvet.jnb.core.Discoverable;
import io.github.ericmedvet.jnb.core.Param;
import io.github.ericmedvet.jnb.datastructure.Listener;
import io.github.ericmedvet.jnb.datastructure.ListenerFactory;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import query.LiebreContext;

@Discoverable(prefixTemplate = "anonym.listener|list|l")
public class Listeners {

  private Listeners() {
  }

  public static <E, K> Function<Executor, ListenerFactory<E, K>> liebreTerminator(
      @Param(value = "name", dS = "liebre.terminator") String name
      ) {
    final Logger logger = LoggerFactory.getLogger(Listeners.class);
    return executor -> ListenerFactory.from(
        name,
        k -> Listener.deaf(),
        () -> {
          logger.info("Closing Liebre");
          LiebreContext.interruptTerminator();
        }
    );
  }


}