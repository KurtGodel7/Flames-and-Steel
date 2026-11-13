package games.strategy.triplea.delegate.power.calculator;

import games.strategy.engine.data.Unit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import org.triplea.java.collections.CollectionUtils;
import org.triplea.java.collections.IntegerMap;

/**
 * Computes the total power, total strength, and total roll for a collection of units
 *
 * <p>The strength is either attack or defense plus any support. The rolls is either attackRolls or
 * defenseRolls plus any support. The power is the strength * rolls unless either LHTR Heavy Bombers
 * or chooseBestRoll is active.
 */
@Value
@Getter(AccessLevel.NONE)
public class PowerStrengthAndRolls implements TotalPowerAndTotalRolls {

  CombatValue calculator;

  @Getter(onMethod_ = @Override)
  int diceSides;

  @Getter(AccessLevel.PUBLIC)
  Map<Unit, UnitPowerStrengthAndRolls> totalStrengthAndTotalRollsByUnit = new HashMap<>();

  List<UnitPowerStrengthAndRolls> sortedStrengthAndRolls = new ArrayList<>();

  @Getter(AccessLevel.PUBLIC)
  Map<Unit, IntegerMap<Unit>> unitSupportPowerMap = new HashMap<>();

  @Getter(AccessLevel.PUBLIC)
  Map<Unit, IntegerMap<Unit>> unitSupportRollsMap = new HashMap<>();

  private PowerStrengthAndRolls(final Collection<Unit> units, final CombatValue calculator) {
    this.calculator = calculator;
    this.diceSides = units.isEmpty() ? 0 : calculator.getDiceSides(CollectionUtils.getAny(units));
    addUnits(units);
  }

  /**
   * Builds a PowerStrengthAndRolls that calculates the power, strength, and roll for each unit
   *
   * @param unitsGettingPowerFor should be sorted from strongest to weakest, before the method is
   *     called, for the actual battle.
   */
  public static PowerStrengthAndRolls buildWithPreSortedUnits(
      final Collection<Unit> unitsGettingPowerFor, final CombatValue calculator) {

    if (unitsGettingPowerFor == null || unitsGettingPowerFor.isEmpty()) {
      return new PowerStrengthAndRolls(List.of(), calculator);
    }

    return new PowerStrengthAndRolls(unitsGettingPowerFor, calculator);
  }

  /**
   * Builds a PowerStrengthAndRolls that calculates the power, strength, and roll for each unit
   *
   * @param unitsGettingPowerFor does not need to be sorted
   * @param calculator calculates the value of offense or defense
   */
  public static PowerStrengthAndRolls build(
      final Collection<Unit> unitsGettingPowerFor, final CombatValue calculator) {

    if (unitsGettingPowerFor == null || unitsGettingPowerFor.isEmpty()) {
      return new PowerStrengthAndRolls(List.of(), calculator);
    }

    // First, sort units strongest to weakest without support so that later, the support is given
    // to the best units first
    return new PowerStrengthAndRolls(
        unitsGettingPowerFor.stream()
            .sorted(calculator.unitComparator())
            .collect(Collectors.toList()),
        calculator);
  }

  private void addUnits(final Collection<Unit> units) {
    final StrengthCalculator strengthCalculator = calculator.getStrength();
    final RollCalculator rollCalculator = calculator.getRoll();
    final PowerCalculator powerCalculator = calculator.getPower();
    for (final Unit unit : units) {
      final UnitPowerStrengthAndRolls data =
          UnitPowerStrengthAndRolls.builder()
              .unit(unit)
              .strengthAndRolls(
                  UnitPowerStrengthAndRolls.StrengthAndRolls.of(
                      strengthCalculator.getStrength(unit), rollCalculator.getRoll(unit)))
              .power(powerCalculator.getValue(unit))
              .powerCalculator(powerCalculator)
              .diceSides(calculator.getDiceSides(unit))
              .chooseBestRoll(calculator.chooseBestRoll(unit))
              .build();
      totalStrengthAndTotalRollsByUnit.put(unit, data);
      sortedStrengthAndRolls.add(data);
    }

    strengthCalculator
        .getSupportGiven()
        .forEach(
            (supporter, supportedUnits) ->
                unitSupportPowerMap
                    .computeIfAbsent(supporter, (newSupport) -> new IntegerMap<>())
                    .add(supportedUnits));
    rollCalculator
        .getSupportGiven()
        .forEach(
            (supporter, supportedUnits) ->
                unitSupportRollsMap
                    .computeIfAbsent(supporter, (newSupport) -> new IntegerMap<>())
                    .add(supportedUnits));
    if (calculator instanceof BombardmentCombatValue bombard && bombard.getDiceCap() != null) {
      capBombardDice(bombard.getDiceCap(), bombard.getReducedDieValue());
    }
  }

  /**
   * Flames and Steel (request 51): whole ships fire first, best value first; the last ship used, if
   * cut short, fires the dice it keeps at the reduced die value (or its own, if higher); later
   * ships do not fire. Never adds dice. Request 67: extra dice count as dice here too.
   */
  private void capBombardDice(final int cap, final int reducedDieValue) {
    final List<UnitPowerStrengthAndRolls> byValue = new ArrayList<>(sortedStrengthAndRolls);
    // request 67: best single die first (extra dice count); ties to more total dice value
    byValue.sort(
        java.util.Comparator.comparingInt(PowerStrengthAndRolls::bestDie)
            .thenComparingInt(UnitPowerStrengthAndRolls::getPower)
            .reversed());
    int left = cap;
    for (final UnitPowerStrengthAndRolls data : byValue) {
      final int rolls = data.getRolls();
      if (rolls <= 0) {
        continue;
      }
      final UnitPowerStrengthAndRolls capped;
      if (rolls <= left) {
        left -= rolls;
        continue;
      } else if (left > 0) {
        capped = cutShort(data, left, reducedDieValue);
        left = 0;
      } else {
        capped = data.toZero();
      }
      totalStrengthAndTotalRollsByUnit.put(data.getUnit(), capped);
      sortedStrengthAndRolls.set(sortedStrengthAndRolls.indexOf(data), capped);
    }
  }

  /** Request 67: the unit's best single die, main or extra. */
  private static int bestDie(final UnitPowerStrengthAndRolls data) {
    int best = data.getMainRolls() > 0 ? data.getStrength() : 0;
    for (final int die : data.getExtraDice()) {
      best = Math.max(best, die);
    }
    return best;
  }

  /**
   * Request 67: a ship left with fewer dice than it has keeps its best dice (main before extra when
   * equal); every kept die fires at its own value or the reduced die value, whichever is higher.
   */
  private static UnitPowerStrengthAndRolls cutShort(
      final UnitPowerStrengthAndRolls data, final int keep, final int reducedDieValue) {
    final int mainRolls = Math.max(0, data.getMainRolls());
    final int mainValue = data.getStrength();
    final List<Integer> extras = new ArrayList<>(data.getExtraDice());
    extras.sort(java.util.Comparator.reverseOrder());
    int keptMain = 0;
    final List<Integer> keptExtras = new ArrayList<>();
    int extraIndex = 0;
    for (int kept = 0; kept < keep; kept++) {
      final boolean mainLeft = keptMain < mainRolls;
      final boolean extraLeft = extraIndex < extras.size();
      if (mainLeft && (!extraLeft || mainValue >= extras.get(extraIndex))) {
        keptMain++;
      } else if (extraLeft) {
        keptExtras.add(Math.max(extras.get(extraIndex++), reducedDieValue));
      } else {
        break;
      }
    }
    return data.withDice(Math.max(mainValue, reducedDieValue), keptMain, keptExtras);
  }

  @Override
  public List<UnitPowerStrengthAndRolls> getActiveUnits() {
    return sortedStrengthAndRolls;
  }

  @Override
  public int calculateTotalPower() {
    return totalStrengthAndTotalRollsByUnit.values().stream()
        .mapToInt(UnitPowerStrengthAndRolls::getPower)
        .sum();
  }

  @Override
  public int calculateTotalRolls() {
    return totalStrengthAndTotalRollsByUnit.values().stream()
        .mapToInt(UnitPowerStrengthAndRolls::getRolls)
        .sum();
  }

  @Override
  public boolean hasStrengthOrRolls() {
    return calculateTotalRolls() != 0 && calculateTotalPower() != 0;
  }

  @Override
  public int getStrength(final Unit unit) {
    return totalStrengthAndTotalRollsByUnit.get(unit).getStrength();
  }

  @Override
  public int getRolls(final Unit unit) {
    return totalStrengthAndTotalRollsByUnit.get(unit).getRolls();
  }

  @Override
  public int getPower(final Unit unit) {
    return totalStrengthAndTotalRollsByUnit.get(unit).getPower();
  }
}
