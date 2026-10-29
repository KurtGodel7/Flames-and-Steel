package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.air.AirBattleReport;
import games.strategy.triplea.delegate.air.AirBattleReport.Row;
import games.strategy.triplea.delegate.air.AirBattleReport.Stack;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.PlayerNames;
import games.strategy.triplea.image.HitPointBar;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import org.triplea.swing.WrapLayout;

/**
 * Request 46: the "Air battles this round" window. One row per battle: the place, the attacker's
 * units and the defender's drawn with the map's unit images (destroyed ones greyed with a red X,
 * damaged ones with their hit-point bar, aborted bombers tagged, a bombed factory with its PU loss),
 * and a short line of what pictures can't say. Rows are grouped for the viewing nation into "Your
 * losses", "Allies' losses" and "Enemy losses", largest loss first, and tinted red where the
 * viewer's side lost more, green where the enemy did.
 */
public class AirBattleSummaryPanel extends JPanel implements Scrollable {
  private static final long serialVersionUID = 1L;
  public static final String YOUR_LOSSES = "Your losses";
  public static final String ALLIES_LOSSES = "Allies' losses";
  public static final String ENEMY_LOSSES = "Enemy losses";
  private static final Color LOSS = new Color(0xCD, 0x28, 0x1E);
  private static final Color GAIN = new Color(0x2E, 0xA0, 0x43);
  private static final Color CROSS = new Color(0xE0, 0x20, 0x20);
  // the window's width; rows of pictures and their notes wrap within it
  private static final int WIDTH = 760;

  private final transient GameData data;
  private final transient UiContext uiContext;
  private final transient @Nullable GamePlayer viewer;
  // section title -> its rows, in the order shown (for tests)
  private final Map<String, List<Row>> sections = new LinkedHashMap<>();

  public AirBattleSummaryPanel(
      final GameData data,
      final UiContext uiContext,
      final AirBattleReport report,
      final @Nullable GamePlayer viewer) {
    this.data = data;
    this.uiContext = uiContext;
    this.viewer = viewer;
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    sort(report.rows());
    if (viewer != null) {
      final JLabel who = label("Shown for " + PlayerNames.display(viewer), Font.ITALIC, 0);
      add(who);
      add(gap(6));
    }
    sections.forEach(
        (title, rows) -> {
          add(label(title, Font.BOLD, 4));
          add(gap(4));
          rows.forEach(
              r -> {
                add(rowPanel(r));
                add(gap(4));
              });
          add(gap(8));
        });
    if (!report.bombingTotals().isEmpty()) {
      final List<String> totals = new ArrayList<>();
      report
          .bombingTotals()
          .forEach((nation, loss) -> totals.add(nation + " " + MINUS + loss + " PUs"));
      add(label(text("Strategic bombing this round: " + String.join(", ", totals)), Font.BOLD, 0));
    }
  }

  private static final String MINUS = "−";

  /**
   * The nation the window is drawn for: the next nation to move that this screen plays (on one
   * computer, the nation whose turn is next), or else the next nation to move.
   */
  public static @Nullable GamePlayer viewer(
      final GameData data, final Predicate<GamePlayer> playedHere) {
    final List<GameStep> steps = new ArrayList<>();
    data.getSequence().forEach(steps::add);
    final int current = data.getSequence().getStepIndex();
    GamePlayer next = null;
    for (int i = 1; i <= steps.size(); i++) {
      final GamePlayer player = steps.get((current + i) % steps.size()).getPlayerId();
      if (player == null || player.isNull()) {
        continue;
      }
      if (playedHere.test(player)) {
        return player;
      }
      if (next == null) {
        next = player;
      }
    }
    return next;
  }

  /** The rows of each section, in the order shown. */
  public Map<String, List<Row>> getSections() {
    return sections;
  }

  private boolean ours(final String nation) {
    final GamePlayer p = data.getPlayerList().getPlayerId(nation);
    return viewer != null && p != null && AirCover.sameSide(viewer, p);
  }

  private void sort(final List<Row> rows) {
    if (viewer == null) {
      sections.put("Air battles", byLoss(rows, n -> true));
      return;
    }
    final List<Row> yours = new ArrayList<>();
    final List<Row> allies = new ArrayList<>();
    final List<Row> enemies = new ArrayList<>();
    for (final Row row : rows) {
      if (row.hurt().contains(viewer.getName())) {
        yours.add(row);
      } else if (row.hurt().stream().anyMatch(this::ours)) {
        allies.add(row);
      } else {
        enemies.add(row);
      }
    }
    put(YOUR_LOSSES, byLoss(yours, viewer.getName()::equals));
    put(ALLIES_LOSSES, byLoss(allies, n -> ours(n) && !n.equals(viewer.getName())));
    put(ENEMY_LOSSES, byLoss(enemies, n -> !ours(n)));
  }

  private void put(final String title, final List<Row> rows) {
    if (!rows.isEmpty()) {
      sections.put(title, rows);
    }
  }

  /** Largest PU loss of these nations first; then most hits; then by place. */
  private static List<Row> byLoss(final List<Row> rows, final Predicate<String> nations) {
    return rows.stream()
        .sorted(
            Comparator.comparingInt((Row r) -> -r.lossOf(nations))
                .thenComparingInt(r -> -r.hitsOf(nations))
                .thenComparing(Row::place))
        .toList();
  }

  /** Red where the viewer's side lost more, green where the enemy did, else none. */
  @Nullable
  Color tint(final Row row) {
    if (viewer == null) {
      return null;
    }
    final Predicate<String> own = this::ours;
    final int lossOwn = row.lossOf(own);
    final int lossEnemy = row.lossOf(own.negate());
    // equal PU losses (e.g. only damage): the side that took more hits lost more
    final int diff =
        lossOwn != lossEnemy
            ? Integer.compare(lossOwn, lossEnemy)
            : Integer.compare(row.hitsOf(own), row.hitsOf(own.negate()));
    return diff > 0 ? LOSS : diff < 0 ? GAIN : null;
  }

  private JPanel rowPanel(final Row row) {
    final Color tint = tint(row);
    final Color base = background();
    final JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    panel.setBackground(tint == null ? base : blend(base, tint, 0.18));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(tint == null ? blend(base, Color.GRAY, 0.5) : tint, 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    panel.add(label(text(row.place()), Font.BOLD, 1));
    final JPanel units = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    units.setOpaque(false);
    units.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.attackers().forEach(s -> units.add(stackLabel(s)));
    if (!row.attackers().isEmpty() && !row.defenders().isEmpty()) {
      final JLabel against = new JLabel("against");
      against.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
      units.add(against);
    }
    row.defenders().forEach(s -> units.add(stackLabel(s)));
    panel.add(units);
    // the notes wrap within the row's width
    final JLabel notes =
        new JLabel(
            "<html><div style='width:"
                + (WIDTH - 130)
                + "px'>"
                + escape(text(String.join("; ", row.notes())))
                + "</div></html>");
    notes.setAlignmentX(Component.LEFT_ALIGNMENT);
    panel.add(notes);
    return panel;
  }

  private static String escape(final String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static Color background() {
    final Color c = UIManager.getColor("Panel.background");
    return c == null ? Color.WHITE : c;
  }

  private static Color blend(final Color base, final Color over, final double amount) {
    return new Color(
        (int) Math.round(base.getRed() * (1 - amount) + over.getRed() * amount),
        (int) Math.round(base.getGreen() * (1 - amount) + over.getGreen() * amount),
        (int) Math.round(base.getBlue() * (1 - amount) + over.getBlue() * amount));
  }

  /** Display names for nations ("America Pacific"), as everywhere else in the fork. */
  private String text(final String raw) {
    return PlayerNames.inText(raw, data);
  }

  JLabel stackLabel(final Stack stack) {
    final GamePlayer owner = data.getPlayerList().getPlayerId(stack.owner());
    final UnitType type = data.getUnitTypeList().getUnitTypeOrThrow(stack.unitType());
    final JLabel label = new JLabel(stack.count() > 1 ? String.valueOf(stack.count()) : "");
    label.setIcon(icon(owner, type, stack));
    label.setHorizontalTextPosition(SwingConstants.RIGHT);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    label.setToolTipText(describe(owner, type, stack));
    return label;
  }

  /** "2 British strategic bombers, damaged, aborted". */
  String describe(final GamePlayer owner, final UnitType type, final Stack stack) {
    final List<String> parts = new ArrayList<>();
    parts.add(
        stack.count()
            + " "
            + AirCombat.adjective(owner)
            + " "
            + MyFormatter.pluralize(AirCombat.unitName(type), stack.count()));
    if (stack.destroyed()) {
      parts.add("destroyed");
    } else if (stack.hits() > 0) {
      parts.add(stack.hits() + MyFormatter.pluralize(" hit", stack.hits()) + " taken");
    }
    if (stack.aborted()) {
      parts.add("aborted");
    }
    if (stack.bombed() > 0) {
      parts.add("bombed: " + PlayerNames.display(owner) + " " + MINUS + stack.bombed() + " PUs");
    }
    return String.join(", ", parts);
  }

  private ImageIcon icon(final GamePlayer owner, final UnitType type, final Stack stack) {
    final ImageIcon plain =
        uiContext
            .getUnitImageFactory()
            .getIcon(ImageKey.builder().player(owner).type(type).build());
    final int hitPoints = type.getUnitAttachment().getHitPoints();
    final ImageIcon base =
        stack.destroyed()
            ? plain
            : HitPointBar.withBar(
                plain, hitPoints, stack.hits(), uiContext.getUnitImageFactory().getScaleFactor());
    final int width = Math.max(1, base.getIconWidth());
    final int height = Math.max(1, base.getIconHeight());
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    if (stack.destroyed()) {
      g.drawImage(greyed(base), 0, 0, null);
      g.setColor(CROSS);
      g.setStroke(new BasicStroke(Math.max(2f, width / 14f), BasicStroke.CAP_ROUND, 0));
      final int m = Math.max(2, width / 8);
      g.drawLine(m, m, width - m, height - m);
      g.drawLine(width - m, m, m, height - m);
    } else {
      g.drawImage(base.getImage(), 0, 0, null);
    }
    int tagTop = 0;
    if (stack.bombed() > 0) {
      tagTop = tag(g, MINUS + stack.bombed() + " PU", LOSS, width, tagTop);
    }
    if (stack.aborted()) {
      tag(g, "aborted", new Color(0x60, 0x60, 0x60), width, tagTop);
    }
    g.dispose();
    return new ImageIcon(image);
  }

  /** The icon in faded grey (drawn at once, unlike GrayFilter's image, which loads later). */
  private static BufferedImage greyed(final ImageIcon icon) {
    final int width = Math.max(1, icon.getIconWidth());
    final int height = Math.max(1, icon.getIconHeight());
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    g.drawImage(icon.getImage(), 0, 0, null);
    g.dispose();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int argb = image.getRGB(x, y);
        final int alpha = (argb >>> 24) * 3 / 4;
        final int grey =
            (((argb >> 16) & 0xFF) * 30 + ((argb >> 8) & 0xFF) * 59 + (argb & 0xFF) * 11) / 100;
        final int faded = 96 + grey * 5 / 8;
        image.setRGB(x, y, (alpha << 24) | (faded << 16) | (faded << 8) | faded);
      }
    }
    return image;
  }

  /** A small rounded tag at the top of the icon; returns where the next tag goes. */
  private static int tag(
      final Graphics2D g, final String text, final Color color, final int width, final int top) {
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
    final FontMetrics fm = g.getFontMetrics();
    final int w = Math.min(width, fm.stringWidth(text) + 6);
    final int h = fm.getHeight();
    g.setColor(color);
    g.fillRoundRect(0, top, w, h, 6, 6);
    g.setColor(Color.WHITE);
    g.drawString(text, 3, top + fm.getAscent());
    return top + h + 1;
  }

  private static JLabel label(final String text, final int style, final int bigger) {
    final JLabel label = new JLabel(text);
    label.setFont(label.getFont().deriveFont(style, label.getFont().getSize2D() + bigger));
    label.setAlignmentX(Component.LEFT_ALIGNMENT);
    // room for the last letter of bold and italic text
    label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
    return label;
  }

  private static Component gap(final int height) {
    final JPanel gap = new JPanel();
    gap.setOpaque(false);
    gap.setAlignmentX(Component.LEFT_ALIGNMENT);
    gap.setPreferredSize(new Dimension(1, height));
    gap.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    return gap;
  }

  // rows fit the window's width (pictures wrap); the window scrolls down

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return new Dimension(WIDTH, Math.min(600, getPreferredSize().height));
  }

  @Override
  public int getScrollableUnitIncrement(
      final Rectangle visibleRect, final int orientation, final int direction) {
    return 24;
  }

  @Override
  public int getScrollableBlockIncrement(
      final Rectangle visibleRect, final int orientation, final int direction) {
    return Math.max(24, visibleRect.height - 24);
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return false;
  }
}
