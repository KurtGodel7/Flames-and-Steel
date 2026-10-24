package games.strategy.triplea.ui.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Request 35 part 3: one computer's nations in a stage go one at a time, in turn order. */
class AirStageTurnstileTest {

  @Test
  void threeNationsOnOneComputerGoInTurnOrder() throws Exception {
    final GameData data = new GameData();
    final GamePlayer germans = new GamePlayer("Germans", data);
    final GamePlayer russians = new GamePlayer("Russians", data);
    final GamePlayer japanese = new GamePlayer("Japanese", data);
    final GamePlayer italians = new GamePlayer("Italians", data);
    // turn order: Germans, Russians, Japanese, Italians; this computer plays the Axis
    final List<GamePlayer> order = List.of(germans, russians, japanese, italians);
    final Set<GamePlayer> local = Set.of(germans, japanese, italians);
    final AirStageTurnstile turnstile = new AirStageTurnstile();
    final List<String> shown = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch allDone = new CountDownLatch(3);
    // the questions arrive in the wrong order, all at once
    for (final GamePlayer nation : List.of(italians, japanese, germans)) {
      new Thread(
              () -> {
                try {
                  turnstile.await("BOMBERS round 1", order, nation, local::contains);
                  shown.add(nation.getName());
                  Thread.sleep(30); // the screen is open
                  turnstile.finish(nation);
                  allDone.countDown();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              })
          .start();
    }
    allDone.await();
    assertThat(shown, is(List.of("Germans", "Japanese", "Italians")));
    assertThat(turnstile.isBusy(), is(false));
    assertThat(turnstile.localLeft(order, local::contains).isEmpty(), is(true));
  }

  @Test
  void aNewStageStartsAfresh() throws Exception {
    final GameData data = new GameData();
    final GamePlayer germans = new GamePlayer("Germans", data);
    final AirStageTurnstile turnstile = new AirStageTurnstile();
    turnstile.await("BOMBERS round 1", List.of(germans), germans, p -> true);
    turnstile.finish(germans);
    turnstile.await("FIGHTERS round 1", List.of(germans), germans, p -> true);
    assertThat(turnstile.isBusy(), is(true));
    turnstile.finish(germans);
  }
}
