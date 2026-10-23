package games.strategy.triplea.ui;

import games.strategy.triplea.formatter.PlayerNames;
import static com.google.common.base.Preconditions.checkNotNull;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.formatter.UnitNames;
import games.strategy.triplea.image.HitPointBar;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JToolTip;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import org.triplea.java.StringUtils;

/** Responsible for showing tool tips when hovering over units on the main map. */
public final class MapUnitTooltipManager implements ActionListener {
  private final JComponent parent;
  private final WindowDeactivationObserver windowDeactivationObserver =
      new WindowDeactivationObserver();
  private final Timer timer;
  private @Nullable String text;
  private @Nullable Popup popup;
  private @Nullable Window window;

  public MapUnitTooltipManager(final JComponent parent) {
    checkNotNull(parent);

    this.parent = parent;
    // Timeout to show tooltip is 2 seconds.
    this.timer = new Timer(2000, this);
    this.timer.setRepeats(false);
    // Note: Timer not started yet.

    // Close tooltips when the window becomes inactive - so they don't overlap opened dialogs.
    // Listen to ancestor events as the component may not have a Window yet.
    parent.addAncestorListener(windowDeactivationObserver);
    attachWindowListener();
  }

  private final class WindowDeactivationObserver extends WindowAdapter implements AncestorListener {
    @Override
    public void windowClosed(final WindowEvent e) {
      detachWindowListener();
    }

    @Override
    public void windowDeactivated(final WindowEvent e) {
      updateTooltip("");
    }

    @Override
    public void ancestorAdded(final AncestorEvent event) {
      reattachWindowListener();
    }

    @Override
    public void ancestorRemoved(final AncestorEvent event) {
      reattachWindowListener();
    }

    @Override
    public void ancestorMoved(final AncestorEvent event) {
      reattachWindowListener();
    }
  }

  private void reattachWindowListener() {
    detachWindowListener();
    attachWindowListener();
  }

  private void detachWindowListener() {
    if (window != null) {
      window.removeWindowListener(windowDeactivationObserver);
    }
  }

  private void attachWindowListener() {
    window = SwingUtilities.getWindowAncestor(parent);
    if (window != null) {
      window.addWindowListener(windowDeactivationObserver);
    }
  }

  /**
   * Sets the tooltip text on the specified label based on the passed parameters.
   *
   * @param component The component whose tooltip text property will be set.
   * @param unitType The type of unit.
   * @param player The owner of the unit.
   * @param count The number of units.
   */
  public static void setUnitTooltip(
      final JComponent component,
      final UnitType unitType,
      final GamePlayer player,
      final int count,
      final UiContext uiContext) {
    final String text = getTooltipTextForUnit(unitType, player, count, uiContext);
    component.setToolTipText("<html>" + text + "</html>");
  }

  /** As above, for units that have taken {@code hits} of their {@code hitPoints}. */
  public static void setUnitTooltip(
      final JComponent component,
      final UnitType unitType,
      final GamePlayer player,
      final int count,
      final int hits,
      final int hitPoints,
      final UiContext uiContext) {
    final String text = getTooltipTextForUnit(unitType, player, count, hits, hitPoints, uiContext);
    component.setToolTipText("<html>" + text + "</html>");
  }

  /**
   * Returns the tooltip text for the passed parameters.
   *
   * @param unitType The type of unit.
   * @param player The owner of the unit.
   * @param count The number of units.
   * @return The tooltip text.
   */
  public static String getTooltipTextForUnit(
      final UnitType unitType,
      final GamePlayer player,
      final int count,
      final UiContext uiContext) {
    return getTooltipTextForUnit(
        unitType, player, count, 0, hitPointsOf(unitType, player), uiContext);
  }

  /**
   * As above, for units that have taken {@code hits}. Flames and Steel: units with 2 or more hit
   * points show what they have left, e.g. "Battleship — 3/5 HP".
   */
  public static String getTooltipTextForUnit(
      final UnitType unitType,
      final GamePlayer player,
      final int count,
      final int hits,
      final int hitPoints,
      final UiContext uiContext) {
    final String firstLine =
        String.format(
            "<b>%s%s (%s)</b>%s<br />",
            count == 1 ? "" : (count + " "),
            StringUtils.capitalize(UnitNames.of(unitType)),
            PlayerNames.display(player),
            HitPointBar.hasBar(hitPoints)
                ? " — "
                    + Math.max(0, hitPoints - hits)
                    + "/"
                    + hitPoints
                    + " HP"
                    + (hits > 0 ? " (heals at end of round)" : "")
                : "");
    // Flames and Steel (request 27): the aircraft's air phase values
    final String air =
        games.strategy.triplea.delegate.air.AirCombat.airValuesText(unitType, player);
    return firstLine
        + (air.isEmpty() ? "" : air + "<br />")
        + uiContext.getTooltipProperties().getTooltip(unitType, player);
  }

  /** The type's hit points for this owner, including hit point techs. */
  private static int hitPointsOf(final UnitType unitType, final @Nullable GamePlayer player) {
    final int base = unitType.getUnitAttachment().getHitPoints();
    return player == null
        ? base
        : base + unitType.getData().getTechTracker().getHitPointBonus(player, unitType);
  }

  /**
   * Updates the tooltip. The tooltip will show after 1s without another update if the text is not
   * empty.
   *
   * @param tipText The tooltip text to set or the empty string if it should be hidden.
   */
  public void updateTooltip(final String tipText) {
    if (tipText.equals(text)) {
      return;
    }

    if (popup != null) {
      popup.hide();
      popup = null;
    }
    text = tipText;

    timer.stop();
    if (text.length() > 0) {
      timer.restart();
    }
  }

  @Override
  public void actionPerformed(final ActionEvent e) {
    if (text != null && text.length() > 0 && window != null && window.isActive()) {
      final Point currentPoint = MouseInfo.getPointerInfo().getLocation();
      if (isPointWithinParentBounds(currentPoint)) {
        final PopupFactory popupFactory = PopupFactory.getSharedInstance();
        final JToolTip info = new JToolTip();
        info.setTipText("<html>" + text + "</html>");
        popup = popupFactory.getPopup(parent, info, currentPoint.x + 20, currentPoint.y - 20);
        popup.show();
      }
    }
  }

  private boolean isPointWithinParentBounds(final Point pointInScreenCoordinates) {
    final Point pointInParentCoordinates = new Point(pointInScreenCoordinates);
    SwingUtilities.convertPointFromScreen(pointInParentCoordinates, parent);
    return parent.getBounds().contains(pointInParentCoordinates);
  }
}
