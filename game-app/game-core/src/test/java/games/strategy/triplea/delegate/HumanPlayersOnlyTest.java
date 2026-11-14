package games.strategy.triplea.delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Request 68 part B: Flames and Steel offers only human players; other games are unchanged. */
class HumanPlayersOnlyTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  @Test
  void onForTheInstalledMap() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    final GameData data = games.strategy.triplea.delegate.air.BasedMap.load();
    assertThat(HumanPlayersOnly.applies(data), is(true));
  }

  @Test
  void offForOtherGamesAndNoGame() {
    assertThat(HumanPlayersOnly.applies(null), is(false));
    final GameData plain = new GameData();
    assertThat(HumanPlayersOnly.applies(plain), is(false));
    plain.getProperties().set(HumanPlayersOnly.PROPERTY, true);
    assertThat(HumanPlayersOnly.applies(plain), is(true));
  }
}
