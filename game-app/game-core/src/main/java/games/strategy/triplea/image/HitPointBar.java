package games.strategy.triplea.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/**
 * Flames and Steel: the segmented hit-point bar drawn under units with 2 or more hit points. One
 * segment per hit point, remaining hit points green and lost ones red (from the end), on a dark
 * backing. Up to 5 hit points fit in one row; 6 or more use two rows, the top row holding the
 * larger half, both rows left-aligned so the columns line up. Segments are equal whole pixels.
 */
public final class HitPointBar {
  public static final Color GREEN = new Color(0x46, 0xC8, 0x46);
  public static final Color RED = new Color(0xCD, 0x28, 0x1E);
  public static final Color BACKING = new Color(0x14, 0x14, 0x14);
  private static final int GAP = 1;
  private static final int BORDER = 1;
  private static final int MAX_ONE_ROW = 5;

  /** Row height at units.scale 1.0; 3 px at the map's 0.6. */
  private static final double ROW_HEIGHT_AT_SCALE_1 = 5.0;

  private HitPointBar() {}

  public static boolean hasBar(final int hitPoints) {
    return hitPoints >= 2;
  }

  /** Segments per row, top row first. */
  static int[] rows(final int hitPoints) {
    return hitPoints <= MAX_ONE_ROW
        ? new int[] {hitPoints}
        : new int[] {(hitPoints + 1) / 2, hitPoints / 2};
  }

  static int rowHeight(final double scale) {
    return Math.max(2, (int) Math.round(ROW_HEIGHT_AT_SCALE_1 * scale));
  }

  /** Width of one segment: equal whole pixels, the widest row (with its backing) fits the icon. */
  static int segmentWidth(final int iconWidth, final int hitPoints) {
    final int columns = rows(hitPoints)[0];
    return Math.max(1, (iconWidth - 2 * BORDER - (columns - 1) * GAP) / columns);
  }

  /** Total height of the bar including its backing, or 0 if the unit has no bar. */
  public static int height(final int hitPoints, final double scale) {
    if (!hasBar(hitPoints)) {
      return 0;
    }
    final int rowCount = rows(hitPoints).length;
    return rowCount * rowHeight(scale) + (rowCount - 1) * GAP + 2 * BORDER;
  }

  /**
   * Paints the bar centred under an icon.
   *
   * @param iconX left edge of the icon
   * @param top top edge of the bar (including its backing)
   * @param iconWidth width of the icon the bar sits under
   * @param hits hits taken
   */
  public static void paint(
      final Graphics2D g,
      final int iconX,
      final int top,
      final int iconWidth,
      final int hitPoints,
      final int hits,
      final double scale) {
    if (!hasBar(hitPoints)) {
      return;
    }
    final int[] rows = rows(hitPoints);
    final int seg = segmentWidth(iconWidth, hitPoints);
    final int rowHeight = rowHeight(scale);
    final int widest = rows[0] * seg + (rows[0] - 1) * GAP;
    final int left = iconX + (iconWidth - widest) / 2;
    final int remaining = Math.max(0, hitPoints - Math.max(0, hits));
    int index = 0;
    for (int r = 0; r < rows.length; r++) {
      final int y = top + BORDER + r * (rowHeight + GAP);
      final int width = rows[r] * seg + (rows[r] - 1) * GAP;
      g.setColor(BACKING);
      g.fillRect(left - BORDER, y - BORDER, width + 2 * BORDER, rowHeight + 2 * BORDER);
      for (int c = 0; c < rows[r]; c++) {
        g.setColor(index < remaining ? GREEN : RED);
        g.fillRect(left + c * (seg + GAP), y, seg, rowHeight);
        index++;
      }
    }
  }

  /**
   * The icon with the bar added underneath (the icon gets taller), or the icon itself if the unit
   * has no bar.
   */
  public static ImageIcon withBar(
      final ImageIcon icon, final int hitPoints, final int hits, final double scale) {
    if (!hasBar(hitPoints) || icon.getIconWidth() <= 0) {
      return icon;
    }
    final int barTop = icon.getIconHeight() + 1;
    final BufferedImage image =
        new BufferedImage(
            icon.getIconWidth(), barTop + height(hitPoints, scale), BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    g.drawImage(icon.getImage(), 0, 0, null);
    paint(g, 0, barTop, icon.getIconWidth(), hitPoints, hits, scale);
    g.dispose();
    return new ImageIcon(image);
  }
}
