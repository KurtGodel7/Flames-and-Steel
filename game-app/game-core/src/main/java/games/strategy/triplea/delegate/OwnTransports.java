package games.strategy.triplea.delegate;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Flames and Steel (request 29 part A): a nation's transports carry only that nation's units, in
 * every relationship. The game property "FS Own Transports Only" turns the rule on or off; when
 * the XML doesn't set it, the rule is on in games with air fronts (Flames and Steel) and off
 * otherwise, so stock games keep allied loading.
 */
public final class OwnTransports {
  public static final String PROPERTY = "FS Own Transports Only";

  private OwnTransports() {}

  public static boolean isActive(final @Nullable GameData data) {
    if (data == null) {
      return false;
    }
    final Object value = data.getProperties().get(PROPERTY);
    if (value instanceof Boolean b) {
      return b;
    }
    if (value != null) {
      return Boolean.parseBoolean(value.toString());
    }
    return games.strategy.triplea.delegate.air.AirBases.usesBases(data);
  }

  /** Whether {@code unit} may ride on, or be unloaded from, {@code transport}. */
  public static boolean mayCarry(final Unit transport, final Unit unit) {
    return transport.getOwner().equals(unit.getOwner()) || !isActive(transport.getData());
  }

  /** Transports the units of {@code owner} may use. */
  public static Predicate<Unit> usableBy(final GamePlayer owner) {
    return transport -> transport.isOwnedBy(owner) || !isActive(transport.getData());
  }

  /** "Only British units can use British transports." */
  public static String message(final GamePlayer transportOwner) {
    final String adjective = adjective(transportOwner);
    return "Only " + adjective + " units can use " + adjective + " transports.";
  }

  /** "Americans" -> "American", "British" -> "British". */
  static String adjective(final GamePlayer player) {
    // request 32: "America Pacific" when the game gives the player a display name
    final String shown = games.strategy.triplea.formatter.PlayerNames.display(player);
    if (!shown.equals(player.getName())) {
      return shown;
    }
    final String name = player.getName();
    return name.endsWith("ans") ? name.substring(0, name.length() - 1) : name;
  }
}
