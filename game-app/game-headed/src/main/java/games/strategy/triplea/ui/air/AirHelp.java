package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import java.awt.Color;
import java.awt.Component;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.UIManager;

/**
 * Request 47: the how-to sentences of the air stages. They are always in the banner's tooltip; in
 * the side panel (small grey text) they show the first time each stage opens for a nation in a
 * game (request 48: until the first move there), or every time while the View menu's "Show Air
 * Stage Help" is on. That option is off unless the player turns it on.
 */
public final class AirHelp {
  private static final Map<GameData, Set<String>> seen =
      Collections.synchronizedMap(new WeakHashMap<>());
  private static volatile @Nullable Boolean chosen;

  private AirHelp() {}

  /** The View menu option: off unless the player turned it on (request 48). */
  public static boolean isOn(final GameData data) {
    return Boolean.TRUE.equals(chosen);
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

  /** The help as small grey text that wraps to the width of the panel it is in. */
  static JComponent text(final String help) {
    final javax.swing.JTextArea area =
        new javax.swing.JTextArea(help) {
          private static final long serialVersionUID = 1L;

          @Override
          public java.awt.Dimension getPreferredSize() {
            // as wide as the panel gives it; as tall as the wrapped text needs at that width
            final java.awt.Container parent = getParent();
            final int width =
                parent != null && parent.getWidth() > 0
                    ? parent.getWidth() - parent.getInsets().left - parent.getInsets().right
                    : 260;
            if (getWidth() != width) {
              setSize(width, Short.MAX_VALUE);
            }
            return new java.awt.Dimension(width, super.getPreferredSize().height);
          }

          @Override
          public java.awt.Dimension getMaximumSize() {
            return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
          }
        };
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    final Color grey = UIManager.getColor("Label.disabledForeground");
    area.setForeground(grey == null ? Color.GRAY : grey);
    final java.awt.Font font = UIManager.getFont("Label.font");
    if (font != null) {
      area.setFont(font.deriveFont(font.getSize2D() - 1f));
    }
    area.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 5, 4, 2));
    area.setAlignmentX(Component.LEFT_ALIGNMENT);
    // a new width needs a new height: lay the panel out again
    area.addComponentListener(
        new java.awt.event.ComponentAdapter() {
          private int lastWidth = -1;

          @Override
          public void componentResized(final java.awt.event.ComponentEvent e) {
            if (area.getWidth() != lastWidth) {
              lastWidth = area.getWidth();
              javax.swing.SwingUtilities.invokeLater(
                  () -> {
                    if (area.getParent() instanceof JComponent c) {
                      c.revalidate();
                    }
                  });
            }
          }
        });
    return area;
  }
}
