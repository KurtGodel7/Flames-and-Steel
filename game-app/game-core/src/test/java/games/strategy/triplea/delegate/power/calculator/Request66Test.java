package games.strategy.triplea.delegate.power.calculator;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Die;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechDescriptions;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.dice.calculator.RolledDice;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 66 on the installed map: extra dice at their own value. Sturmtruppen are
 * set to draft 109's 3/4 and Assault Rifle to the request's target options (attack +1, defense -1,
 * an extra defense die at 2), in memory. Skipped when the map is not installed.
 */
class Request66Test {
  private GameData data;
  private GamePlayer germans;
  private GamePlayer russians;
  private TechAdvance assaultRifle;

  @BeforeEach
  void setUp() throws Exception {
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    germans = data.getPlayerList().getPlayerId("Germans");
    russians = data.getPlayerList().getPlayerId("Russians");
    final UnitAttachment sturm = type("sturmtruppen").getUnitAttachment();
    sturm.setAttack(3);
    sturm.setDefense(4);
    assaultRifle =
        TechAdvance.getTechAdvances(data.getTechnologyFrontier()).stream()
            .filter(t -> t.getName().equals("AssaultRifle"))
            .findFirst()
            .orElseThrow();
    final TechAbilityAttachment rifle = TechAbilityAttachment.get(assaultRifle);
    set(rifle, "attackBonus", "1:sturmtruppen");
    set(rifle, "defenseBonus", "-1:sturmtruppen");
    set(rifle, "defenseExtraDie", "2:sturmtruppen");
    data.getTechTracker().clearCache();
  }

  private static void set(final TechAbilityAttachment a, final String option, final String value)
      throws Exception {
    a.getPropertyOrThrow(option).resetValue();
    a.getPropertyOrThrow(option).setValue(value);
  }

  private void grantAssaultRifle() {
    TechTracker.addAdvance(germans, newDelegateBridge(germans), assaultRifle);
    data.getTechTracker().clearCache();
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  private List<Unit> units(final String name, final int count, final GamePlayer owner) {
    return type(name).create(count, owner);
  }

  private PowerStrengthAndRolls power(final List<Unit> side, final BattleState.Side role) {
    final CombatValue value =
        CombatValueBuilder.mainCombatValue()
            .enemyUnits(units("infantry", 1, russians))
            .friendlyUnits(side)
            .side(role)
            .gameSequence(data.getSequence())
            .supportAttachments(data.getUnitTypeList().getSupportRules())
            .lhtrHeavyBombers(false)
            .gameDiceSides(data.getDiceSides())
            .territoryEffects(List.of())
            .build();
    return PowerStrengthAndRolls.build(side, value);
  }

  /** The unit's dice as "main x rolls + extra...", e.g. "3 + 2" or "4". */
  private static String dice(final PowerStrengthAndRolls power, final Unit unit) {
    final UnitPowerStrengthAndRolls u = power.getTotalStrengthAndTotalRollsByUnit().get(unit);
    final StringBuilder text =
        new StringBuilder((u.getMainRolls() > 1 ? u.getMainRolls() + "x" : "") + u.getStrength());
    u.getExtraDice().forEach(d -> text.append(" + ").append(d));
    return text.toString();
  }

  private String sturmDice(final BattleState.Side role, final String supporter) {
    final List<Unit> side = new ArrayList<>(units("sturmtruppen", 1, germans));
    if (supporter != null) {
      side.addAll(units(supporter, 1, germans));
    }
    return dice(power(side, role), side.get(0));
  }

  @Test
  void withoutAssaultRifleSturmtruppenAre3And4AndSupportedRaiseTheirOneDie() {
    assertThat(sturmDice(BattleState.Side.OFFENSE, null), is("3"));
    assertThat(sturmDice(BattleState.Side.DEFENSE, null), is("4"));
    assertThat(sturmDice(BattleState.Side.OFFENSE, "fieldGun"), is("4"));
    assertThat(sturmDice(BattleState.Side.DEFENSE, "heavyGun"), is("5"));
  }

  @Test
  void withAssaultRifleTheyAttack4AndDefend3Plus2() {
    grantAssaultRifle();
    assertThat(sturmDice(BattleState.Side.OFFENSE, null), is("4"));
    assertThat(sturmDice(BattleState.Side.DEFENSE, null), is("3 + 2"));
  }

  @Test
  void supportGoesToTheExtraDieOnDefenseAndToTheMainDieOnAttack() {
    grantAssaultRifle();
    assertThat(sturmDice(BattleState.Side.DEFENSE, "heavyGun"), is("3 + 3"));
    assertThat(sturmDice(BattleState.Side.OFFENSE, "fieldGun"), is("5"));
  }

  @Test
  void lowLuckPowerCountsTheExtraDie() {
    grantAssaultRifle();
    final List<Unit> alone = units("sturmtruppen", 1, germans);
    assertThat(power(alone, BattleState.Side.DEFENSE).calculateTotalPower(), is(5));
    assertThat(power(alone, BattleState.Side.DEFENSE).calculateTotalRolls(), is(2));
    final List<Unit> supported = new ArrayList<>(units("sturmtruppen", 1, germans));
    supported.addAll(units("heavyGun", 1, germans));
    final PowerStrengthAndRolls p = power(supported, BattleState.Side.DEFENSE);
    assertThat(p.getPower(supported.get(0)), is(6));
  }

  @Test
  void kingTigersAreUnchangedWithOrWithoutAPanther() {
    grantAssaultRifle();
    final List<Unit> tiger = units("kingTiger", 1, germans);
    final String alone = dice(power(tiger, BattleState.Side.DEFENSE), tiger.get(0));
    final List<Unit> withPanther = new ArrayList<>(tiger);
    withPanther.addAll(units("panther", 1, germans));
    assertThat(dice(power(withPanther, BattleState.Side.DEFENSE), tiger.get(0)), is(alone));
    final UnitAttachment ua = type("kingTiger").getUnitAttachment();
    assertThat(alone, is(ua.getDefenseRolls(germans) + "x" + Math.min(6, ua.getDefense(germans))));
  }

  @Test
  void rolledDiceHitBelowEachDiesOwnValue() {
    grantAssaultRifle();
    final List<Unit> side = new ArrayList<>(units("sturmtruppen", 1, germans));
    side.addAll(units("heavyGun", 1, germans));
    final PowerStrengthAndRolls p = power(side.subList(0, 1), BattleState.Side.DEFENSE);
    // rolled 2 and 2 (zero based): the main die at 3 hits, the extra die at 2 misses
    final List<Die> dice =
        RolledDice.getDiceHits(new int[] {2, 2}, List.of(p.getActiveUnits().get(0)));
    assertThat(dice.size(), is(2));
    assertThat(dice.get(0).getType(), is(Die.DieType.HIT));
    assertThat(dice.get(1).getType(), is(Die.DieType.MISS));
    assertThat(dice.get(1).getRolledAt(), is(2));
  }

  @Test
  void anExtraAttackDieOnInfantryRollsAndIsSupportedTheSameWay() throws Exception {
    final TechAbilityAttachment rifle = TechAbilityAttachment.get(assaultRifle);
    set(rifle, "attackExtraDie", "2:infantry");
    grantAssaultRifle();
    final List<Unit> side = new ArrayList<>(units("infantry", 1, germans));
    final int main = type("infantry").getUnitAttachment().getAttack(germans);
    assertThat(dice(power(side, BattleState.Side.OFFENSE), side.get(0)), is(main + " + 2"));
    side.addAll(units("fieldGun", 1, germans));
    assertThat(dice(power(side, BattleState.Side.OFFENSE), side.get(0)), is(main + " + 3"));
  }

  @Test
  void aUnitsOwnExtraDieWorksWithoutATech() throws Exception {
    final UnitAttachment ua = type("infantry").getUnitAttachment();
    ua.getPropertyOrThrow("defenseExtraDie").setValue("1");
    data.getTechTracker().clearCache();
    final List<Unit> side = units("infantry", 1, russians);
    final int main = ua.getDefense(russians);
    assertThat(dice(power(side, BattleState.Side.DEFENSE), side.get(0)), is(main + " + 1"));
    assertThat(ua.defenseText(russians), is(main + " + 1"));
  }

  @Test
  void tooltipAndTechTextShowTheExtraDie() {
    grantAssaultRifle();
    final UnitAttachment ua = type("sturmtruppen").getUnitAttachment();
    assertThat(ua.defenseText(germans), is("3 + 2"));
    assertThat(ua.attackText(germans), is("4"));
    assertThat(ua.toStringShortAndOnlyImportantDifferences(germans), containsString("4 | 3 + 2"));
    assertThat(TechDescriptions.describe(assaultRifle), containsString("extra defense die at 2"));
  }

  /** Lots of rolled battles average out to the battle calculator's expected hits (power / 6). */
  @Test
  void rolledHitsAverageToThePowerTheCalculatorUses() {
    grantAssaultRifle();
    final List<Unit> side = new ArrayList<>(units("sturmtruppen", 3, germans));
    side.addAll(units("heavyGun", 1, germans));
    final PowerStrengthAndRolls p = power(side, BattleState.Side.DEFENSE);
    final Random random = new Random(66);
    final int battles = 200_000;
    long hits = 0;
    for (int i = 0; i < battles; i++) {
      final int[] rolls = new int[p.calculateTotalRolls()];
      for (int r = 0; r < rolls.length; r++) {
        rolls[r] = random.nextInt(6);
      }
      hits +=
          RolledDice.getDiceHits(rolls, p.getActiveUnits()).stream()
              .filter(d -> d.getType() == Die.DieType.HIT)
              .count();
    }
    // 3 sturmtruppen 3+2, one supported to 3+3, heavy gun's own defense
    assertThat((double) hits / battles, closeTo(p.calculateTotalPower() / 6.0, 0.01));
  }
}
