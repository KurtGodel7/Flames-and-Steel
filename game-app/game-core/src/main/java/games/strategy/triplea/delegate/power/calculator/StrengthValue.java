package games.strategy.triplea.delegate.power.calculator;

import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Holds the value of a strength
 *
 * <p>Correctly limits the value to between 0 and diceSides
 *
 * <p>Flames and Steel (request 66): a strength can also carry extra dice, each at its own value.
 * Support given to a unit with extra dice raises only its lowest extra die, not the main value.
 */
@EqualsAndHashCode
@ToString
@Getter(AccessLevel.NONE)
class StrengthValue {

  private final int diceSides;
  private final int value;
  // request 66: the extra dice before support, and the support they get (on the lowest one)
  private final List<Integer> extraDice;
  private final int extraDiceSupport;

  private StrengthValue(
      final int diceSides,
      final int value,
      final List<Integer> extraDice,
      final int extraDiceSupport) {
    this.diceSides = diceSides;
    this.value = value;
    this.extraDice = extraDice;
    this.extraDiceSupport = extraDiceSupport;
  }

  static StrengthValue of(final int diceSides, final int value) {
    return new StrengthValue(diceSides, value, List.of(), 0);
  }

  /** Request 66: the same strength with these extra dice (values before support). */
  StrengthValue withExtraDice(final List<Integer> dice) {
    return new StrengthValue(diceSides, value, List.copyOf(dice), extraDiceSupport);
  }

  /** Adds to the main value (territory effects, bonuses). */
  StrengthValue add(final int extraValue) {
    return new StrengthValue(diceSides, value + extraValue, extraDice, extraDiceSupport);
  }

  /**
   * Request 66: adds support. A unit with extra dice gets it on its lowest extra die; any other
   * unit on its main value, as before.
   */
  StrengthValue addSupport(final int support) {
    if (extraDice.isEmpty()) {
      return add(support);
    }
    return new StrengthValue(diceSides, value, extraDice, extraDiceSupport + support);
  }

  StrengthValue toValue(final int value) {
    return new StrengthValue(diceSides, value, extraDice, extraDiceSupport);
  }

  /** Request 66: the same strength without extra dice. */
  StrengthValue withoutExtraDice() {
    return new StrengthValue(diceSides, value, List.of(), 0);
  }

  int getValue() {
    return Math.min(Math.max(value, 0), diceSides);
  }

  boolean isZero() {
    return value == 0;
  }

  /**
   * Request 66: the extra dice as rolled, the lowest first with any support on it, each capped
   * between 0 and the dice sides. Dice at 0 are left out (they do not roll).
   */
  List<Integer> getExtraDice() {
    if (extraDice.isEmpty()) {
      return List.of();
    }
    final List<Integer> sorted = new ArrayList<>(extraDice);
    sorted.sort(null);
    sorted.set(0, sorted.get(0) + extraDiceSupport);
    final List<Integer> result = new ArrayList<>();
    for (final int die : sorted) {
      final int capped = Math.min(Math.max(die, 0), diceSides);
      if (capped > 0) {
        result.add(capped);
      }
    }
    return result;
  }

  int getExtraDicePower() {
    return getExtraDice().stream().mapToInt(Integer::intValue).sum();
  }
}
