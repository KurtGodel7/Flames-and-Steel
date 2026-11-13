package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import games.strategy.triplea.delegate.power.calculator.PowerStrengthAndRolls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 21 on the installed map (draft 76 or later): Night Action replaces Night
 * Combat. +1 for Japanese cruisers; one more die for Yamato battleships, adding up with Fleet
 * Action and Improved Shells, as the battle window counts them. Skipped when the map is not
 * installed or has no Night Action.
 */
class Draft76Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer japanese;
  private GamePlayer americans;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    Assumptions.assumeTrue(
        data.getTechnologyFrontier().getAdvanceByName("NightAction") != null, "no Night Action");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    americans = data.getPlayerList().getPlayerId("Americans");
  }

  private void grant(final String tech) {
    TechTracker.addAdvance(
        japanese, newDelegateBridge(japanese), data.getTechnologyFrontier().getAdvanceByName(tech));
    data.getTechTracker().clearCache();
  }

  private Unit unit(final String type, final GamePlayer owner) {
    return data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, owner).get(0);
  }

  /** {strength, rolls} as the battle window works them out for one side of a sea battle. */
  private int[] powerAndRolls(final Unit unit, final BattleState.Side side) {
    final List<Unit> friendly = new ArrayList<>(List.of(unit));
    final List<Unit> enemies = List.of(unit("battleship", americans));
    final var combatValue =
        CombatValueBuilder.mainCombatValue()
            .enemyUnits(enemies)
            .friendlyUnits(friendly)
            .side(side)
            .gameSequence(data.getSequence())
            .supportAttachments(data.getUnitTypeList().getSupportRules())
            .lhtrHeavyBombers(false)
            .gameDiceSides(data.getDiceSides())
            .territoryEffects(List.of())
            .build();
    final PowerStrengthAndRolls p = PowerStrengthAndRolls.build(friendly, combatValue);
    return new int[] {p.getStrength(unit), p.getRolls(unit)};
  }

  @Test
  void nightCombatIsGone() {
    assertThat(data.getTechnologyFrontier().getAdvanceByName("NightCombat") == null, is(true));
  }

  // Draft 114: Night Action no longer gives cruisers +1 nor the Yamato an extra die (it swaps the
  // Yamato's support by trigger instead). Draft 117: the Yamato's Fleet Action and Improved Shells
  // dice are request 66 extra dice at 4.
  @Test
  void nightActionAloneChangesNoCruiserOrYamatoDice() {
    final Unit cruiser = unit("cruiser", japanese);
    final Unit yamato = unit("yamatoBattleship", japanese);
    final int[] cruiserBefore = powerAndRolls(cruiser, BattleState.Side.OFFENSE);
    final int[] yamatoBefore = powerAndRolls(yamato, BattleState.Side.OFFENSE);
    grant("NightAction");
    assertThat(powerAndRolls(cruiser, BattleState.Side.OFFENSE), is(cruiserBefore));
    assertThat(powerAndRolls(yamato, BattleState.Side.OFFENSE), is(yamatoBefore));
  }

  @Test
  void yamatoDiceAddUpWithFleetActionAndImprovedShells() {
    final Unit yamato = unit("yamatoBattleship", japanese);
    assertThat(powerAndRolls(yamato, BattleState.Side.OFFENSE)[1], is(1));
    grant("FleetAction");
    grant("ImprovedShells");
    final int[] attack = powerAndRolls(yamato, BattleState.Side.OFFENSE);
    final int[] defense = powerAndRolls(yamato, BattleState.Side.DEFENSE);
    // main die at 5 plus two extra dice at 4
    assertThat(attack[1], is(3));
    assertThat(defense[1], is(3));
    assertThat(attack[0], is(5));
    assertThat(defense[0], is(5));
  }
}
