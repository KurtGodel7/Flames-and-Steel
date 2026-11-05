package games.strategy.triplea.ui.mapdata;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NonNls;
import org.triplea.java.ColorUtils;

@Slf4j
public final class PlayerColors {
  @NonNls public static final String PROPERTY_COLOR_PREFIX = "color.";
  @NonNls public static final String PLAYER_NAME_IMPASSABLE = "Impassable";
  @NonNls public static final String PLAYER_NAME_IMPASSABLE_LEGACY_SPELLING = "Impassible";
  public static final Color DEFAULT_IMPASSABLE_COLOR = ColorUtils.fromHexString("DEB887");

  @VisibleForTesting
  static final ImmutableList<Color> COLORS =
      ImmutableList.of(
          Color.RED,
          Color.MAGENTA,
          Color.YELLOW,
          Color.ORANGE,
          Color.CYAN,
          Color.GREEN,
          Color.PINK,
          Color.GRAY);

  @VisibleForTesting static final int DEFAULT_COLOR_COUNT = COLORS.size();

  /**
   * map.properties key giving single impassable territories their own color, for example {@code
   * color.impassable.overrides=Pripet Marshes - Impassable:66753A}.
   */
  @NonNls public static final String PROPERTY_IMPASSABLE_OVERRIDES = "color.impassable.overrides";

  private final Iterator<Color> colorIterator = COLORS.iterator();
  private final Map<String, Color> playerColors = new HashMap<>();
  private final Properties mapProperties;

  /** The map's territory names, used to warn about unknown names; null means don't check. */
  @Nullable private final Set<String> knownTerritories;

  /** Parsed lazily from {@link #PROPERTY_IMPASSABLE_OVERRIDES}. */
  @Nullable private Map<String, Color> impassableOverrides;

  PlayerColors(final Properties mapProperties) {
    this(mapProperties, null);
  }

  PlayerColors(final Properties mapProperties, @Nullable final Set<String> knownTerritories) {
    this.mapProperties = mapProperties;
    this.knownTerritories = knownTerritories;
  }

  /**
   * Returns the color that should be used for a given player. If the player's color is not defined
   * in map.properties then a default color is randomly generated.
   */
  Color getPlayerColor(final String playerName) {
    Preconditions.checkArgument(
        !playerName.equals(PLAYER_NAME_IMPASSABLE)
            && !playerName.equals(PLAYER_NAME_IMPASSABLE_LEGACY_SPELLING),
        "Illegal player name: %s, use the method 'getImpassableColor()' instead",
        playerName);

    // NOTE: we do *not* use computeIfAbsent here to avoid
    // 'java.util.ConcurrentModificationException'
    Color playerColor = playerColors.get(playerName);
    if (playerColor == null) {
      playerColor = computePlayerColor(playerName);
      playerColors.put(playerName, playerColor);
    }
    return playerColor;
  }

  private Color computePlayerColor(final String playerName) {
    return Optional.ofNullable(mapProperties.getProperty(PROPERTY_COLOR_PREFIX + playerName))
        .map(ColorUtils::fromHexString)
        .orElseGet(() -> nextColor(playerName));
  }

  /**
   * Returns the next available default color or generates a random one if no more default colors
   * are available.
   */
  private Color nextColor(final String playerName) {
    return colorIterator.hasNext()
        ? colorIterator.next()
        : ColorUtils.randomColor(playerName.hashCode());
  }

  /**
   * Returns the color for one impassable territory: its own color when map.properties lists it
   * under {@link #PROPERTY_IMPASSABLE_OVERRIDES}, otherwise {@link #getImpassableColor()}.
   */
  Color getImpassableColor(final String territoryName) {
    if (impassableOverrides == null) {
      impassableOverrides = parseImpassableOverrides();
    }
    final Color own = impassableOverrides.get(territoryName);
    return own != null ? own : getImpassableColor();
  }

  /**
   * Reads {@code color.impassable.overrides=Territory name:HEX, Other name:HEX}. Territory names
   * may contain spaces, hyphens and colons, so each pair is split on its last colon. A pair with a
   * bad color or (when the map's territory names are known) an unknown territory is skipped with a
   * warning; the map always loads.
   */
  private Map<String, Color> parseImpassableOverrides() {
    final Map<String, Color> overrides = new HashMap<>();
    final String value = mapProperties.getProperty(PROPERTY_IMPASSABLE_OVERRIDES);
    if (value == null || value.isBlank()) {
      return overrides;
    }
    for (final String rawPair : value.split(",")) {
      final String pair = rawPair.trim();
      if (pair.isEmpty()) {
        continue;
      }
      final int colon = pair.lastIndexOf(':');
      if (colon <= 0) {
        log.warn(
            "{}: ignoring '{}' (expected 'Territory name:HEX')",
            PROPERTY_IMPASSABLE_OVERRIDES,
            pair);
        continue;
      }
      final String territory = pair.substring(0, colon).trim();
      final String hex = pair.substring(colon + 1).trim();
      if (knownTerritories != null && !knownTerritories.contains(territory)) {
        log.warn("{}: ignoring unknown territory '{}'", PROPERTY_IMPASSABLE_OVERRIDES, territory);
        continue;
      }
      try {
        overrides.put(territory, ColorUtils.fromHexString(hex));
      } catch (final IllegalArgumentException e) {
        log.warn(
            "{}: ignoring bad color '{}' for '{}'", PROPERTY_IMPASSABLE_OVERRIDES, hex, territory);
      }
    }
    return overrides;
  }

  /**
   * Returns the color for the 'Impassable player', this is to mean impassible territories. If the
   * impassable player color is not defined in map.properties then a default is returned.
   */
  Color getImpassableColor() {
    return Optional.ofNullable(
            mapProperties.getProperty(PROPERTY_COLOR_PREFIX + PLAYER_NAME_IMPASSABLE))
        .or(
            () ->
                Optional.ofNullable(
                    mapProperties.getProperty(
                        PROPERTY_COLOR_PREFIX + PLAYER_NAME_IMPASSABLE_LEGACY_SPELLING)))
        .map(ColorUtils::fromHexString)
        .orElse(DEFAULT_IMPASSABLE_COLOR);
  }
}
