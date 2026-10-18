package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Properties;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.casualty.CasualtySelector;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 9 item 1 on the installed map: units keep their XML hit points through
 * the start of the game (stock "Two hit battleship" handling had cut battleships to 1), and a
 * battleship survives 1 and 4 hits and dies to 5. Skipped when the map is not installed.
 */
class MultiHitPointBattleTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer british;
  private final Map<String, Integer> xmlHitPoints = new HashMap<>();

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    british = data.getPlayerList().getPlayerId("British");
    for (final UnitType type : data.getUnitTypeList()) {
      xmlHitPoints.put(type.getName(), type.getUnitAttachment().getHitPoints());
    }
    // the game's own start-of-game setup
    final InitializationDelegate init = new InitializationDelegate();
    init.initialize("initDelegate", "Initializing Delegates");
    init.setDelegateBridgeAndPlayer(newDelegateBridge(british));
    init.start();
    init.end();
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  @Test
  void everyUnitKeepsItsXmlHitPointsAfterGameStart() {
    for (final UnitType type : data.getUnitTypeList()) {
      assertThat(
          type.getName(),
          type.getUnitAttachment().getHitPoints(),
          is(xmlHitPoints.get(type.getName())));
    }
    final Map<String, Integer> expected =
        Map.ofEntries(
            Map.entry("battleship", 5),
            Map.entry("yamatoBattleship", 8),
            Map.entry("cruiser", 2),
            Map.entry("carrier", 2),
            Map.entry("fleetCarrier", 4),
            Map.entry("tank", 2),
            Map.entry("heavyTank", 4),
            Map.entry("panther", 4),
            Map.entry("churchillTank", 4),
            Map.entry("pershingTank", 4),
            Map.entry("kingTiger", 6),
            Map.entry("typeXXI", 4),
            Map.entry("trench", 2),
            Map.entry("fighter", 2));
    expected.forEach(
        (name, hp) -> assertThat(name, type(name).getUnitAttachment().getHitPoints(), is(hp)));
  }

  private CasualtyDetails hit(final Unit target, final int hits) {
    final IDelegateBridge bridge = newDelegateBridge(british);
    final Territory sz = data.getMap().getTerritoryOrThrow("Sea Zone 54 Bay of Biscay - West");
    final DiceRoll dice = mock(DiceRoll.class);
    when(dice.getHits()).thenReturn(hits);
    return CasualtySelector.selectCasualties(
        british,
        List.of(target),
        CombatValueBuilder.mainCombatValue()
            .enemyUnits(List.of())
            .friendlyUnits(List.of(target))
            .side(BattleState.Side.DEFENSE)
            .gameSequence(data.getSequence())
            .supportAttachments(data.getUnitTypeList().getSupportRules())
            .lhtrHeavyBombers(Properties.getLhtrHeavyBombers(data.getProperties()))
            .gameDiceSides(data.getDiceSides())
            .territoryEffects(List.of())
            .build(),
        sz,
        bridge,
        "test",
        dice,
        UUID.randomUUID(),
        true,
        hits,
        true);
  }

  @Test
  void damageHealsFullyAtTheEndOfTheRoundOnly() {
    final Territory sz = data.getMap().getTerritoryOrThrow("Sea Zone 54 Bay of Biscay - West");
    final Unit battleship = type("battleship").create(1, british).get(0);
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(sz, List.of(battleship)));
    final IDelegateBridge bridge = newDelegateBridge(british);
    battleship.setHits(4);

    // only the last End Turn of the round repairs
    for (final String nation : List.of("german", "russian", "japanese", "british", "italian")) {
      MockDelegateBridge.advanceToStep(bridge, nation + "EndTurn");
      assertThat(nation, GameStepPropertiesHelper.isRepairUnits(data), is(false));
    }
    MockDelegateBridge.advanceToStep(bridge, "americanEndTurn");
    assertThat(GameStepPropertiesHelper.isRepairUnits(data), is(true));

    MoveDelegate.repairMultipleHitPointUnits(bridge, british);
    assertThat(battleship.getHits(), is(0));
  }

  @Test
  void battleshipSurvivesOneAndFourHitsAndDiesToFive() {
    final Unit battleship = type("battleship").create(1, british).get(0);
    assertThat(Unit.maxHitPoints(battleship), is(5));

    final CasualtyDetails one = hit(battleship, 1);
    assertThat(one.getKilled(), is(empty()));
    assertThat(one.getDamaged(), is(List.of(battleship)));

    final CasualtyDetails four = hit(battleship, 4);
    assertThat(four.getKilled(), is(empty()));
    assertThat(four.getDamaged().size(), is(4));

    final CasualtyDetails five = hit(battleship, 5);
    assertThat(five.getKilled(), is(List.of(battleship)));
  }
}
