package games.strategy.triplea.attachments;

import games.strategy.engine.data.Attachable;
import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import java.io.Serial;
import java.util.Optional;
import lombok.Getter;
import org.jetbrains.annotations.NonNls;

/**
 * Flames and Steel: air combat properties of a unit type (aircraft and carriers), used by the
 * abstract air zones.
 */
public class AirUnitAttachment extends DefaultAttachment {
  @Serial private static final long serialVersionUID = -2385710968021571234L;
  public static final String ATTACHMENT_NAME = "airUnitAttachment";

  // fighter-type aircraft (fighters, advanced fighters, jets) are allocated after the bombers
  @Getter private boolean fighter = false;
  // carriers: a fleet carrier, whose slots jets may use
  @Getter private boolean fleetCarrier = false;
  // aircraft: may only use fleet carriers (jets)
  @Getter private boolean fleetCarrierOnly = false;

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

  @Override
  public void validate(final GameState data) {}

  @Override
  public Optional<MutableProperty<?>> getPropertyOrEmpty(final @NonNls String propertyName) {
    return switch (propertyName) {
      case "fighter" ->
          Optional.of(
              MutableProperty.of(
                  this::setFighter, this::setFighter, this::isFighter, () -> fighter = false));
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
      default -> Optional.empty();
    };
  }
}
