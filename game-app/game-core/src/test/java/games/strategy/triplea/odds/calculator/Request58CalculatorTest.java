package games.strategy.triplea.odds.calculator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.air.BasedMap;
import games.strategy.triplea.delegate.battle.AmphibiousLanding;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 58 on the installed map: the battle calculator applies request 55 per unit (units marked
 * as landing from the sea, as the calculator's "from sea" counts mark them) and request 51's
 * bombardment cap. The calculator rolls real dice, so outcomes are compared with margins.
 */
class Request58CalculatorTest extends AbstractClientSettingTestCase {
  private static final int RUNS = 3000;

  private GameData data;
  private GamePlayer italians;
  private GamePlayer british;
  private Territory gibraltar;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    italians = BasedMap.p(data, "Italians");
    british = BasedMap.p(data, "British");
    gibraltar = BasedMap.t(data, "Gibraltar");
  }

  private List<Unit> units(
      final String type, final int n, final GamePlayer owner, final boolean fromSea) {
    final List<Unit> units = data.getUnitTypeList().getUnitTypeOrThrow(type).createTemp(n, owner);
    units.forEach(u -> u.setWasAmphibious(fromSea));
    return units;
  }

  private double attackerWins(
      final List<Unit> attacking, final List<Unit> defending, final List<Unit> bombarding) {
    final BattleCalculator calculator = new BattleCalculator(data);
    return calculator
        .calculate(
            italians, british, gibraltar, attacking, defending, bombarding, List.of(), false, RUNS)
        .getAttackerWinPercent();
  }

  /** Average attacking units left: bombardment that kills defenders early saves attackers. */
  private double attackersLeft(
      final List<Unit> attacking, final List<Unit> defending, final List<Unit> bombarding) {
    final BattleCalculator calculator = new BattleCalculator(data);
    return calculator
        .calculate(
            italians, british, gibraltar, attacking, defending, bombarding, List.of(), false, RUNS)
        .getAverageAttackingUnitsLeft();
  }

  private List<Unit> sixDefenders() {
    return units("infantry", 6, british, false);
  }

  /** 5 by land + 4 from sea lies between all 9 by land and all 9 from sea. */
  @Test
  void mixedLandingLiesBetweenAllByLandAndAllFromSea() {
    final List<Unit> allLand = units("infantry", 9, italians, false);
    final List<Unit> mixed = units("infantry", 5, italians, false);
    mixed.addAll(units("infantry", 4, italians, true));
    final List<Unit> allSea = units("infantry", 9, italians, true);

    final double byLand = attackerWins(allLand, sixDefenders(), List.of());
    final double split = attackerWins(mixed, sixDefenders(), List.of());
    final double bySea = attackerWins(allSea, sixDefenders(), List.of());

    assertThat(byLand - split, greaterThan(0.03));
    assertThat(split - bySea, greaterThan(0.03));
  }

  /** Exempt types fire in round 1 when landing: as good as attacking over land. */
  @Test
  void exemptLandingUnitsFireInRoundOne() {
    data.getProperties().set(AmphibiousLanding.EXEMPT_PROPERTY, "infantry");
    final double byLand =
        attackerWins(units("infantry", 9, italians, false), sixDefenders(), List.of());
    final double bySea =
        attackerWins(units("infantry", 9, italians, true), sixDefenders(), List.of());
    assertThat(bySea, closeTo(byLand, 0.05));
  }

  /**
   * Italy's Gibraltar landing: 6 infantry + 3 field guns from sea, 2 battleships bombarding,
   * against infantry, heavy gun and 2 trenches. The ships help only when units land from the sea
   * (request 51: dice = units landing x N).
   */
  @Test
  void bombardmentNeedsUnitsLandingFromTheSea() {
    final List<Unit> defenders = new ArrayList<>(units("infantry", 1, british, false));
    defenders.addAll(units("heavyGun", 1, british, false));
    defenders.addAll(units("trench", 2, british, false));

    final List<Unit> landing = units("infantry", 6, italians, true);
    landing.addAll(units("fieldGun", 3, italians, true));
    final List<Unit> overland = units("infantry", 6, italians, false);
    overland.addAll(units("fieldGun", 3, italians, false));

    final double landingNoShips = attackersLeft(landing, defenders, List.of());
    final double landingShips =
        attackersLeft(landing, defenders, units("battleship", 2, italians, false));
    assertThat(landingShips - landingNoShips, greaterThan(0.2));

    // with nobody landing from the sea the ships roll nothing
    final double overlandNoShips = attackersLeft(overland, defenders, List.of());
    final double overlandShips =
        attackersLeft(overland, defenders, units("battleship", 2, italians, false));
    assertThat(overlandShips, closeTo(overlandNoShips, 0.1));
  }
}
