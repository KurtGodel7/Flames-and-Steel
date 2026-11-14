package games.strategy.triplea.delegate;

import games.strategy.engine.data.GameState;
import javax.annotation.Nullable;

/**
 * Flames and Steel (request 68, part B): the computer players don't know the fork's own steps (air
 * stages, research centres, SPC output, garrisons), so game setup offers only human players for
 * this map. A nation that never takes a turn (the Dutch, Vichy) stays "Does Nothing", as before.
 *
 * <p>On for any game with the fork's air stages, or with the property "FS Human Players Only" set
 * to true.
 */
public final class HumanPlayersOnly {
  public static final String PROPERTY = "FS Human Players Only";

  /** Shown on the setup screens. */
  public static final String NOTE =
      "Flames and Steel: computer players are switched off for now, because the computer doesn't"
          + " know this game's special steps yet. Every nation is played by a person.";

  private HumanPlayersOnly() {}

  public static boolean applies(final @Nullable GameState data) {
    if (data == null) {
      return false;
    }
    return data.getProperties().get(PROPERTY, false) || AirStageDelegate.usesStages(data);
  }
}
