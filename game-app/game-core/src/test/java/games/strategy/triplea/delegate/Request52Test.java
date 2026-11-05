package games.strategy.triplea.delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import games.strategy.engine.GameOverException;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.player.Player;
import games.strategy.triplea.delegate.air.BasedMap;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 52: leaving a game during an air stage. Once the game is over every call on a remote
 * player, hashCode included, throws GameOverException; no air stage thread may let it escape
 * (TripleA would show it as an error window).
 */
class Request52Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private final List<String> escaped = new ArrayList<>();
  private Thread.UncaughtExceptionHandler before;
  private GameData data;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = BasedMap.load();
    before = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (t, e) -> {
          synchronized (escaped) {
            escaped.add(t.getName() + ": " + e);
          }
        });
  }

  @AfterEach
  void tearDown() {
    Thread.setDefaultUncaughtExceptionHandler(before);
  }

  /** A remote player after Leave Game: everything it is asked, even hashCode, throws. */
  private static Player leftGame() {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> {
              throw new GameOverException("Game Over");
            });
  }

  @Test
  void leavingDuringTheAirStagesEndsQuietly() throws Exception {
    final Player gone = leftGame();
    final AirStageRunner runner =
        new AirStageRunner(data)
            .bridgeSetUp(
                bridge -> when(bridge.getRemotePlayer(any(GamePlayer.class))).thenReturn(gone));
    try {
      runner.run();
    } catch (final GameOverException e) {
      // the delegate's own thread: stock TripleA ends the game quietly there
    }
    // the helper threads that tell allies and waiting players are daemons: give them a moment
    Thread.sleep(300);
    synchronized (escaped) {
      assertThat(escaped.toString(), escaped, is(empty()));
    }
  }
}
