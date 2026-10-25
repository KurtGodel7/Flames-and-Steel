package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.GameDataEvent;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.AirStageDelegate;
import games.strategy.triplea.delegate.air.AirBases;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.ui.panels.map.MapPanel;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.panels.map.MapOverlay;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;

/**
 * Flames and Steel (request 38): the air war on the map. Aircraft on bases are drawn by the map as
 * usual (on an airfield plate on land, see the units drawer). Aircraft in the air are kept in a
 * hidden territory the map never draws; this overlay draws them faded over the territory they are
 * over, with a "lands at round end" mark, and pointing at them says how much range they have left.
 *
 * <p>The air map mode (button and Ctrl+I, on by itself during the air phase and planes go home)
 * adds a tag over every territory where opposing aircraft meet ("Air battle"), and the nation
 * filter fades the other nations' aircraft. Air panels may show a banner along the top of the view
 * and flash a territory.
 */
public final class AirMapMode implements MapOverlay {
  private static final Color BANNER_LINE = new Color(255, 170, 40);
  private static final Color LANDS_TAG = new Color(200, 225, 255);
  private static final Color BATTLE_TAG = new Color(255, 120, 90);

  private final UiContext uiContext;
  private final @Nullable MapPanel mapPanel;
  private final Consumer<String> status;
  private final AirDraw draw;
  private GameData data;
  private boolean enabled;
  private boolean inAirPhase;
  private boolean stepKnown;
  private @Nullable GamePlayer filter;
  private final List<Consumer<Boolean>> modeListeners = new CopyOnWriteArrayList<>();
  // where airborne groups were drawn last, for pointing at them
  private final List<Drawn> drawn = new CopyOnWriteArrayList<>();
  private volatile @Nullable String banner;
  private volatile @Nullable Territory flashZone;
  private volatile long flashUntil;

  private record Drawn(Rectangle box, GamePlayer owner, Territory over, List<Unit> units) {}

  public AirMapMode(
      final UiContext uiContext,
      final @Nullable MapPanel mapPanel,
      final GameData data,
      final Consumer<String> status) {
    this.uiContext = uiContext;
    this.mapPanel = mapPanel;
    this.status = status;
    this.draw = new AirDraw(uiContext);
    setGameData(data);
    if (mapPanel != null) {
      mapPanel.addMapSelectionListener(
          new games.strategy.triplea.ui.DefaultMapSelectionListener() {
            @Override
            public void mouseMoved(
                final @Nullable Territory territory,
                final games.strategy.triplea.ui.MouseDetails md) {
              pointedAt(md.getMapPoint());
            }
          });
    }
  }

  /** Whether the game puts aircraft on the map (it has the hidden airborne territory). */
  public static boolean isUsed(final GameData data) {
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      return AirBases.usesBases(data);
    }
  }

  public void setGameData(final GameData newData) {
    data = newData;
    data.addGameDataEventListener(
        GameDataEvent.GAME_STEP_CHANGED, () -> SwingUtilities.invokeLater(this::stepChanged));
    data.addDataChangeListener(change -> repaint());
    stepKnown = false;
    stepChanged();
  }

  // ---- the view switch

  public boolean isEnabled() {
    return enabled;
  }

  /** The player's toggle; lasts until the air phase starts or ends. */
  public void toggle() {
    setEnabled(!enabled);
  }

  public void addModeListener(final Consumer<Boolean> listener) {
    modeListeners.add(listener);
    listener.accept(enabled);
  }

  private void setEnabled(final boolean value) {
    enabled = value;
    modeListeners.forEach(l -> l.accept(value));
    repaint();
  }

  private void stepChanged() {
    final boolean air;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      air = isAirStep(data.getSequence().getStep());
    }
    if (air != inAirPhase || !stepKnown) {
      stepKnown = true;
      inAirPhase = air;
      setEnabled(air);
    }
  }

  // the map XML's names for the air delegates
  private static final Set<String> AIR_DELEGATE_NAMES = Set.of("airCombat", "airStage");

  static boolean isAirStep(final @Nullable GameStep step) {
    if (step == null) {
      return false;
    }
    // request 18: the history view's copy of the game data has no delegates, so never fetch one
    // that may be missing; go by the delegate's name there
    final IDelegate delegate = step.getDelegateOptional().orElse(null);
    if (delegate != null) {
      return delegate instanceof AirCombatDelegate || delegate instanceof AirStageDelegate;
    }
    return AIR_DELEGATE_NAMES.contains(step.getDelegateName());
  }

  /** Fades every nation's aircraft but this one's (null shows all). */
  public void setFilter(final @Nullable GamePlayer nation) {
    filter = nation;
    repaint();
  }

  /** Kept for the air panels: a panel with a selection writes its own status. */
  public void setBusy(final boolean busy) {
    // the overlay reports only when the mouse is over aircraft in the air
  }

  /** Outlines the territory for a moment, so the player sees where the group is. */
  public void flash(final Territory zone) {
    flashZone = zone;
    flashUntil = System.currentTimeMillis() + 1600;
    repaint();
    final javax.swing.Timer timer = new javax.swing.Timer(1700, e -> repaint());
    timer.setRepeats(false);
    timer.start();
  }

  /** Shows (or with null hides) a banner along the top of the visible map. */
  public void setBanner(final @Nullable String text) {
    banner = text;
    repaint();
  }

  private void repaint() {
    if (mapPanel != null) {
      mapPanel.repaint();
    }
  }

  // ---- painting

  @Override
  public void paint(final Graphics2D g, final GameData paintData) {
    final List<Drawn> now = new ArrayList<>();
    try (GameData.Unlocker ignored = paintData.acquireReadLock()) {
      final Territory airborne = AirBases.airborne(paintData).orElse(null);
      if (airborne != null) {
        paintAirborne(g, airborne, now);
      }
    }
    drawn.clear();
    drawn.addAll(now);
    final Territory flash = flashZone;
    if (flash != null && System.currentTimeMillis() < flashUntil) {
      draw.outlineBox(g, flash, Color.YELLOW, 5f);
    }
    paintBanner(g);
  }

  /**
   * Every nation's aircraft in the air, faded over the territory they are over: one icon per
   * nation and type with its count, and "lands at round end" under them. In air map mode a
   * territory where enemies meet is tagged "Air battle".
   */
  private void paintAirborne(final Graphics2D g, final Territory airborne, final List<Drawn> out) {
    final Map<Territory, Map<String, List<Unit>>> byPlace = new LinkedHashMap<>();
    for (final Unit u : airborne.getUnits()) {
      if (u.getFlightOver() == null) {
        continue;
      }
      byPlace
          .computeIfAbsent(u.getFlightOver(), k -> new LinkedHashMap<>())
          .computeIfAbsent(u.getOwner().getName() + "|" + u.getType().getName(), k -> new ArrayList<>())
          .add(u);
    }
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    final int w = images.getUnitImageWidth();
    final int h = images.getUnitImageHeight();
    for (final Map.Entry<Territory, Map<String, List<Unit>>> e : byPlace.entrySet()) {
      final Territory over = e.getKey();
      final Point middle = draw.middle(over);
      final int groups = e.getValue().size();
      final int rowWidth = groups * (w + 18);
      int x = middle.x - rowWidth / 2;
      final int y = middle.y - h - 12;
      final List<GamePlayer> owners = new ArrayList<>();
      for (final List<Unit> units : e.getValue().values()) {
        final Unit first = units.get(0);
        if (!owners.contains(first.getOwner())) {
          owners.add(first.getOwner());
        }
        final boolean faded = filter != null && !filter.equals(first.getOwner());
        final Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, faded ? 0.25f : 0.6f));
        draw.iconAt(g, new Point(x, y), first.getOwner(), first.getType(), units.size());
        g.setComposite(old);
        out.add(new Drawn(new Rectangle(x - 2, y - 2, w + 4, h + 4), first.getOwner(), over, units));
        x += w + 18;
      }
      AirDraw.tag(g, "lands at round end", middle.x, y + h + 14, LANDS_TAG);
      if (enabled && hasBattle(owners)) {
        AirDraw.tag(g, "Air battle", middle.x, y - 14, BATTLE_TAG);
      }
    }
  }

  private static boolean hasBattle(final List<GamePlayer> owners) {
    for (final GamePlayer a : owners) {
      for (final GamePlayer b : owners) {
        if (AirCombat.atWar(a, b)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Pointing at aircraft in the air says what they are and how far they can still fly. */
  private void pointedAt(final Point mapPoint) {
    for (final Drawn d : drawn) {
      if (d.box().contains(mapPoint)) {
        final Unit first = d.units().get(0);
        final int least = d.units().stream().mapToInt(Unit::getFlightRangeLeft).min().orElse(0);
        status.accept(
            d.units().size()
                + " "
                + typeName(first.getType(), d.units().size())
                + " ("
                + games.strategy.triplea.formatter.PlayerNames.display(d.owner())
                + ") in the air over "
                + d.over().getName()
                + ": land at round end, "
                + least
                + (least == 1 ? " step" : " steps")
                + " of range left. In the air they can't be attacked and are no base.");
        return;
      }
    }
  }

  private static String typeName(final UnitType type, final int count) {
    final String name = AirCombat.unitName(type);
    return count == 1 ? name : name + "s";
  }

  private void paintBanner(final Graphics2D g) {
    final String text = banner;
    if (text == null) {
      return;
    }
    final Rectangle view = view();
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
    final FontMetrics fm = g.getFontMetrics();
    final int w = Math.min(view.width - 20, fm.stringWidth(text) + 30);
    final int h = fm.getHeight() + 14;
    final int x = view.x + (view.width - w) / 2;
    final int y = view.y + 12;
    g.setColor(new Color(0x1C, 0x1C, 0x1C, 230));
    g.fillRoundRect(x, y, w, h, 12, 12);
    g.setColor(BANNER_LINE);
    g.setStroke(new BasicStroke(3f));
    g.drawRoundRect(x, y, w, h, 12, 12);
    g.setColor(Color.WHITE);
    g.drawString(text, x + 15, y + 7 + fm.getAscent());
  }

  /** The visible part of the map, in map pixels (the whole map without a map panel). */
  private Rectangle view() {
    if (mapPanel != null && mapPanel.getWidth() > 0) {
      final double scale = mapPanel.getScale();
      return new Rectangle(
          mapPanel.getXOffset(),
          mapPanel.getYOffset(),
          (int) (mapPanel.getWidth() / scale),
          (int) (mapPanel.getHeight() / scale));
    }
    final java.awt.Dimension size = uiContext.getMapData().getMapDimensions();
    return new Rectangle(0, 0, size.width, size.height);
  }

  /** For tests. */
  List<Rectangle> airborneBoxes() {
    return drawn.stream().map(Drawn::box).toList();
  }
}
