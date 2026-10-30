package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.apc.Apcs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 50 on the installed map (draft 90 or later): Germany may own five SPCs
 * with Advanced 5, Britain two; Speer's Industrialization gives each German SPC a free Panther
 * every other turn. Skipped when the map is not installed or has no Advanced 5.
 */
class Draft90Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer germans;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    Assumptions.assumeTrue(
        data.getTechnologyFrontier().getAdvanceByName("Advanced5") != null, "no Advanced 5");
    germans = data.getPlayerList().getPlayerId("Germans");
  }

  private void grant(final GamePlayer player, final String tech) {
    TechTracker.addAdvance(
        player, newDelegateBridge(player), data.getTechnologyFrontier().getAdvanceByName(tech));
    data.getTechTracker().clearCache();
  }

  @Test
  void germanyMayOwnFiveSpcsAndBritainTwo() {
    for (final String tech : List.of("Advanced1", "Advanced2", "Advanced3", "Advanced4")) {
      grant(germans, tech);
    }
    assertThat(Apcs.getLimit(germans), is(4));
    grant(germans, "Advanced5");
    assertThat(Apcs.getLimit(germans), is(5));

    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    grant(british, "Advanced1");
    grant(british, "Advanced2");
    assertThat(Apcs.getLimit(british), is(2));
  }

  @Test
  void speersPantherComesEveryOtherTurnAtEachSpc() throws Exception {
    final TechAbilityAttachment speers =
        TechAbilityAttachment.get(
            data.getTechnologyFrontier().getAdvanceByName("SpeersIndustrialization"));
    // until the XML carries it (cloud session, after this build)
    speers.getPropertyOrThrow("apcFreeUnitsTurns").setValue(2);
    grant(germans, "SpeersIndustrialization");
    grant(germans, "Panther");
    final UnitType apc = data.getUnitTypeList().getUnitTypeOrThrow("apc");
    final List<Territory> spcs =
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater() && t.isOwnedBy(germans))
            .filter(t -> t.getUnitCollection().stream().noneMatch(Apcs::isApc))
            .limit(2)
            .toList();
    for (final Territory t : spcs) {
      final List<Unit> built = apc.create(1, germans);
      data.performChange(ChangeFactory.addUnits(t, built));
      data.performChange(OriginalOwnerTracker.addOriginalOwnerChange(built, germans));
    }
    final int ownSpcs = Apcs.getOwnApcs(germans, data.getMap()).size();
    assertThat(ownSpcs >= 2, is(true));

    final IDelegateBridge bridge = newDelegateBridge(germans);
    final UnitType panther = data.getUnitTypeList().getUnitTypeOrThrow("panther");
    final List<Integer> gained = new ArrayList<>();
    for (int turn = 0; turn < 4; turn++) {
      final int before = count(panther);
      Apcs.produce(bridge, germans);
      gained.add(count(panther) - before);
    }

    assertThat(gained, contains(ownSpcs, 0, ownSpcs, 0));
  }

  private int count(final UnitType type) {
    return data.getMap().getTerritories().stream()
        .mapToInt(
            t ->
                t.getUnitCollection()
                    .countMatches(u -> u.getType().equals(type) && u.isOwnedBy(germans)))
        .sum();
  }
}
