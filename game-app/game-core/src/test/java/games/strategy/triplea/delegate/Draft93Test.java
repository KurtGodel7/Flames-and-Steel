package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.battle.BombardCap;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel requests 50 and 51 on the installed map (draft 93 or later): Speer's Panther
 * every other turn; Naval Gunfire Support allows 2 bombardment dice per unit unloaded; cut ships
 * fire at 5. Skipped when the map is not installed or has no Naval Gunfire Support.
 */
class Draft93Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    Assumptions.assumeTrue(
        data.getTechnologyFrontier().getAdvanceByName("NavalGunfireSupport") != null,
        "no Naval Gunfire Support");
  }

  @Test
  void speersPantherComesEveryOtherTurn() {
    assertThat(
        TechAbilityAttachment.get(
                data.getTechnologyFrontier().getAdvanceByName("SpeersIndustrialization"))
            .getApcFreeUnitsTurns(),
        is(2));
  }

  @Test
  void navalGunfireSupportAllowsTwoDicePerUnitUnloaded() {
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    assertThat(BombardCap.dicePerUnloadedUnit(japanese), is(1));
    TechTracker.addAdvance(
        japanese,
        newDelegateBridge(japanese),
        data.getTechnologyFrontier().getAdvanceByName("NavalGunfireSupport"));
    data.getTechTracker().clearCache();
    assertThat(BombardCap.dicePerUnloadedUnit(japanese), is(2));
  }

  @Test
  void cutShipsFireAtFive() {
    assertThat(BombardCap.applies(data.getProperties()), is(true));
    assertThat(BombardCap.reducedDieValue(data.getProperties()), is(5));
  }
}
