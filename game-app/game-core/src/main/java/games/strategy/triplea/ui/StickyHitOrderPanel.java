package games.strategy.triplea.ui;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.casualty.StickyHits;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.UnitNames;
import games.strategy.triplea.image.HitPointBar;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

/**
 * Flames and Steel sticky hits (request 23): the casualty choice as an order of units. Hits go down
 * the list; each unit takes hits until it is destroyed, then the next one starts, so only the last
 * unit hit can be left damaged. Units damaged earlier are locked at the head of the list. The
 * player drags rows to reorder, or clicks units in the order they should take hits; every row says
 * in plain words what will happen to it, with its hit-point bar.
 */
public class StickyHitOrderPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final int ICON_HEIGHT = 34;
  private static final int SEGMENT_WIDTH = 13;
  private static final int SEGMENT_HEIGHT = 9;

  /** Hit points lost before these hits. */
  static final Color LOST_EARLIER = new Color(0x6E, 0x22, 0x1C);

  private final int hits;
  private final List<Unit> locked;
  private final List<Unit> defaultOrder;
  private final Map<Unit, Collection<Unit>> dependents;
  @Nullable private final UiContext uiContext;
  private final Map<String, Icon> icons = new LinkedHashMap<>();

  private List<Unit> order;

  /** Where the next clicked unit goes; units clicked since the last reset. */
  private int clickCursor;

  private final Set<Unit> clicked = new HashSet<>();
  private final DefaultListModel<Row> model = new DefaultListModel<>();
  private final RowList list = new RowList(model);
  private final JLabel resultLabel = new JLabel();
  private final JLabel clickLabel = new JLabel();

  /**
   * @param units the units that can be hit, in the default order (the units damaged earlier are
   *     moved to the head of it)
   */
  public StickyHitOrderPanel(
      final List<Unit> units,
      final Map<Unit, Collection<Unit>> dependents,
      final int hits,
      final String title,
      final GamePlayer player,
      @Nullable final UiContext uiContext) {
    this.hits = hits;
    this.dependents = dependents;
    this.uiContext = uiContext;
    this.locked = StickyHits.lockedFirst(units);
    this.defaultOrder = StickyHits.damagedFirst(units);
    this.order = new ArrayList<>(defaultOrder);
    this.clickCursor = locked.size();

    setLayout(new BorderLayout(0, 6));
    setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

    final JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    final JLabel titleLabel = new JLabel(html(title));
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
    top.add(titleLabel);
    top.add(Box.createVerticalStrut(4));
    top.add(
        new JLabel(
            html(
                player.getName()
                    + " take "
                    + hits
                    + MyFormatter.pluralize(" hit", hits)
                    + ". Hits go down this list: each unit takes hits until it is destroyed, "
                    + "and only then does the next one start.")));
    top.add(
        new JLabel(
            html(
                "Drag a row to move it, or click units in the order they should take hits."
                    + (locked.isEmpty() ? "" : " Units damaged earlier must go first."))));
    add(top, BorderLayout.NORTH);

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new RowRenderer());
    list.setFocusable(false);
    final RowMouse mouse = new RowMouse();
    list.addMouseListener(mouse);
    list.addMouseMotionListener(mouse);
    add(list, BorderLayout.CENTER);

    final JPanel bottom = new JPanel();
    bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
    resultLabel.setFont(resultLabel.getFont().deriveFont(Font.BOLD));
    bottom.add(resultLabel);
    bottom.add(Box.createVerticalStrut(4));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final JButton reset = new JButton("Default order");
    reset.setToolTipText("Put the units back in the suggested order and start clicking afresh");
    reset.addActionListener(e -> resetOrder());
    buttons.add(reset);
    buttons.add(Box.createHorizontalStrut(10));
    buttons.add(clickLabel);
    buttons.setAlignmentX(LEFT_ALIGNMENT);
    bottom.add(buttons);
    bottom.add(Box.createVerticalStrut(4));
    bottom.add(legend());
    add(bottom, BorderLayout.SOUTH);
    for (final Component c : top.getComponents()) {
      ((JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
    }
    for (final Component c : bottom.getComponents()) {
      ((JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
    }
    refresh();
  }

  /** The units in the order they take hits, the locked ones first. */
  public List<Unit> getOrder() {
    return List.copyOf(order);
  }

  /** What each unit hit gets, in order. */
  public List<StickyHits.Allocation> getAllocations() {
    return StickyHits.allocate(order, hits);
  }

  /** The casualties for the chosen order, as the engine lists them. */
  public CasualtyDetails getCasualtyDetails() {
    final CasualtyList casualties = StickyHits.toCasualtyList(getAllocations());
    return new CasualtyDetails(casualties.getKilled(), casualties.getDamaged(), false);
  }

  /** The plain-words result line under the list. */
  public String getResultText() {
    return resultLabel.getText();
  }

  /** The rows as shown: one per unit, or per run of identical units with the same result. */
  List<Row> getRows() {
    final List<Row> rows = new ArrayList<>();
    for (int i = 0; i < model.size(); i++) {
      rows.add(model.get(i));
    }
    return rows;
  }

  /** Puts the unit next in the click order (after the units clicked so far). */
  public void clickUnit(final Unit unit) {
    if (locked.contains(unit) || clicked.contains(unit)) {
      return;
    }
    order.remove(unit);
    order.add(clickCursor, unit);
    clicked.add(unit);
    clickCursor++;
    refresh();
  }

  /**
   * Moves the units of a row so they start where the target row starts now (or at the end).
   *
   * @param targetRow index of the row to insert before, or the row count for the end
   */
  public void moveRow(final int rowIndex, final int targetRow) {
    final Row row = model.get(rowIndex);
    if (row.locked || targetRow == rowIndex || targetRow == rowIndex + 1) {
      return;
    }
    final Unit before = targetRow < model.size() ? model.get(targetRow).units.get(0) : null;
    order.removeAll(row.units);
    int insertAt = before == null ? order.size() : order.indexOf(before);
    insertAt = Math.max(insertAt, locked.size());
    order.addAll(insertAt, row.units);
    // a drag starts a fresh click order after it
    clicked.clear();
    clickCursor = locked.size();
    refresh();
  }

  /** Back to the suggested order; clicking starts afresh. */
  public void resetOrder() {
    order = new ArrayList<>(defaultOrder);
    clicked.clear();
    clickCursor = locked.size();
    refresh();
  }

  private void refresh() {
    final List<StickyHits.Allocation> allocations = getAllocations();
    final Map<Unit, StickyHits.Allocation> byUnit = new LinkedHashMap<>();
    allocations.forEach(a -> byUnit.put(a.unit(), a));
    model.clear();
    int position = 1;
    Row current = null;
    for (final Unit unit : order) {
      final StickyHits.Allocation allocation = byUnit.get(unit);
      final int taken = allocation == null ? 0 : allocation.hitsTaken();
      final boolean killed = allocation != null && allocation.killed();
      final boolean isLocked = locked.contains(unit);
      final boolean isClicked = clicked.contains(unit);
      if (current != null && current.accepts(unit, taken, killed, isLocked, isClicked)) {
        current.units.add(unit);
      } else {
        current =
            new Row(
                unit,
                taken,
                killed,
                isLocked,
                isClicked,
                position,
                dependents.getOrDefault(unit, List.of()));
        model.addElement(current);
      }
      position++;
    }
    resultLabel.setText(html(resultText(allocations)));
    clickLabel.setText(
        clicked.isEmpty()
            ? "Click a unit to make it the first to take hits."
            : "Next click goes to place " + (clickCursor + 1) + ".");
    revalidate();
    repaint();
  }

  private String resultText(final List<StickyHits.Allocation> allocations) {
    final Map<String, Integer> lost = new LinkedHashMap<>();
    final List<String> parts = new ArrayList<>();
    int used = 0;
    for (final StickyHits.Allocation a : allocations) {
      used += a.hitsTaken();
      if (a.killed()) {
        lost.merge(fate(a.unit()) + "|" + name(a.unit().getType()), 1, Integer::sum);
      }
    }
    lost.forEach(
        (key, count) -> {
          final String[] fateAndName = key.split("\\|", 2);
          parts.add(
              count + " " + MyFormatter.pluralize(fateAndName[1], count) + " " + fateAndName[0]);
        });
    allocations.stream()
        .filter(a -> !a.killed())
        .forEach(
            a ->
                parts.add(
                    "1 "
                        + name(a.unit().getType())
                        + " damaged, "
                        + a.hitPointsLeftAfter()
                        + " HP left"));
    return (parts.isEmpty() ? "No units hit" : String.join("; ", parts))
        + ". ("
        + used
        + MyFormatter.pluralize(" hit", used)
        + " allocated, "
        + (hits - used)
        + " left)";
  }

  private JComponent legend() {
    final JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    legend.add(new JLabel(swatch(HitPointBar.GREEN)));
    legend.add(new JLabel("HP left   "));
    legend.add(new JLabel(swatch(HitPointBar.RED)));
    legend.add(new JLabel("hit now   "));
    legend.add(new JLabel(swatch(LOST_EARLIER)));
    legend.add(new JLabel("lost earlier"));
    return legend;
  }

  private static Icon swatch(final Color color) {
    return new Icon() {
      @Override
      public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        g.setColor(HitPointBar.BACKING);
        g.fillRect(x, y, SEGMENT_WIDTH + 2, SEGMENT_HEIGHT + 2);
        g.setColor(color);
        g.fillRect(x + 1, y + 1, SEGMENT_WIDTH, SEGMENT_HEIGHT);
      }

      @Override
      public int getIconWidth() {
        return SEGMENT_WIDTH + 2;
      }

      @Override
      public int getIconHeight() {
        return SEGMENT_HEIGHT + 2;
      }
    };
  }

  /** "sunk" for ships, "shot down" for aircraft, "destroyed" otherwise. */
  static String fate(final Unit unit) {
    if (Matches.unitIsAir().test(unit)) {
      return "shot down";
    }
    return Matches.unitIsSea().test(unit) ? "sunk" : "destroyed";
  }

  /** The unit type's name in words: "heavyTank" becomes "Heavy Tank". */
  static String name(final UnitType type) {
    final String raw = UnitNames.of(type);
    if (UnitNames.isRenamed(type.getName())) {
      return raw;
    }
    final StringBuilder out = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      final char c = raw.charAt(i);
      if (i == 0) {
        out.append(Character.toUpperCase(c));
      } else if (Character.isUpperCase(c) && !Character.isUpperCase(raw.charAt(i - 1))) {
        out.append(' ').append(c);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String html(final String text) {
    return "<html>"
        + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        + "</html>";
  }

  private Icon iconFor(final Unit unit) {
    final ImageKey key = ImageKey.of(unit);
    return icons.computeIfAbsent(
        key.getFullName(),
        k -> {
          if (uiContext == null) {
            return new ImageIcon(
                new java.awt.image.BufferedImage(
                    1, ICON_HEIGHT, java.awt.image.BufferedImage.TYPE_INT_ARGB));
          }
          final ImageIcon full = uiContext.getUnitImageFactory().getIcon(key);
          if (full.getIconHeight() <= 0) {
            return full;
          }
          final int width = full.getIconWidth() * ICON_HEIGHT / full.getIconHeight();
          return new ImageIcon(
              full.getImage().getScaledInstance(width, ICON_HEIGHT, Image.SCALE_SMOOTH));
        });
  }

  /** One row: a unit, or a run of identical units next to each other with the same result. */
  static final class Row {
    final List<Unit> units = new ArrayList<>();
    final int hitsTaken;
    final boolean killed;
    final boolean locked;
    final boolean clicked;
    final int firstPosition;
    final Collection<Unit> carried;

    Row(
        final Unit unit,
        final int hitsTaken,
        final boolean killed,
        final boolean locked,
        final boolean clicked,
        final int firstPosition,
        final Collection<Unit> carried) {
      units.add(unit);
      this.hitsTaken = hitsTaken;
      this.killed = killed;
      this.locked = locked;
      this.clicked = clicked;
      this.firstPosition = firstPosition;
      this.carried = carried;
    }

    boolean accepts(
        final Unit unit,
        final int taken,
        final boolean wasKilled,
        final boolean isLocked,
        final boolean isClicked) {
      final Unit first = units.get(0);
      return !locked
          && !isLocked
          && carried.isEmpty()
          && clicked == isClicked
          && taken == hitsTaken
          && wasKilled == killed
          && unit.getType().equals(first.getType())
          && Objects.equals(unit.getOwner(), first.getOwner())
          && unit.getHits() == first.getHits()
          && Unit.maxHitPoints(unit) == Unit.maxHitPoints(first);
    }

    int count() {
      return units.size();
    }

    boolean isHit() {
      return hitsTaken > 0;
    }

    /** The result in words, e.g. "2 hits, sunk" or "4 of 5 hits, damaged, 1 HP left". */
    String resultText() {
      final Unit unit = units.get(0);
      if (!isHit()) {
        return "not hit";
      }
      final int left = StickyHits.hitPointsLeft(unit);
      final String each = count() > 1 ? " each" : "";
      final String taken =
          killed
              ? hitsTaken + MyFormatter.pluralize(" hit", hitsTaken) + each
              : hitsTaken + " of " + left + MyFormatter.pluralize(" hit", left) + each;
      return killed
          ? taken + ", " + fate(unit)
          : taken + ", damaged, " + (left - hitsTaken) + " HP left";
    }

    String positionText() {
      return count() == 1
          ? String.valueOf(firstPosition)
          : firstPosition + "–" + (firstPosition + count() - 1);
    }
  }

  private final class RowRenderer implements ListCellRenderer<Row> {
    private final JPanel panel = new JPanel(new BorderLayout(8, 0));
    private final JLabel position = new JLabel();
    private final JLabel unitLabel = new JLabel();
    private final HpBar bar = new HpBar();
    private final JLabel result = new JLabel();

    RowRenderer() {
      final JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
      left.setOpaque(false);
      position.setPreferredSize(new Dimension(34, ICON_HEIGHT));
      position.setHorizontalAlignment(JLabel.RIGHT);
      left.add(position);
      left.add(unitLabel);
      final JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
      right.setOpaque(false);
      right.add(bar);
      result.setPreferredSize(new Dimension(230, ICON_HEIGHT));
      right.add(result);
      panel.add(left, BorderLayout.CENTER);
      panel.add(right, BorderLayout.EAST);
      panel.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 6));
    }

    @Override
    public Component getListCellRendererComponent(
        final JList<? extends Row> list,
        final Row row,
        final int index,
        final boolean isSelected,
        final boolean cellHasFocus) {
      final Unit unit = row.units.get(0);
      final Color fg = UIManager.getColor("Label.foreground");
      final Color dim =
          Objects.requireNonNullElse(UIManager.getColor("Label.disabledForeground"), Color.GRAY);
      position.setText(row.isHit() ? row.positionText() + "." : "");
      position.setForeground(fg);
      final StringBuilder label = new StringBuilder(name(unit.getType()));
      if (row.count() > 1) {
        label.append(" ×").append(row.count());
      }
      if (row.locked) {
        label.append("  (damaged earlier, goes first)");
      }
      if (!row.carried.isEmpty()) {
        label.append("  carrying ").append(MyFormatter.unitsToTextNoOwner(row.carried));
      }
      unitLabel.setText(label.toString());
      unitLabel.setIcon(iconFor(unit));
      unitLabel.setForeground(row.isHit() ? fg : dim);
      unitLabel.setFont(list.getFont().deriveFont(row.isHit() ? Font.BOLD : Font.PLAIN));
      bar.set(Unit.maxHitPoints(unit), unit.getHits(), row.hitsTaken);
      result.setText(row.resultText());
      result.setForeground(row.isHit() ? fg : dim);
      result.setFont(list.getFont().deriveFont(row.isHit() ? Font.BOLD : Font.PLAIN));
      final boolean firstNotHit =
          !row.isHit() && index > 0 && list.getModel().getElementAt(index - 1).isHit();
      panel.setBorder(
          BorderFactory.createCompoundBorder(
              firstNotHit
                  ? BorderFactory.createMatteBorder(2, 0, 0, 0, dim)
                  : BorderFactory.createEmptyBorder(2, 0, 0, 0),
              BorderFactory.createEmptyBorder(3, 2, 3, 6)));
      panel.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
      panel.setToolTipText(
          row.locked
              ? "Damaged earlier this round: it must take hits before any undamaged unit"
              : "Drag to move, or click to put it next in line");
      return panel;
    }
  }

  /** A hit-point bar: green left, red hit now, dark red lost earlier. */
  static final class HpBar extends JComponent {
    private static final long serialVersionUID = 1L;
    private int hitPoints;
    private int hitsBefore;
    private int hitsNow;

    void set(final int hitPoints, final int hitsBefore, final int hitsNow) {
      this.hitPoints = hitPoints;
      this.hitsBefore = hitsBefore;
      this.hitsNow = hitsNow;
    }

    @Override
    public Dimension getPreferredSize() {
      // room for the largest bar in a fixed column, so results line up
      return new Dimension(6 * (SEGMENT_WIDTH + 1) + 3, ICON_HEIGHT);
    }

    @Override
    protected void paintComponent(final Graphics g) {
      final int width = hitPoints * (SEGMENT_WIDTH + 1) + 1;
      final int y = (getHeight() - SEGMENT_HEIGHT) / 2;
      g.setColor(HitPointBar.BACKING);
      g.fillRect(0, y - 1, width, SEGMENT_HEIGHT + 2);
      final int left = hitPoints - hitsBefore - hitsNow;
      for (int i = 0; i < hitPoints; i++) {
        g.setColor(
            i < left
                ? HitPointBar.GREEN
                : i < hitPoints - hitsBefore ? HitPointBar.RED : LOST_EARLIER);
        g.fillRect(1 + i * (SEGMENT_WIDTH + 1), y, SEGMENT_WIDTH, SEGMENT_HEIGHT);
      }
    }
  }

  /** The list, with a line where a dragged row will land. */
  private static final class RowList extends JList<Row> {
    private static final long serialVersionUID = 1L;
    private int dropLine = -1;

    RowList(final DefaultListModel<Row> model) {
      super(model);
    }

    void setDropLine(final int row) {
      dropLine = row;
      repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
      super.paintComponent(g);
      if (dropLine < 0 || getModel().getSize() == 0) {
        return;
      }
      final Rectangle cell =
          getCellBounds(
              Math.min(dropLine, getModel().getSize() - 1),
              Math.min(dropLine, getModel().getSize() - 1));
      final int y = dropLine >= getModel().getSize() ? cell.y + cell.height - 2 : cell.y;
      final Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(
          UIManager.getColor("Component.focusColor") != null
              ? UIManager.getColor("Component.focusColor")
              : Color.ORANGE);
      g2.setStroke(new BasicStroke(3));
      g2.drawLine(4, y, getWidth() - 4, y);
      g2.dispose();
    }
  }

  private final class RowMouse extends MouseAdapter {
    private int pressedRow = -1;
    private Point pressedAt;
    private boolean dragging;

    @Override
    public void mousePressed(final MouseEvent e) {
      pressedRow = list.locationToIndex(e.getPoint());
      pressedAt = e.getPoint();
      dragging = false;
    }

    @Override
    public void mouseDragged(final MouseEvent e) {
      if (pressedRow < 0 || model.get(pressedRow).locked) {
        return;
      }
      if (!dragging && pressedAt.distance(e.getPoint()) > 5) {
        dragging = true;
      }
      if (dragging) {
        list.setDropLine(dropRow(e.getPoint()));
      }
    }

    @Override
    public void mouseReleased(final MouseEvent e) {
      final int row = pressedRow;
      pressedRow = -1;
      list.setDropLine(-1);
      if (row < 0 || row >= model.size()) {
        return;
      }
      if (dragging) {
        moveRow(row, dropRow(e.getPoint()));
      } else if (list.locationToIndex(e.getPoint()) == row) {
        model.get(row).units.stream()
            .filter(u -> !clicked.contains(u))
            .findFirst()
            .ifPresent(StickyHitOrderPanel.this::clickUnit);
      }
      list.clearSelection();
    }

    /** The row a drop at this point goes before; never above the locked rows. */
    private int dropRow(final Point p) {
      final int index = list.locationToIndex(p);
      if (index < 0) {
        return 0;
      }
      final Rectangle cell = list.getCellBounds(index, index);
      int row = p.y > cell.y + cell.height / 2 ? index + 1 : index;
      int lockedRows = 0;
      while (lockedRows < model.size() && model.get(lockedRows).locked) {
        lockedRows++;
      }
      return Math.max(row, lockedRows);
    }
  }
}
