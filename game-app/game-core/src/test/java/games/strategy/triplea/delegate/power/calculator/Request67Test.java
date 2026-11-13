package games.strategy.triplea.delegate.power.calculator;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Die;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.battle.BombardCap;
import games.strategy.triplea.delegate.dice.calculator.RolledDice;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 67 on the installed map (draft 117): a ship's attack extra dice (request
 * 66) bombard too and count toward the request 51 cap. With Fleet Action and Improved Shells the
 * Yamato is 5 + 4 + 4 and a battleship 4 + 4 (two rolls). Skipped when the map is not installed.
 */
class Request67Test {
  private GameData data;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    japanese = data.getPlayerList().getPlayerId("Japanese");
  }

  private void grant(final String techName) {
    final TechAdvance tech =
        TechAdvance.getTechAdvances(data.getTechnologyFrontier()).stream()
            .filter(t -> t.getName().equals(techName))
            .findFirst()
            .orElseThrow();
    TechTracker.addAdvance(japanese, newDelegateBridge(japanese), tech);
    data.getTechTracker().clearCache();
  }

  private void grantShipTechs() {
    grant("FleetAction");
    grant("ImprovedShells");
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  private List<Unit> landed(final int count) {
    final List<Unit> units = type("infantry").create(count, japanese);
    units.forEach(u -> u.setWasAmphibious(true));
    return units;
  }

  private PowerStrengthAndRolls bombard(final List<Unit> landed, final List<Unit> ships) {
    return PowerStrengthAndRolls.build(
        ships,
        CombatValueBuilder.navalBombardmentCombatValue()
            .enemyUnits(List.of())
            .friendlyUnits(landed)
            .supportAttachments(data.getUnitTypeList().getSupportRules())
            .lhtrHeavyBombers(false)
            .gameDiceSides(data.getDiceSides())
            .territoryEffects(List.of())
            .diceCap(BombardCap.diceCap(landed, japanese))
            .reducedDieValue(BombardCap.reducedDieValue(data.getProperties()))
            .build());
  }

  /** The values the ship's dice roll at, in the order they are rolled, e.g. "5, 4, 4". */
  private static String dice(final PowerStrengthAndRolls power, final Unit ship) {
    final UnitPowerStrengthAndRolls u = power.getTotalStrengthAndTotalRollsByUnit().get(ship);
    // all ones roll: every die hits, so each Die shows the value it was rolled at
    final int[] rolls = new int[Math.max(0, u.getRolls())];
    final List<Die> dice = RolledDice.getDiceHits(rolls, List.of(u));
    return dice.isEmpty()
        ? "none"
        : dice.stream().map(d -> String.valueOf(d.getRolledAt())).collect(Collectors.joining(", "));
  }

  private String yamato(final int landed) {
    final List<Unit> ship = type("yamatoBattleship").create(1, japanese);
    return dice(bombard(landed(landed), ship), ship.get(0));
  }

  @Test
  void theMapHasTheReducedDieValueFive() {
    assertThat(BombardCap.reducedDieValue(data.getProperties()), is(5));
  }

  @Test
  void yamatoWithImprovedShellsAndThreeUnitsLandedRolls5And4And4() {
    grantShipTechs();
    assertThat(yamato(3), is("5, 4, 4"));
    assertThat(yamato(4), is("5, 4, 4"));
  }

  @Test
  void cutShortTheKeptDiceAreRaisedToFive() {
    grantShipTechs();
    assertThat(yamato(2), is("5, 5"));
    assertThat(yamato(1), is("5"));
    assertThat(yamato(0), is("none"));
  }

  @Test
  void navalGunfireSupportLetsTwoUnitsCarryAllThreeDice() {
    grantShipTechs();
    grant("NavalGunfireSupport");
    assertThat(BombardCap.dicePerUnloadedUnit(japanese), is(2));
    assertThat(yamato(2), is("5, 4, 4"));
    assertThat(yamato(1), is("5, 5"));
  }

  @Test
  void fleetActionAloneGivesTheYamatoOneExtraDie() {
    grant("FleetAction");
    assertThat(yamato(2), is("5, 4"));
    assertThat(yamato(1), is("5"));
  }

  @Test
  void theYamatoFiresAllItsDiceBeforeABattleshipGetsTheRest() {
    grantShipTechs();
    final List<Unit> ships = new ArrayList<>(type("battleship").create(1, japanese));
    ships.addAll(type("yamatoBattleship").create(1, japanese));
    final PowerStrengthAndRolls p = bombard(landed(4), ships);
    assertThat(dice(p, ships.get(1)), is("5, 4, 4"));
    assertThat(dice(p, ships.get(0)), is("5"));
    assertThat(p.calculateTotalRolls(), is(4));
  }

  @Test
  void aBattleshipWithoutExtraDiceIsUnchanged() {
    grantShipTechs();
    final List<Unit> ship = type("battleship").create(1, japanese);
    assertThat(dice(bombard(landed(2), ship), ship.get(0)), is("4, 4"));
    assertThat(dice(bombard(landed(1), ship), ship.get(0)), is("5"));
    assertThat(dice(bombard(landed(0), ship), ship.get(0)), is("none"));
  }

  @Test
  void lowLuckPowerAndTheCaptionCountTheExtraDice() {
    grantShipTechs();
    final List<Unit> ship = type("yamatoBattleship").create(1, japanese);
    final PowerStrengthAndRolls full = bombard(landed(3), ship);
    assertThat(full.calculateTotalPower(), is(13));
    assertThat(full.calculateTotalRolls(), is(3));
    assertThat(bombard(landed(2), ship).calculateTotalPower(), is(10));
    assertThat(
        BombardCap.caption(full.calculateTotalRolls(), 3, 1),
        is("Bombardment: 3 dice (3 units unloaded)"));
  }

  @Test
  void withoutACapAllDiceStillBombard() {
    grantShipTechs();
    final List<Unit> ship = type("yamatoBattleship").create(1, japanese);
    final PowerStrengthAndRolls p =
        PowerStrengthAndRolls.build(
            ship,
            CombatValueBuilder.navalBombardmentCombatValue()
                .enemyUnits(List.of())
                .friendlyUnits(List.of())
                .supportAttachments(data.getUnitTypeList().getSupportRules())
                .lhtrHeavyBombers(false)
                .gameDiceSides(data.getDiceSides())
                .territoryEffects(List.of())
                .build());
    assertThat(dice(p, ship.get(0)), is("5, 4, 4"));
    assertThat(
        p.getActiveUnits().stream().map(UnitPowerStrengthAndRolls::getRolls).toList(), contains(3));
  }
}
