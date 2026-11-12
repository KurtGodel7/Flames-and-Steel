package games.strategy.triplea.delegate.power.calculator;

import games.strategy.engine.data.Unit;
import javax.annotation.Nonnull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;

/** Stores and computes an individual unit's power, strength and roll */
@Value
@Getter(AccessLevel.PACKAGE)
@Builder(access = AccessLevel.PACKAGE, toBuilder = true)
public class UnitPowerStrengthAndRolls {
  @Getter(AccessLevel.PUBLIC)
  @Nonnull
  Integer power;

  @Nonnull StrengthAndRolls strengthAndRolls;

  @Getter(AccessLevel.PUBLIC)
  @Nonnull
  Unit unit;

  @Nonnull PowerCalculator powerCalculator;

  @Getter(AccessLevel.PUBLIC)
  @Nonnull
  Boolean chooseBestRoll;

  @Getter(AccessLevel.PUBLIC)
  @Nonnull
  Integer diceSides;

  @Value(staticConstructor = "of")
  static class StrengthAndRolls {
    StrengthValue strength;
    RollValue rolls;

    private StrengthAndRolls(final StrengthValue strength, final RollValue rolls) {
      if (strength.isZero() || rolls.isZero()) {
        this.strength = strength.toValue(0);
        this.rolls = rolls.toValue(0);
      } else {
        this.strength = strength;
        this.rolls = rolls;
      }
    }
  }

  public int getStrength() {
    return strengthAndRolls.getStrength().getValue();
  }

  /** The dice the unit rolls: its main rolls plus any extra dice (request 66). */
  public int getRolls() {
    final int main = getMainRolls();
    return main < 0 ? main : main + getExtraDice().size();
  }

  /** Request 66: the rolls at the main strength only. */
  public int getMainRolls() {
    return strengthAndRolls.getRolls().getValue();
  }

  /** Request 66: the extra dice, each at its own value (support included), lowest first. */
  public java.util.List<Integer> getExtraDice() {
    return strengthAndRolls.getStrength().getExtraDice();
  }

  public UnitPowerStrengthAndRolls subtractStrength(final int strengthToSubtract) {
    // request 66: support is taken back from where it was given (the lowest extra die, if any)
    return update(
        strengthAndRolls.getStrength().addSupport(-1 * strengthToSubtract),
        strengthAndRolls.getRolls());
  }

  private UnitPowerStrengthAndRolls update(
      final StrengthValue newStrength, final RollValue newRolls) {
    return this.toBuilder()
        .strengthAndRolls(StrengthAndRolls.of(newStrength, newRolls))
        .power(powerCalculator.getValue(chooseBestRoll, diceSides, newStrength, newRolls))
        .build();
  }

  public UnitPowerStrengthAndRolls subtractRolls(final int rollsToSubtract) {
    return update(
        strengthAndRolls.getStrength(), strengthAndRolls.getRolls().add(-1 * rollsToSubtract));
  }

  /** Flames and Steel (request 51): a bombarding ship cut short by the dice cap. */
  UnitPowerStrengthAndRolls withStrengthAndRolls(final int newStrength, final int newRolls) {
    return update(
        strengthAndRolls.getStrength().toValue(newStrength),
        strengthAndRolls.getRolls().toValue(newRolls));
  }

  UnitPowerStrengthAndRolls updateRolls(final int newRolls) {
    return update(strengthAndRolls.getStrength(), strengthAndRolls.getRolls().toValue(newRolls));
  }

  UnitPowerStrengthAndRolls toZero() {
    final StrengthValue newStrength = strengthAndRolls.getStrength().withoutExtraDice().toValue(0);
    final RollValue newRolls = strengthAndRolls.getRolls().toValue(0);
    return this.toBuilder()
        .strengthAndRolls(StrengthAndRolls.of(newStrength, newRolls))
        .power(powerCalculator.getValue(chooseBestRoll, diceSides, newStrength, newRolls))
        .build();
  }
}
