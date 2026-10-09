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
 * Flames and Steel lend-lease munitions. A unit type with {@code munitionsValue} &gt; 0 is
 * munitions: it may enter allied land even where the relationship keeps other land units out
 * (Soviet and Chinese soil); at the end of the turn of a nation that converts munitions it becomes
 * that nation's PUs if it stands on that nation's land; and if its territory is captured it becomes
 * the captor's PUs.
 */
public class MunitionsAttachment extends DefaultAttachment {
  @Serial private static final long serialVersionUID = -3360751880938129472L;
  public static final String ATTACHMENT_NAME = "munitionsAttachment";

  // PUs each unit of this type is worth when converted or captured
  @Getter private int munitionsValue = 0;

  public MunitionsAttachment(
      final String name, final Attachable attachable, final GameData gameData) {
    super(name, attachable, gameData);
  }

  public static Optional<MunitionsAttachment> get(final UnitType type) {
    return Optional.ofNullable((MunitionsAttachment) type.getAttachment(ATTACHMENT_NAME));
  }

  public static boolean isMunitions(final Unit unit) {
    return valueOf(unit) > 0;
  }

  public static int valueOf(final Unit unit) {
    return get(unit.getType()).map(MunitionsAttachment::getMunitionsValue).orElse(0);
  }

  private void setMunitionsValue(final String value) {
    munitionsValue = getInt(value);
  }

  private void setMunitionsValue(final Integer value) {
    munitionsValue = value;
  }

  @Override
  public void validate(final GameState data) {}

  @Override
  public Optional<MutableProperty<?>> getPropertyOrEmpty(final @NonNls String propertyName) {
    if ("munitionsValue".equals(propertyName)) {
      return Optional.of(
          MutableProperty.of(
              this::setMunitionsValue,
              this::setMunitionsValue,
              this::getMunitionsValue,
              () -> munitionsValue = 0));
    }
    return Optional.empty();
  }
}
