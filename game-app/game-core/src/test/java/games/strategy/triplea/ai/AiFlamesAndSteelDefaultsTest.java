package games.strategy.triplea.ai;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.apc.Apcs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Request 69 on the installed map: the AI's SPC default (no AI-only game bought an SPC, so this is
 * where the default is exercised). A German SPC with the Panther tech builds Panthers; without any
 * SPC tech the AI chooses nothing, which is legal.
 */
class AiFlamesAndSteelDefaultsTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  @Test
  void spcBuildsTheFirstUnitItMayBuild() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    final GameData data = games.strategy.triplea.delegate.air.BasedMap.load();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Territory berlin = data.getMap().getTerritoryOrThrow("Berlin");
    final List<Unit> spc = data.getUnitTypeList().getUnitTypeOrThrow("apc").create(1, germans);
    data.performChange(ChangeFactory.addUnits(berlin, spc));
    data.performChange(OriginalOwnerTracker.addOriginalOwnerChange(spc, germans));

    // no SPC tech yet: nothing to build, nothing chosen
    assertThat(AbstractAi.defaultApcChoices(germans).isEmpty(), is(true));

    TechTracker.addAdvance(
        germans,
        newDelegateBridge(germans),
        data.getTechnologyFrontier().getAdvanceByName("Panther"));
    data.getTechTracker().clearCache();
    final Map<Unit, UnitType> choices = AbstractAi.defaultApcChoices(germans);
    final UnitType panther = data.getUnitTypeList().getUnitTypeOrThrow("panther");
    assertThat(choices, is(Map.of(spc.get(0), panther)));
    assertThat(Apcs.validate(germans, choices, data.getMap()), is(Optional.empty()));

    Apcs.setProduction(newDelegateBridge(germans), germans, choices);
    assertThat(Apcs.getProduction(spc.get(0), germans), is(Optional.of(panther)));
    // once set, the AI leaves it alone
    assertThat(AbstractAi.defaultApcChoices(germans).isEmpty(), is(true));
  }
}
