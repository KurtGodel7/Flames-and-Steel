package games.strategy.triplea.attachments;

import games.strategy.engine.data.Attachable;
import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParseException;
import java.io.Serial;
import java.util.Optional;
import javax.annotation.Nullable;
import lombok.Getter;
import org.jetbrains.annotations.NonNls;

/**
 * Flames and Steel advanced production centres (APCs). On the APC unit type: {@code isApc}. On an
 * advanced unit type an APC can build: its output rate ({@code apcUnitsPerTurn} every {@code
 * apcTurnsPerUnit} turns, e.g. Type XXI 2 per turn, Superfortress 1 every 2 turns) and the tech the
 * nation needs ({@code apcRequiredTech}).
 */
public class ApcAttachment extends DefaultAttachment {
  @Serial private static final long serialVersionUID = 4920175538281145077L;
  public static final String ATTACHMENT_NAME = "apcAttachment";

  @Getter private boolean isApc = false;
  // 0: an APC cannot build this unit type
  @Getter private int apcUnitsPerTurn = 0;
  @Getter private int apcTurnsPerUnit = 1;
  private @Nullable String apcRequiredTech = null;

  public ApcAttachment(final String name, final Attachable attachable, final GameData gameData) {
    super(name, attachable, gameData);
  }

  public static Optional<ApcAttachment> get(final UnitType type) {
    return Optional.ofNullable((ApcAttachment) type.getAttachment(ATTACHMENT_NAME));
  }

  public static boolean isApc(final UnitType type) {
    return get(type).map(ApcAttachment::isApc).orElse(false);
  }

  /** Whether an APC can build this unit type at all (some nations may still lack the tech). */
  public static boolean isApcBuildable(final UnitType type) {
    return get(type).map(a -> a.getApcUnitsPerTurn() > 0).orElse(false);
  }

  public Optional<String> getApcRequiredTech() {
    return Optional.ofNullable(apcRequiredTech);
  }

  private void setIsApc(final String value) {
    isApc = getBool(value);
  }

  private void setIsApc(final Boolean value) {
    isApc = value;
  }

  private void setApcUnitsPerTurn(final String value) throws GameParseException {
    apcUnitsPerTurn = positive(value, "apcUnitsPerTurn");
  }

  private void setApcUnitsPerTurn(final Integer value) {
    apcUnitsPerTurn = value;
  }

  private void setApcTurnsPerUnit(final String value) throws GameParseException {
    apcTurnsPerUnit = positive(value, "apcTurnsPerUnit");
  }

  private void setApcTurnsPerUnit(final Integer value) {
    apcTurnsPerUnit = value;
  }

  private void setApcRequiredTech(final String value) {
    apcRequiredTech = value.intern();
  }

  private int positive(final String value, final String option) throws GameParseException {
    final int result = getInt(value);
    if (result < 1) {
      throw new GameParseException(option + " must be at least 1" + thisErrorMsg());
    }
    return result;
  }

  @Override
  public void validate(final GameState data) throws GameParseException {
    if (apcRequiredTech != null
        && data.getTechnologyFrontier().getAdvanceByName(apcRequiredTech) == null) {
      throw new GameParseException(
          "apcRequiredTech names an unknown technology: " + apcRequiredTech + thisErrorMsg());
    }
  }

  @Override
  public Optional<MutableProperty<?>> getPropertyOrEmpty(final @NonNls String propertyName) {
    return switch (propertyName) {
      case "isApc" ->
          Optional.of(
              MutableProperty.of(this::setIsApc, this::setIsApc, this::isApc, () -> isApc = false));
      case "apcUnitsPerTurn" ->
          Optional.of(
              MutableProperty.of(
                  this::setApcUnitsPerTurn,
                  this::setApcUnitsPerTurn,
                  this::getApcUnitsPerTurn,
                  () -> apcUnitsPerTurn = 0));
      case "apcTurnsPerUnit" ->
          Optional.of(
              MutableProperty.of(
                  this::setApcTurnsPerUnit,
                  this::setApcTurnsPerUnit,
                  this::getApcTurnsPerUnit,
                  () -> apcTurnsPerUnit = 1));
      case "apcRequiredTech" ->
          Optional.of(
              MutableProperty.of(
                  this::setApcRequiredTech,
                  this::setApcRequiredTech,
                  () -> apcRequiredTech,
                  () -> apcRequiredTech = null));
      default -> Optional.empty();
    };
  }
}
