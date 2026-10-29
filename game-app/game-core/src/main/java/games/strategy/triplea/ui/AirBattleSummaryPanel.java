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
import java.awt.AlphaComposite;
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
 * Request 46: the "Air battles this round" window. One row per battle: the place, then each side's
 * units behind their nation's small flag (request 47), drawn with the map's unit images: destroyed
 * ones faded in their own colours with a red X, damaged ones with their hit-point bar, aborted
 * bombers tagged, a bombed factory with its PU loss. A short line holds only what pictures can't
 * show. Rows are grouped for the viewing nation: "Your losses" (it lost a unit or PUs; damage alone
 * is not a loss), "Enemy losses", "Allies' losses", "Neutral losses" and "No losses"; within each,
 * battles where the viewer's units were damaged come first (with a badge), then the largest loss.
 * Tinted red where the viewer's side lost more, green where the enemy did.
 */
public class AirBattleSummaryPanel extends JPanel implements Scrollable {
  private static final long serialVersionUID = 1L;
  public static final String YOUR_LOSSES = "Your losses";
  public static final String ENEMY_LOSSES = "Enemy losses";
  public static final String ALLIES_LOSSES = "Allies' losses";
  public static final String NEUTRAL_LOSSES = "Neutral losses";
  public static final String NO_LOSSES = "No losses";
  /**
   * Request 47: optional game property naming neutral garrisons, "Ireland=Irish,Andalusia=Spanish";
   * a territory not named is "Ireland garrison".
   */
  public static final String NEUTRAL_NAMES = "FS Neutral Garrison Names";

  private static final Color LOSS = new Color(0xCD, 0x28, 0x1E);
  private static final Color GAIN = new Color(0x2E, 0xA0, 0x43);
  private static final Color CROSS = new Color(0xE0, 0x20, 0x20);
  // the window's width; rows of pictures and their notes wrap within it
  private static final int WIDTH = 760;
  private static final String MINUS = "−";

  private final transient GameData data;
  private final transient UiContext uiContext;
  private final transient @Nullable GamePlayer viewer;
  // section title -> its rows, in the order shown (for tests)
  private final Map<String, List<Row>> sections = new LinkedHashMap<>();
  // request 47: the damage badges shown, by place (for tests)
  private final Map<String, String> badges = new LinkedHashMap<>();
  private int maxViewportHeight = 600;

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
      add(label("Shown for " + PlayerNames.display(viewer), Font.ITALIC, 0));
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
          .forEach((nation, loss) -> totals.add(nation + " " + MINUS + loss + " PU"));
      add(label(text("Strategic bombing this round: " + String.join(", ", totals)), Font.BOLD, 0));
    }
  }

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

  /** Request 47: the damage badge of each row that has one, by place. */
  public Map<String, String> getBadges() {
    return badges;
  }

  /** Request 47: the tallest the scrolling area may be (the window must fit the screen). */
  public void setMaxViewportHeight(final int height) {
    maxViewportHeight = Math.max(120, height);
  }

  private @Nullable GamePlayer player(final String nation) {
    return data.getPlayerList().getPlayerId(nation);
  }

  private boolean neutral(final String nation) {
    final GamePlayer p = player(nation);
    return p == null || p.isNull();
  }

  private boolean ours(final String nation) {
    final GamePlayer p = player(nation);
    return viewer != null && p != null && !p.isNull() && AirCover.sameSide(viewer, p);
  }

  private boolean enemy(final String nation) {
    return !neutral(nation) && !ours(nation);
  }

  private void sort(final List<Row> rows) {
    if (viewer == null) {
      sections.put("Air battles", order(rows, n -> true));
      return;
    }
    final Map<String, List<Row>> by = new LinkedHashMap<>();
    for (final String title :
        List.of(YOUR_LOSSES, ENEMY_LOSSES, ALLIES_LOSSES, NEUTRAL_LOSSES, NO_LOSSES)) {
      by.put(title, new ArrayList<>());
    }
    for (final Row row : rows) {
      // the nations that lost a unit or PUs here (damage alone is not a loss)
      final java.util.Set<String> lost = row.lossValue().keySet();
      final String title;
      if (lost.contains(viewer.getName())) {
        title = YOUR_LOSSES;
      } else if (lost.stream().anyMatch(this::enemy)) {
        title = ENEMY_LOSSES;
      } else if (lost.stream().anyMatch(this::ours)) {
        title = ALLIES_LOSSES;
      } else if (lost.stream().anyMatch(this::neutral)) {
        title = NEUTRAL_LOSSES;
      } else {
        title = NO_LOSSES;
      }
      by.get(title).add(row);
    }
    final Map<String, Predicate<String>> whose =
        Map.of(
            YOUR_LOSSES, viewer.getName()::equals,
            ENEMY_LOSSES, this::enemy,
            ALLIES_LOSSES, n -> ours(n) && !n.equals(viewer.getName()),
            NEUTRAL_LOSSES, this::neutral,
            NO_LOSSES, n -> true);
    by.forEach(
        (title, list) -> {
          if (!list.isEmpty()) {
            sections.put(title, order(list, whose.get(title)));
          }
        });
  }

  /** Battles where the viewer's units were damaged first; then the largest loss; then by place. */
  private List<Row> order(final List<Row> rows, final Predicate<String> nations) {
    return rows.stream()
        .sorted(
            Comparator.comparingInt((Row r) -> damagedOfViewer(r) > 0 ? 0 : 1)
                .thenComparingInt(r -> -r.lossOf(nations))
                .thenComparingInt(r -> -r.hitsOf(nations))
                .thenComparing(Row::place))
        .toList();
  }

  /** Request 47: how many of the viewer's units here were damaged and survived. */
  int damagedOfViewer(final Row row) {
    if (viewer == null) {
      return 0;
    }
    final List<Stack> all = new ArrayList<>(row.attackers());
    all.addAll(row.defenders());
    return all.stream()
        .filter(s -> s.owner().equals(viewer.getName()) && !s.destroyed() && s.hits() > 0)
        .mapToInt(Stack::count)
        .sum();
  }

  /** Red where the viewer's side lost more, green where the enemy did, else none. */
  @Nullable
  Color tint(final Row row) {
    if (viewer == null) {
      return null;
    }
    final int lossOwn = row.lossOf(this::ours);
    final int lossEnemy = row.lossOf(this::enemy);
    // equal PU losses (e.g. only damage): the side that took more hits lost more
    final int diff =
        lossOwn != lossEnemy
            ? Integer.compare(lossOwn, lossEnemy)
            : Integer.compare(row.hitsOf(this::ours), row.hitsOf(this::enemy));
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
    final JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    title.setOpaque(false);
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    title.add(label(text(row.place()), Font.BOLD, 1));
    // request 47: damage is never hidden: a badge in the viewer's colour
    final int damaged = damagedOfViewer(row);
    if (damaged > 0 && viewer != null) {
      final String badge = damaged + " of yours damaged";
      badges.put(row.place(), badge);
      title.add(badge(badge, uiContext.getMapData().getPlayerColor(viewer.getName())));
    }
    panel.add(title);
    final JPanel units = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    units.setOpaque(false);
    units.setAlignmentX(Component.LEFT_ALIGNMENT);
    addSide(units, row.attackers(), row);
    if (!row.attackers().isEmpty() && !row.defenders().isEmpty()) {
      final JLabel against = new JLabel("against");
      against.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
      units.add(against);
    }
    addSide(units, row.defenders(), row);
    panel.add(units);
    // request 47: only what pictures can't show, each part kept whole, wrapping to the width
    if (!row.notes().isEmpty()) {
      final JPanel notes = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 0));
      notes.setOpaque(false);
      notes.setAlignmentX(Component.LEFT_ALIGNMENT);
      for (int i = 0; i < row.notes().size(); i++) {
        notes.add(new JLabel(text(row.notes().get(i)) + (i < row.notes().size() - 1 ? "  ·  " : "")));
      }
      panel.add(notes);
    }
    return panel;
  }

  /** Request 47: each nation's units behind its small flag (a neutral garrison by name). */
  private void addSide(final JPanel units, final List<Stack> side, final Row row) {
    String owner = null;
    for (final Stack s : side) {
      if (!s.owner().equals(owner)) {
        owner = s.owner();
        units.add(nationLabel(owner, row.place()));
      }
      units.add(stackLabel(s, row.place()));
    }
  }

  private JLabel nationLabel(final String nation, final String place) {
    final GamePlayer p = player(nation);
    if (p == null || p.isNull()) {
      final JLabel name = new JLabel(garrisonName(place) + ":");
      name.setFont(name.getFont().deriveFont(Font.ITALIC));
      return name;
    }
    final JLabel flag = new JLabel(new ImageIcon(uiContext.getFlagImageFactory().getSmallFlag(p)));
    flag.setToolTipText(PlayerNames.display(p));
    return flag;
  }

  /** Request 47: "Irish garrison" (from the game property), else "Ireland garrison". */
  String garrisonName(final String place) {
    final String names;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      names = data.getProperties().get(NEUTRAL_NAMES, "");
    }
    for (final String pair : names.split("[,;]")) {
      final String[] kv = pair.split("=", 2);
      if (kv.length == 2 && kv[0].trim().equals(place)) {
        return kv[1].trim() + " garrison";
      }
    }
    return place + " garrison";
  }

  private static JLabel badge(final String text, final @Nullable Color color) {
    final Color back = color == null ? Color.GRAY : color;
    final JLabel badge = new JLabel(" " + text + " ");
    badge.setOpaque(true);
    badge.setBackground(back);
    // dark text on a light colour, white on a dark one
    final double light =
        (0.299 * back.getRed() + 0.587 * back.getGreen() + 0.114 * back.getBlue()) / 255;
    badge.setForeground(light > 0.6 ? Color.BLACK : Color.WHITE);
    badge.setFont(badge.getFont().deriveFont(Font.BOLD, badge.getFont().getSize2D() - 1f));
    badge.setBorder(BorderFactory.createEmptyBorder(1, 3, 1, 3));
    return badge;
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

  JLabel stackLabel(final Stack stack, final String place) {
    final GamePlayer owner = data.getPlayerList().getPlayerId(stack.owner());
    final UnitType type = data.getUnitTypeList().getUnitTypeOrThrow(stack.unitType());
    final JLabel label = new JLabel(stack.count() > 1 ? String.valueOf(stack.count()) : "");
    label.setIcon(icon(owner, type, stack));
    label.setHorizontalTextPosition(SwingConstants.RIGHT);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    label.setToolTipText(describe(owner, type, stack, place));
    return label;
  }

  /** "2 British strategic bombers, destroyed" or "1 Irish garrison infantry, destroyed". */
  String describe(
      final GamePlayer owner, final UnitType type, final Stack stack, final String place) {
    final List<String> parts = new ArrayList<>();
    final String nation = owner.isNull() ? garrisonName(place) : AirCombat.adjective(owner);
    parts.add(
        stack.count()
            + " "
            + nation
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
      parts.add("bombed: " + MINUS + stack.bombed() + " PU");
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
      // request 47: faded in its own colours (a destroyed British bomber is still British)
      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
      g.drawImage(base.getImage(), 0, 0, null);
      g.setComposite(AlphaComposite.SrcOver);
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

  // rows fit the window's width (pictures and notes wrap); the window scrolls down

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return new Dimension(WIDTH, Math.min(maxViewportHeight, getPreferredSize().height));
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
