package games.strategy.triplea.ui;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.battle.casualty.StickyCasualtyChoice;
import games.strategy.triplea.delegate.battle.casualty.StickyCasualtyChoice.Group;
import games.strategy.triplea.delegate.battle.casualty.StickyHits;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.image.HitPointBar;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Flames and Steel (request 28 part A): casualty selection under sticky hits, laid out like stock
 * TripleA's: one row per unit type with its icon and count. Click a unit to give it a hit;
 * right-click it, or press −, to take a hit back. Hits on units damaged earlier are given already
 * and locked. Multi-hit-point units show a hit-point bar for each unit being hit; the result is one
 * line under the rows. "Suggested casualties" fills in the engine's choice. OK is enabled only when
 * every hit is given and the choice is legal.
 */
public class StickyCasualtyPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final int ICON_HEIGHT = 34;
  private static final int SEGMENT_WIDTH = 13;
  private static final int SEGMENT_HEIGHT = 9;

  /** Hit points lost before these hits. */
  static final Color LOST_EARLIER = new Color(0x6E, 0x22, 0x1C);

  private final StickyCasualtyChoice choice;
  private final CasualtyList suggested;
  @Nullable private final UiContext uiContext;
  private final JPanel rows = new JPanel(new GridBagLayout());
  private final JLabel counter = new JLabel();
  private final JLabel resultLabel = new JLabel();
  private final JLabel problemLabel = new JLabel();
  private final List<Runnable> listeners = new ArrayList<>();

  public StickyCasualtyPanel(
      final List<Unit> units,
      final int hits,
      final CasualtyList suggested,
      final String title,
      final GamePlayer player,
      @Nullable final UiContext uiContext) {
    this.choice = new StickyCasualtyChoice(units, hits);
    this.suggested = suggested;
    this.uiContext = uiContext;
    choice.suggest(suggested);

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
            html(player.getName() + ": click a unit to give it a hit; right-click to take it back.")));
    for (final java.awt.Component c : top.getComponents()) {
      ((JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
    }
    add(top, BorderLayout.NORTH);
    add(rows, BorderLayout.CENTER);

    final JPanel bottom = new JPanel();
    bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
    counter.setFont(counter.getFont().deriveFont(Font.BOLD));
    bottom.add(counter);
    resultLabel.setFont(resultLabel.getFont().deriveFont(Font.BOLD));
    bottom.add(resultLabel);
    problemLabel.setForeground(new Color(0xD0, 0x40, 0x30));
    bottom.add(problemLabel);
    bottom.add(Box.createVerticalStrut(4));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final JButton suggest = new JButton("Suggested casualties");
    suggest.setToolTipText("Fill in the engine's choice: the cheapest units first, as usual");
    suggest.addActionListener(e -> suggestCasualties());
    buttons.add(suggest);
    bottom.add(buttons);
    for (final java.awt.Component c : bottom.getComponents()) {
      ((JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
    }
    add(bottom, BorderLayout.SOUTH);
    refresh();
  }

  /** Runs after every change (the dialog enables OK). */
  public void addChangeListener(final Runnable listener) {
    listeners.add(listener);
  }

  public StickyCasualtyChoice getChoice() {
    return choice;
  }

  /** Whether OK may be pressed: every hit given, and the choice is legal. */
  public boolean isComplete() {
    return choice.problem().isEmpty();
  }

  public CasualtyDetails getCasualtyDetails() {
    final CasualtyList casualties = choice.casualties();
    return new CasualtyDetails(casualties.getKilled(), casualties.getDamaged(), false);
  }

  public String getCounterText() {
    return counter.getText();
  }

  public String getResultText() {
    return resultLabel.getText();
  }

  /** Gives one hit to the group, as a click on it does. */
  public void addHit(final Group group) {
    if (choice.add(group)) {
      refresh();
    }
  }

  /** Takes one hit back from the group, as a right-click does; locked hits stay. */
  public void removeHit(final Group group) {
    if (choice.remove(group)) {
      refresh();
    }
  }

  public void suggestCasualties() {
    choice.clear();
    choice.suggest(suggested);
    refresh();
  }

  private void refresh() {
    rows.removeAll();
    final GridBagConstraints c = new GridBagConstraints();
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(2, 4, 2, 8);
    c.gridy = 0;
    for (final Group group : choice.getGroups()) {
      addRow(group, c);
      c.gridy++;
    }
    final int assigned = choice.assigned();
    counter.setText("Hits to assign: " + assigned + " of " + choice.getHitsToTake());
    resultLabel.setText(html(choice.resultText()));
    problemLabel.setText(choice.problem().map(StickyCasualtyPanel::html).orElse(" "));
    listeners.forEach(Runnable::run);
    revalidate();
    repaint();
  }

  private void addRow(final Group group, final GridBagConstraints c) {
    final Unit first = group.getUnits().get(0);
    final JLabel unit =
        new JLabel(
            group.name() + (group.getUnits().size() > 1 ? " ×" + group.getUnits().size() : ""),
            iconFor(first),
            JLabel.LEFT);
    unit.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    unit.setToolTipText("Click to give a hit; right-click to take one back");
    unit.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            if (SwingUtilities.isRightMouseButton(e)) {
              removeHit(group);
            } else {
              addHit(group);
            }
          }
        });
    c.gridx = 0;
    rows.add(unit, c);

    final JPanel count = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    final JButton minus = small("−");
    minus.setEnabled(group.getHits() > group.getLocked());
    minus.addActionListener(e -> removeHit(group));
    final JLabel hits = new JLabel(String.valueOf(group.getHits()));
    hits.setFont(hits.getFont().deriveFont(Font.BOLD));
    hits.setPreferredSize(new Dimension(22, hits.getPreferredSize().height));
    hits.setHorizontalAlignment(JLabel.CENTER);
    hits.setToolTipText(group.getHits() + MyFormatter.pluralize(" hit", group.getHits()));
    final JButton plus = small("+");
    plus.setEnabled(choice.assigned() < choice.needed() && group.getHits() < group.capacity());
    plus.addActionListener(e -> addHit(group));
    count.add(minus);
    count.add(hits);
    count.add(plus);
    c.gridx = 1;
    rows.add(count, c);

    final JPanel bars = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    if (!group.isSingleHitPoint()) {
      for (final StickyHits.Allocation a : group.allocations()) {
        final HpBar bar = new HpBar();
        bar.set(Unit.maxHitPoints(a.unit()), a.unit().getHits(), a.hitsTaken());
        bar.setToolTipText(
            a.killed()
                ? "This one is " + StickyCasualtyChoice.fate(a.unit())
                : "This one: " + (a.unit().getHits() + a.hitsTaken()) + " of "
                    + Unit.maxHitPoints(a.unit()) + " hit points lost");
        bars.add(bar);
      }
    }
    c.gridx = 2;
    rows.add(bars, c);

    final JLabel note = new JLabel(rowResult(group));
    if (group.getLocked() > 0) {
      note.setIcon(LOCK);
      note.setText("Already damaged: must take hits first.  " + note.getText());
      note.setToolTipText("Damaged earlier: its hits are given already and cannot be taken back");
    }
    c.gridx = 3;
    rows.add(note, c);
  }

  private static String rowResult(final Group group) {
    final int killed = group.killed();
    final List<String> parts = new ArrayList<>();
    if (killed > 0) {
      parts.add(killed + " " + StickyCasualtyChoice.fate(group.getUnits().get(0)));
    }
    group
        .leftDamaged()
        .ifPresent(
            u -> {
              final int left =
                  group.allocations().stream()
                      .filter(a -> a.unit().equals(u))
                      .mapToInt(StickyHits.Allocation::hitPointsLeftAfter)
                      .findFirst()
                      .orElse(0);
              parts.add("1 damaged (" + left + " HP left)");
            });
    return String.join(", ", parts);
  }

  private static JButton small(final String text) {
    final JButton b = new JButton(text);
    b.setMargin(new Insets(0, 5, 0, 5));
    return b;
  }

  private static String html(final String text) {
    return "<html>"
        + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        + "</html>";
  }

  private Icon iconFor(final Unit unit) {
    if (uiContext == null) {
      return new ImageIcon(
          new java.awt.image.BufferedImage(1, ICON_HEIGHT, java.awt.image.BufferedImage.TYPE_INT_ARGB));
    }
    final ImageIcon full = uiContext.getUnitImageFactory().getIcon(ImageKey.of(unit));
    if (full.getIconHeight() <= 0) {
      return full;
    }
    final int width = full.getIconWidth() * ICON_HEIGHT / full.getIconHeight();
    return new ImageIcon(full.getImage().getScaledInstance(width, ICON_HEIGHT, Image.SCALE_SMOOTH));
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
      return new Dimension(hitPoints * (SEGMENT_WIDTH + 1) + 1, ICON_HEIGHT);
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

  /** A small padlock: the hits on this row are fixed. */
  static final Icon LOCK =
      new Icon() {
        @Override
        public void paintIcon(
            final java.awt.Component c, final Graphics g, final int x, final int y) {
          final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
          g2.setRenderingHint(
              java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
          g2.setColor(
              Objects.requireNonNullElse(UIManager.getColor("Label.foreground"), Color.BLACK));
          g2.setStroke(new java.awt.BasicStroke(2f));
          g2.drawArc(x + 3, y + 1, 8, 9, 0, 180);
          g2.drawLine(x + 3, y + 5, x + 3, y + 7);
          g2.drawLine(x + 11, y + 5, x + 11, y + 7);
          g2.fillRoundRect(x + 1, y + 7, 12, 9, 3, 3);
          g2.dispose();
        }

        @Override
        public int getIconWidth() {
          return 14;
        }

        @Override
        public int getIconHeight() {
          return 16;
        }
      };
}
