package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.util.UnitSeparator;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Flames and Steel: drawing helpers for the air phase overlays, in map coordinates. */
final class AirDraw {
  static final Color LEGAL_FILL = new Color(80, 220, 110, 60);
  static final Color LEGAL_LINE = new Color(70, 230, 110);
  static final Color ILLEGAL_FILL = new Color(0, 0, 0, 120);
  static final Color ORIGIN_LINE = new Color(255, 150, 30);
  static final Color PENDING_LINE = new Color(255, 150, 30);
  private static final float[] DASH = {10f, 7f};

  private final UiContext uiContext;

  /** Next free unit spot per zone while one overlay paints. */
  private final Map<Territory, Integer> nextSlot = new HashMap<>();

  AirDraw(final UiContext uiContext) {
    this.uiContext = uiContext;
  }

  MapData mapData() {
    return uiContext.getMapData();
  }

  /** Call at the start of each paint: ghost stacks start after the units already drawn. */
  void resetSlots() {
    nextSlot.clear();
  }

  void fillBox(final Graphics2D g, final Territory zone, final Color fill) {
    g.setColor(fill);
    for (final Polygon p : mapData().getPolygons(zone)) {
      g.fillPolygon(p);
    }
  }

  void outlineBox(final Graphics2D g, final Territory zone, final Color color, final float width) {
    AirZoneOverlay.outline(g, mapData(), zone, color, width);
  }

  /**
   * Draws a faded stack of unit icons with a count in the next free spot of the zone, outlined to
   * show it is not there yet. Returns the centre of the stack.
   */
  Point ghostStack(
      final Graphics2D g,
      final GameData data,
      final Territory zone,
      final GamePlayer owner,
      final UnitType type,
      final int count,
      final Color outline,
      final boolean dashed) {
    final List<Point> spots = mapData().getPlacementPoints(zone);
    final int slot =
        nextSlot.computeIfAbsent(
            zone, z -> UnitSeparator.getSortedUnitCategories(z.getUnits(), data, mapData()).size());
    nextSlot.put(zone, slot + 1);
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    final int w = images.getUnitImageWidth();
    final int h = images.getUnitImageHeight();
    final Point spot;
    if (spots.isEmpty()) {
      final Point c = mapData().getCenter(zone);
      spot = new Point(c.x + slot * (w + 4), c.y);
    } else if (slot < spots.size()) {
      spot = spots.get(slot);
    } else {
      final Point last = spots.get(spots.size() - 1);
      spot = new Point(last.x, last.y + (slot - spots.size() + 1) * (h + 4));
    }
    final Image image = images.getImage(ImageKey.builder().player(owner).type(type).build());
    g.setColor(new Color(255, 255, 255, 70));
    g.fillRoundRect(spot.x - 1, spot.y - 1, w + 2, h + 2, 6, 6);
    final Composite old = g.getComposite();
    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
    g.drawImage(image, spot.x, spot.y, null);
    g.setComposite(old);
    final Stroke oldStroke = g.getStroke();
    g.setColor(outline);
    g.setStroke(
        dashed
            ? new BasicStroke(
                2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 3f}, 0f)
            : new BasicStroke(2f));
    g.drawRoundRect(spot.x - 1, spot.y - 1, w + 2, h + 2, 6, 6);
    g.setStroke(oldStroke);
    if (count > 1) {
      countLabel(g, String.valueOf(count), spot.x + w, spot.y + h);
    }
    return new Point(spot.x + w / 2, spot.y + h / 2);
  }

  /** A unit icon with a count and a light backing, its top left corner at the point. */
  void iconAt(
      final Graphics2D g,
      final Point topLeft,
      final GamePlayer owner,
      final UnitType type,
      final int count) {
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    final int w = images.getUnitImageWidth();
    final int h = images.getUnitImageHeight();
    g.setColor(new Color(255, 255, 255, 150));
    g.fillRoundRect(topLeft.x - 2, topLeft.y - 2, w + 4, h + 4, 8, 8);
    g.setColor(Color.BLACK);
    g.drawRoundRect(topLeft.x - 2, topLeft.y - 2, w + 4, h + 4, 8, 8);
    g.drawImage(
        images.getImage(ImageKey.builder().player(owner).type(type).build()),
        topLeft.x,
        topLeft.y,
        null);
    countLabel(g, String.valueOf(count), topLeft.x + w + 2, topLeft.y + h + 2);
  }

  /** White number with a dark outline, its bottom right corner at (x, y). */
  static void countLabel(final Graphics2D g, final String text, final int x, final int y) {
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    final FontMetrics fm = g.getFontMetrics();
    final int tx = x - fm.stringWidth(text);
    final int ty = y;
    g.setColor(Color.BLACK);
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        g.drawString(text, tx + dx, ty + dy);
      }
    }
    g.setColor(Color.WHITE);
    g.drawString(text, tx, ty);
  }

  /** A small label on a dark rounded background, centred on (x, y). */
  static void tag(
      final Graphics2D g, final String text, final int x, final int y, final Color color) {
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    final FontMetrics fm = g.getFontMetrics();
    final int w = fm.stringWidth(text) + 10;
    final int h = fm.getHeight();
    g.setColor(new Color(0, 0, 0, 185));
    g.fillRoundRect(x - w / 2, y - h / 2, w, h, 8, 8);
    g.setColor(color);
    g.drawString(text, x - w / 2 + 5, y - h / 2 + fm.getAscent());
  }

  /** The point where an arrow leaves a box: its centre. */
  Point center(final Territory t) {
    return mapData().getCenter(t);
  }

  /** The middle of a territory's bounding box (for targets drawn on the map). */
  Point middle(final Territory t) {
    final Rectangle r = mapData().getBoundingRect(t);
    return new Point((int) r.getCenterX(), (int) r.getCenterY());
  }

  /**
   * A curved arrow, bulging upwards, from one point to another, with an optional label at its
   * middle.
   */
  static void arrow(
      final Graphics2D g,
      final Point from,
      final Point to,
      final Color color,
      final float width,
      final boolean dashed,
      final String label) {
    arrow(g, from, to, color, width, dashed, label, 0);
  }

  /** As above, stopping {@code gap} short of the end point (e.g. at the edge of an icon). */
  static void arrow(
      final Graphics2D g,
      final Point from,
      final Point target,
      final Color color,
      final float width,
      final boolean dashed,
      final String label,
      final int gap) {
    final double tdx = target.x - from.x;
    final double tdy = target.y - from.y;
    final double tlength = Math.max(1, Math.hypot(tdx, tdy));
    final double tbulge = Math.min(260, tlength * 0.3);
    final double cx = (from.x + target.x) / 2.0;
    final double cy = (from.y + target.y) / 2.0 - tbulge;
    final double back = Math.max(1, Math.hypot(target.x - cx, target.y - cy));
    final Point to =
        new Point(
            (int) Math.round(target.x - gap * (target.x - cx) / back),
            (int) Math.round(target.y - gap * (target.y - cy) / back));
    final double dx = to.x - from.x;
    final double dy = to.y - from.y;
    final double length = Math.max(1, Math.hypot(dx, dy));
    final double bulge = Math.min(260, length * 0.3);
    final Point2D.Double control =
        new Point2D.Double((from.x + to.x) / 2.0, (from.y + to.y) / 2.0 - bulge);
    final QuadCurve2D curve =
        new QuadCurve2D.Double(from.x, from.y, control.x, control.y, to.x, to.y);
    final Stroke old = g.getStroke();
    g.setColor(new Color(0, 0, 0, 150));
    g.setStroke(stroke(width + 3, dashed));
    g.draw(curve);
    g.setColor(color);
    g.setStroke(stroke(width, dashed));
    g.draw(curve);
    g.setStroke(old);
    // arrow head along the curve's last tangent
    final double angle = Math.atan2(to.y - control.y, to.x - control.x);
    final double head = 10 + 2.5 * width;
    final Polygon tip = new Polygon();
    tip.addPoint(to.x, to.y);
    tip.addPoint(
        (int) Math.round(to.x - head * Math.cos(angle - 0.45)),
        (int) Math.round(to.y - head * Math.sin(angle - 0.45)));
    tip.addPoint(
        (int) Math.round(to.x - head * Math.cos(angle + 0.45)),
        (int) Math.round(to.y - head * Math.sin(angle + 0.45)));
    g.setColor(Color.BLACK);
    g.setStroke(new BasicStroke(1.5f));
    g.fillPolygon(tip);
    g.setColor(color);
    final Polygon inner = new Polygon();
    for (int i = 0; i < 3; i++) {
      inner.addPoint(
          (int) Math.round(to.x + (tip.xpoints[i] - to.x) * 0.8 - 2 * Math.cos(angle)),
          (int) Math.round(to.y + (tip.ypoints[i] - to.y) * 0.8 - 2 * Math.sin(angle)));
    }
    g.fillPolygon(inner);
    if (!label.isEmpty()) {
      // the curve's midpoint
      final int mx = (int) Math.round(0.25 * from.x + 0.5 * control.x + 0.25 * to.x);
      final int my = (int) Math.round(0.25 * from.y + 0.5 * control.y + 0.25 * to.y);
      tag(g, label, mx, my, Color.WHITE);
    }
  }

  private static Stroke stroke(final float width, final boolean dashed) {
    return dashed
        ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, DASH, 0f)
        : new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
  }
}
