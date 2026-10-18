package games.strategy.triplea.formatter;

import games.strategy.engine.data.UnitType;
import java.util.Map;

/**
 * Flames and Steel: the name shown to players for a unit type whose XML identifier is kept for
 * compatibility. The unit type "apc" (Advanced Production Center) is shown as "Special Production
 * Center", or "SPC" where space is short; every other unit type shows its XML name as before.
 */
public final class UnitNames {
  private static final Map<String, String> FULL = Map.of("apc", "Special Production Center");
  private static final Map<String, String> SHORT = Map.of("apc", "SPC");

  private UnitNames() {}

  /** The player-visible name of the unit type with this XML name. */
  public static String of(final String typeName) {
    return typeName == null ? null : FULL.getOrDefault(typeName, typeName);
  }

  public static String of(final UnitType type) {
    return of(type.getName());
  }

  /** As {@link #of(String)}, but a short form where one exists ("SPC"). */
  public static String shortOf(final String typeName) {
    return typeName == null ? null : SHORT.getOrDefault(typeName, of(typeName));
  }

  public static String shortOf(final UnitType type) {
    return shortOf(type.getName());
  }

  /** True if this XML name has a different player-visible name. */
  public static boolean isRenamed(final String typeName) {
    return typeName != null && FULL.containsKey(typeName);
  }
}
