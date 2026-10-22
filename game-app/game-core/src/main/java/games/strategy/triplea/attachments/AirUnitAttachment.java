package games.strategy.triplea.attachments;

import games.strategy.engine.data.Attachable;
import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParseException;
import java.io.Serial;
import java.util.Optional;
import javax.annotation.Nullable;
import lombok.Getter;
import org.jetbrains.annotations.NonNls;

/**
 * Flames and Steel: air combat properties of a unit type (aircraft and carriers), used by the
 * abstract air zones.
 */
public class AirUnitAttachment extends DefaultAttachment {
  @Serial private static final long serialVersionUID = -2385710968021571234L;
  public static final String ATTACHMENT_NAME = "airUnitAttachment";

  // fighter-type aircraft (fighters, advanced fighters, jets) are allocated after the bombers,
  // fight in the dogfight and intercept bombers; every other aircraft is a bomber-type. Set by
  // "airRole" (fighter | bomber) or the older "fighter" (true | false).
  @Getter private boolean fighter = false;
  // carriers: a fleet carrier, whose slots jets may use
  @Getter private boolean fleetCarrier = false;
  // aircraft: may only use fleet carriers (jets)
  @Getter private boolean fleetCarrierOnly = false;
  // aircraft: firing value in dogfights, and how many times it fires
  @Getter private int dogfight = 0;
  @Getter private int dogfightRolls = 1;
  // request 27, fighter-types: firing value against enemy bombers in the interception step, and
  // how many times it fires. Null = not set in the XML (or a save from before request 27): the
  // dogfight value is used, and 1 roll.
  private @Nullable Integer interception = null;
  private @Nullable Integer interceptionRolls = null;
  // request 27, bomber-types: firing value back at interceptors, and how many times it fires.
  // Null = not set in the XML: the dogfight value is used, and 1 roll.
  private @Nullable Integer airDefense = null;
  private @Nullable Integer airDefenseRolls = null;
  // aircraft: PUs of damage per strategic bombing run (0 = cannot bomb strategically)
  @Getter private int bombingDamage = 0;
  // aircraft: double attack against land targets (dive bombers)
  @Getter private boolean doubleVsLand = false;
  // aircraft: double attack against surface ships (torpedo bombers)
  @Getter private boolean doubleVsSurface = false;
  // aircraft: may attack submarines (torpedo and medium bombers)
  @Getter private boolean canAttackSubs = false;

  public AirUnitAttachment(
      final String name, final Attachable attachable, final GameData gameData) {
    super(name, attachable, gameData);
  }

  public static Optional<AirUnitAttachment> get(final UnitType unitType) {
    return Optional.ofNullable((AirUnitAttachment) unitType.getAttachment(ATTACHMENT_NAME));
  }

  public static boolean isFighter(final Unit unit) {
    return get(unit.getType()).map(AirUnitAttachment::isFighter).orElse(false);
  }

  public static boolean isFleetCarrier(final Unit unit) {
    return get(unit.getType()).map(AirUnitAttachment::isFleetCarrier).orElse(false);
  }

  public static boolean isFleetCarrierOnly(final Unit unit) {
    return get(unit.getType()).map(AirUnitAttachment::isFleetCarrierOnly).orElse(false);
  }

  private void setFighter(final String value) {
    fighter = getBool(value);
  }

  private void setFighter(final Boolean value) {
    fighter = value;
  }

  private void setAirRole(final String value) throws GameParseException {
    switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "fighter" -> fighter = true;
      case "bomber" -> fighter = false;
      default ->
          throw new GameParseException(
              "airRole must be fighter or bomber, not " + value + thisErrorMsg());
    }
  }

  private String getAirRole() {
    return fighter ? "fighter" : "bomber";
  }

  /** The interception value set in the XML; the dogfight value when it is not set. */
  public int getInterception() {
    return interception == null ? dogfight : interception;
  }

  /** The air defense value set in the XML; the dogfight value when it is not set. */
  public int getAirDefense() {
    return airDefense == null ? dogfight : airDefense;
  }

  public int getInterceptionRolls() {
    return interceptionRolls == null ? 1 : interceptionRolls;
  }

  public int getAirDefenseRolls() {
    return airDefenseRolls == null ? 1 : airDefenseRolls;
  }

  /** Whether the XML sets the interception value itself (request 27 migration check). */
  public boolean hasOwnInterception() {
    return interception != null;
  }

  /** Whether the XML sets the air defense value itself (request 27 migration check). */
  public boolean hasOwnAirDefense() {
    return airDefense != null;
  }

  private void setInterception(final String value) {
    interception = getInt(value);
  }

  private void setInterception(final Integer value) {
    interception = value;
  }

  private void setInterceptionRolls(final String value) {
    interceptionRolls = getInt(value);
  }

  private void setInterceptionRolls(final Integer value) {
    interceptionRolls = value;
  }

  private void setAirDefense(final String value) {
    airDefense = getInt(value);
  }

  private void setAirDefense(final Integer value) {
    airDefense = value;
  }

  private void setAirDefenseRolls(final String value) {
    airDefenseRolls = getInt(value);
  }

  private void setAirDefenseRolls(final Integer value) {
    airDefenseRolls = value;
  }

  private void setFleetCarrier(final String value) {
    fleetCarrier = getBool(value);
  }

  private void setFleetCarrier(final Boolean value) {
    fleetCarrier = value;
  }

  private void setFleetCarrierOnly(final String value) {
    fleetCarrierOnly = getBool(value);
  }

  private void setFleetCarrierOnly(final Boolean value) {
    fleetCarrierOnly = value;
  }

  private void setDogfight(final String value) {
    dogfight = getInt(value);
  }

  private void setDogfight(final Integer value) {
    dogfight = value;
  }

  private void setDogfightRolls(final String value) {
    dogfightRolls = getInt(value);
  }

  private void setDogfightRolls(final Integer value) {
    dogfightRolls = value;
  }

  private void setBombingDamage(final String value) {
    bombingDamage = getInt(value);
  }

  private void setBombingDamage(final Integer value) {
    bombingDamage = value;
  }

  private void setDoubleVsLand(final String value) {
    doubleVsLand = getBool(value);
  }

  private void setDoubleVsLand(final Boolean value) {
    doubleVsLand = value;
  }

  private void setDoubleVsSurface(final String value) {
    doubleVsSurface = getBool(value);
  }

  private void setDoubleVsSurface(final Boolean value) {
    doubleVsSurface = value;
  }

  private void setCanAttackSubs(final String value) {
    canAttackSubs = getBool(value);
  }

  private void setCanAttackSubs(final Boolean value) {
    canAttackSubs = value;
  }

  @Override
  public void validate(final GameState data) {}

  @Override
  public Optional<MutableProperty<?>> getPropertyOrEmpty(final @NonNls String propertyName) {
    return switch (propertyName) {
      case "fighter" ->
          Optional.of(
              MutableProperty.of(
                  this::setFighter, this::setFighter, this::isFighter, () -> fighter = false));
      case "airRole" ->
          Optional.of(
              MutableProperty.ofString(
                  this::setAirRole, this::getAirRole, () -> fighter = false));
      case "interception" ->
          Optional.of(
              MutableProperty.of(
                  this::setInterception,
                  this::setInterception,
                  this::getInterception,
                  () -> interception = null));
      case "interceptionRolls" ->
          Optional.of(
              MutableProperty.of(
                  this::setInterceptionRolls,
                  this::setInterceptionRolls,
                  this::getInterceptionRolls,
                  () -> interceptionRolls = null));
      case "airDefense" ->
          Optional.of(
              MutableProperty.of(
                  this::setAirDefense,
                  this::setAirDefense,
                  this::getAirDefense,
                  () -> airDefense = null));
      case "airDefenseRolls" ->
          Optional.of(
              MutableProperty.of(
                  this::setAirDefenseRolls,
                  this::setAirDefenseRolls,
                  this::getAirDefenseRolls,
                  () -> airDefenseRolls = null));
      case "fleetCarrier" ->
          Optional.of(
              MutableProperty.of(
                  this::setFleetCarrier,
                  this::setFleetCarrier,
                  this::isFleetCarrier,
                  () -> fleetCarrier = false));
      case "fleetCarrierOnly" ->
          Optional.of(
              MutableProperty.of(
                  this::setFleetCarrierOnly,
                  this::setFleetCarrierOnly,
                  this::isFleetCarrierOnly,
                  () -> fleetCarrierOnly = false));
      case "dogfight" ->
          Optional.of(
              MutableProperty.of(
                  this::setDogfight, this::setDogfight, this::getDogfight, () -> dogfight = 0));
      case "dogfightRolls" ->
          Optional.of(
              MutableProperty.of(
                  this::setDogfightRolls,
                  this::setDogfightRolls,
                  this::getDogfightRolls,
                  () -> dogfightRolls = 1));
      case "bombingDamage" ->
          Optional.of(
              MutableProperty.of(
                  this::setBombingDamage,
                  this::setBombingDamage,
                  this::getBombingDamage,
                  () -> bombingDamage = 0));
      case "doubleVsLand" ->
          Optional.of(
              MutableProperty.of(
                  this::setDoubleVsLand,
                  this::setDoubleVsLand,
                  this::isDoubleVsLand,
                  () -> doubleVsLand = false));
      case "doubleVsSurface" ->
          Optional.of(
              MutableProperty.of(
                  this::setDoubleVsSurface,
                  this::setDoubleVsSurface,
                  this::isDoubleVsSurface,
                  () -> doubleVsSurface = false));
      case "canAttackSubs" ->
          Optional.of(
              MutableProperty.of(
                  this::setCanAttackSubs,
                  this::setCanAttackSubs,
                  this::isCanAttackSubs,
                  () -> canAttackSubs = false));
      default -> Optional.empty();
    };
  }
}
