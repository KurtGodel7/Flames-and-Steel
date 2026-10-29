package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import java.awt.Color;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;

/**
 * Request 47: the how-to sentences of the air stages. They are always in the banner's tooltip; in
 * the side panel (small grey text) they show the first time each stage opens for a nation in a
 * game, or every time while the View menu's "Show Air Stage Help" is on. That option is on during
 * a game's first round and off after, until the player sets it.
 */
public final class AirHelp {
  private static final Map<GameData, Set<String>> seen =
      Collections.synchronizedMap(new WeakHashMap<>());
  private static volatile @Nullable Boolean chosen;

  private AirHelp() {}

  /** The View menu option: the player's choice, else on in round 1 only. */
  public static boolean isOn(final GameData data) {
    final Boolean choice = chosen;
    if (choice != null) {
      return choice;
    }
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      return data.getSequence().getRound() <= 1;
    }
  }

  public static void setOn(final boolean on) {
    chosen = on;
  }

  /**
   * Whether the stage's help shows now, as the stage opens for the nation. Counts the opening:
   * call once per stage.
   */
  public static boolean showAsStageOpens(final GameData data, final GamePlayer player, final String stage) {
    final boolean first =
        seen.computeIfAbsent(data, d -> Collections.synchronizedSet(new HashSet<>()))
            .add(player.getName() + "|" + stage);
    return first || isOn(data);
  }

  /** The help as small grey text that wraps in the side panel. */
  static JComponent text(final String help) {
    final JLabel label =
        new JLabel("<html><div style='width:220px'>" + help + "</div></html>");
    final Color grey = UIManager.getColor("Label.disabledForeground");
    label.setForeground(grey == null ? Color.GRAY : grey);
    label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
    label.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 5, 4, 0));
    return label;
  }
}
