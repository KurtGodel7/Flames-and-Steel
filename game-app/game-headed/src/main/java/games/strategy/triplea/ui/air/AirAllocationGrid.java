package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.CarrierLoad;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.LongRangeRaid;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolTip;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel allocation grid: one window per allocation step. Rows are the nation's aircraft
 * groups (type and current front); columns are the fronts it could use this round. A cell's + moves
 * one aircraft from the row's current front into that column and − moves it back, so every aircraft
 * is always somewhere; the row's own cell ("Staying") has − and + too. Cells where the type cannot
 * go are greyed with the reason; usable cells of aircraft that strike show their number of targets.
 * Bottom rows show the nation's totals, allies' moves so far and enemies' last revealed aircraft. A
 * small map of the fronts shows where the selected row can go.
 */
final class AirAllocationGrid extends JDialog {
  private static final long serialVersionUID = 1L;
  private static final Color TEXT = new Color(0x1A, 0x1A, 0x1A);
  private static final Color LINES = new Color(0xC4, 0xCA, 0xD0);
  private static final Color CURRENT = new Color(0xFF, 0xF1, 0xC4);
  private static final Color GREY = new Color(0xE2, 0xE2, 0xE2);
  private static final Color GREY_TEXT = new Color(0x90, 0x90, 0x90);
  private static final Color USABLE = Color.WHITE;
  private static final Color HEADER = new Color(0xE6, 0xEC, 0xF2);
  private static final Color FOOTER = new Color(0xF7, 0xF7, 0xF7);
  private static final Color OCEAN = new Color(0xE0, 0xA8, 0x00);
  private static final Color FLASH = new Color(0xFF, 0xCD, 0xD2);
  private static final Color HATCH = new Color(0xC8, 0xC8, 0xC8);
  private static final Color SELECTION_BAR = new Color(0x1E, 0x6F, 0xD9);
  private static final Color REASON = new Color(0x55, 0x55, 0x55);
  private static final Color AMBER = new Color(0xA0, 0x6A, 0x00);

  /** What the grid needs from the allocation step. */
  interface Host {
    GameData data();

    GamePlayer player();

    Stage stage();

    int round();

    List<AirMove> staged();

    void setStaged(List<AirMove> moves);

    Map<GamePlayer, List<AirMove>> allied();

    void done();

    void hoverZone(@Nullable Territory zone);
  }

  /** One row: the nation's aircraft of a type in a front. */
  private record Row(Territory from, UnitType type, int count) {}

  private final Host host;
  private final UiContext uiContext;
  private final List<Row> rows = new ArrayList<>();
  private final List<Territory> columns = new ArrayList<>();
  // per (type, column): why the type cannot be there (empty if it can), and its target count
  private final Map<String, Optional<String>> entry = new HashMap<>();
  private final Map<String, Integer> targetCount = new HashMap<>();
  private final Map<String, Map<TargetKind, Integer>> targetKinds = new HashMap<>();
  private final Map<String, Integer> neutralTargets = new HashMap<>();
  // land targets held by players that never take a turn, e.g. "3 Dutch" (request 12)
  private final Map<String, String> turnlessTargets = new HashMap<>();
  private final Deque<List<AirMove>> undo = new ArrayDeque<>();
  // per row: the columns it sent aircraft to, most recent last (the Staying cell's + pulls back)
  private final Map<Row, Deque<Territory>> sentOrder = new HashMap<>();
  private final JLabel header = new JLabel();
  private final JLabel status = new JLabel();
  private final JPanel grid = new JPanel(new GridBagLayout());
  private final JButton undoButton = new JButton("Undo");
  private final MiniMap miniMap;
  private @Nullable Row selectedRow;
  private @Nullable Row hoverRow;
  private @Nullable Territory hoverColumn;

  AirAllocationGrid(final Frame owner, final UiContext uiContext, final Host host) {
    super(owner, games.strategy.triplea.formatter.PlayerNames.display(host.player()) + " air allocation", false);
    this.host = host;
    this.uiContext = uiContext;
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(final WindowEvent e) {
            // the step stays open; the Actions tab reopens the window
            setVisible(false);
          }
        });
    computeLayout();
    selectedRow = rows.isEmpty() ? null : rows.get(0);
    miniMap = new MiniMap();

    header.setFont(header.getFont().deriveFont(Font.BOLD, 15f));
    header.setForeground(TEXT);
    status.setFont(status.getFont().deriveFont(Font.PLAIN, 13f));
    status.setForeground(TEXT);
    final JPanel top = new JPanel(new BorderLayout());
    final JPanel titles = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    titles.add(header, c);
    c.gridy = 1;
    titles.add(status, c);
    top.add(titles, BorderLayout.CENTER);
    top.add(miniMap, BorderLayout.EAST);
    top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

    final JButton reset = new JButton("Reset");
    reset.addActionListener(
        e -> {
          sentOrder.clear();
          change(List.of());
        });
    undoButton.addActionListener(
        e -> {
          if (!undo.isEmpty()) {
            host.setStaged(undo.pop());
            refresh();
          }
        });
    final JButton done = new JButton("Done (Ctrl+Enter)");
    done.setFont(done.getFont().deriveFont(Font.BOLD));
    done.addActionListener(e -> host.done());
    // request 24: the same shortcut as the Actions panel's Done
    getRootPane()
        .registerKeyboardAction(
            e -> host.done(),
            javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
    buttons.add(undoButton);
    buttons.add(reset);
    buttons.add(done);

    grid.setOpaque(true);
    grid.setBackground(LINES);
    final JPanel gridHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    gridHolder.add(grid);
    final JPanel middle = new JPanel(new BorderLayout());
    getContentPane().setLayout(new BorderLayout());
    getContentPane().add(top, BorderLayout.NORTH);
    final JScrollPane scroll = new JScrollPane(gridHolder);
    scroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
    middle.add(key(), BorderLayout.NORTH);
    middle.add(scroll, BorderLayout.CENTER);
    getContentPane().add(middle, BorderLayout.CENTER);
    getContentPane().add(buttons, BorderLayout.SOUTH);
    refresh();
    pack();
    final Rectangle o = owner.getBounds();
    setSize(Math.min(getWidth() + 30, o.width - 40), Math.min(getHeight() + 20, o.height - 80));
    setLocation(o.x + (o.width - getWidth()) / 2, o.y + o.height - getHeight() - 60);
  }

  // ---- what the grid shows

  private void computeLayout() {
    final GameData data = host.data();
    final GamePlayer player = host.player();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final List<Territory> zones = AirZoneAttachment.getAirZones(data.getMap());
      final Set<Territory> current = new LinkedHashSet<>();
      for (final Territory zone : zones) {
        final IntegerMap<UnitType> byType = new IntegerMap<>();
        AirAllocation.getAircraft(player, zone, host.stage())
            .forEach(u -> byType.add(u.getType(), 1));
        for (final UnitType type : byType.keySet()) {
          rows.add(new Row(zone, type, byType.getInt(type)));
          current.add(zone);
        }
      }
      final Set<UnitType> types = new LinkedHashSet<>();
      rows.forEach(r -> types.add(r.type()));
      for (final Territory zone : zones) {
        for (final UnitType type : types) {
          final Optional<String> problem = AirAllocation.entryProblem(player, type, zone);
          entry.put(key(type, zone), problem);
          // every front, the rows' own ones included, for every type that strikes
          if (!isFighter(type)) {
            final Map<TargetKind, Integer> kinds = AirCombat.countTargetsByKind(player, zone, type);
            targetKinds.put(key(type, zone), kinds);
            neutralTargets.put(key(type, zone), AirCombat.countNeutralTargets(player, zone, type));
            turnlessTargets.put(key(type, zone), turnlessText(player, zone, type));
            targetCount.put(
                key(type, zone), kinds.values().stream().mapToInt(Integer::intValue).sum());
          }
        }
      }
      // request 9 item 3: every front and bombing zone has a column, open or not; nearest first
      // (the nation's own fronts, then their neighbours, and so on)
      final Map<Territory, Integer> distance = new HashMap<>();
      final ArrayDeque<Territory> queue = new ArrayDeque<>(current);
      current.forEach(z -> distance.put(z, 0));
      while (!queue.isEmpty()) {
        final Territory z = queue.poll();
        for (final Territory n : AirZoneAttachment.get(z).orElseThrow().getAdjacentZones()) {
          if (!distance.containsKey(n)) {
            distance.put(n, distance.get(z) + 1);
            queue.add(n);
          }
        }
      }
      zones.stream()
          .filter(
              zone -> AirZoneAttachment.get(zone).orElseThrow().getZoneType() != ZoneType.RESERVE)
          .sorted(
              java.util.Comparator.comparingInt(
                  (Territory z) -> distance.getOrDefault(z, Integer.MAX_VALUE)))
          .forEach(columns::add);
    }
  }

  private String aircraftWord() {
    return host.stage() == Stage.BOMBERS ? "bombers" : "fighters";
  }

  private void refresh() {
    final GamePlayer player = host.player();
    final List<AirMove> staged = host.staged();
    final int moving = staged.stream().mapToInt(AirMove::count).sum();
    header.setText(
        games.strategy.triplea.formatter.PlayerNames.display(player)
            + "  ·  round "
            + host.round()
            + "  ·  step "
            + (host.stage() == Stage.BOMBERS ? "1 of 2: bombers" : "2 of 2: fighters"));
    final int total = rows.stream().mapToInt(Row::count).sum();
    // wraps rather than being cut off beside the mini map
    status.setText(
        "<html><div style='width:320px'>"
            + (total == 0
                ? "No " + aircraftWord() + " to allocate."
                : moving == 0
                    ? "All " + total + " aircraft stay where they are. Use + to move them."
                    : moving + " moving, " + (total - moving) + " staying.")
            + "</div></html>");
    undoButton.setEnabled(!undo.isEmpty());
    grid.removeAll();
    final GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.BOTH;
    c.insets = new Insets(1, 1, 0, 0);
    c.gridy = 0;
    // column headers
    c.gridx = 0;
    grid.add(headerCell("Aircraft"), c);
    for (int i = 0; i < columns.size(); i++) {
      c.gridx = i + 1;
      grid.add(columnHeader(columns.get(i), staged), c);
    }
    // rows
    for (final Row row : rows) {
      c.gridy++;
      c.gridx = 0;
      grid.add(rowHeader(row), c);
      for (int i = 0; i < columns.size(); i++) {
        c.gridx = i + 1;
        final JComponent cell = cell(row, columns.get(i), staged);
        track(cell, row, columns.get(i));
        grid.add(cell, c);
      }
    }
    // totals and other nations
    c.gridy++;
    c.gridx = 0;
    grid.add(footerLabel("Your " + aircraftWord() + " after moves"), c);
    for (int i = 0; i < columns.size(); i++) {
      c.gridx = i + 1;
      grid.add(footerCell(String.valueOf(totalAfter(player, columns.get(i), staged)), true), c);
    }
    c.gridy++;
    c.gridx = 0;
    grid.add(footerLabel("Allies (moves so far)"), c);
    for (int i = 0; i < columns.size(); i++) {
      c.gridx = i + 1;
      grid.add(footerCell(othersText(columns.get(i), true), false), c);
    }
    c.gridy++;
    c.gridx = 0;
    c.insets = new Insets(1, 1, 1, 0);
    grid.add(footerLabel("Enemies (last revealed)"), c);
    for (int i = 0; i < columns.size(); i++) {
      c.gridx = i + 1;
      grid.add(footerCell(othersText(columns.get(i), false), false), c);
    }
    // a 1-pixel line on the right edge
    c.gridx = columns.size() + 1;
    c.gridy = 0;
    c.gridheight = GridBagConstraints.REMAINDER;
    c.insets = new Insets(0, 1, 0, 0);
    grid.add(new JLabel(), c);
    grid.revalidate();
    grid.repaint();
    miniMap.repaint();
  }

  private JComponent headerCell(final String text) {
    final JLabel l = new JLabel(text, SwingConstants.CENTER);
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    l.setForeground(TEXT);
    l.setOpaque(true);
    l.setBackground(HEADER);
    l.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    return l;
  }

  private JComponent columnHeader(final Territory zone, final List<AirMove> staged) {
    final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
    final StringBuilder text =
        new StringBuilder("<html><center><b>").append(AirAllocation.zoneName(zone)).append("</b>");
    if (a.getZoneType() == ZoneType.BOMB) {
      text.append("<br><font size=-2>strategic bombing</font>");
    } else if (a.getZoneType() == ZoneType.OCEAN) {
      try (GameData.Unlocker ignored = host.data().acquireReadLock()) {
        final CarrierLoad load =
            AirAllocation.carrierLoadAfter(host.player(), host.stage(), staged, zone);
        final int fighters =
            host.stage() == Stage.BOMBERS ? fighterCarrierCost(host.player(), zone) : 0;
        text.append("<br><font size=-2>carriers ");
        if (fighters > 0) {
          text.append(load.cost()).append(" bombers<br>+ ").append(fighters).append(" fighters");
        } else {
          text.append(load.cost());
        }
        text.append(" / ").append(load.capacity());
        if (load.jetCost() > 0 || load.fleetCapacity() > 0) {
          text.append(", jets ").append(load.jetCost()).append('/').append(load.fleetCapacity());
        }
        text.append("</font>");
        // over capacity: in the bomber step the fighters will have to move off; in the fighter
        // step they must
        final int over =
            host.stage() == Stage.BOMBERS
                ? AirAllocation.carrierOverload(host.player(), staged, zone)
                : Math.max(0, load.cost() - load.capacity());
        if (over > 0) {
          text.append("<br><font size=-2 color='#b71c1c'><b>")
              .append(over)
              .append(over == 1 ? " fighter" : " fighters")
              .append(host.stage() == Stage.BOMBERS ? " must<br>leave in step 2" : " must leave")
              .append("</b></font>");
        }
      }
    }
    final JLabel l =
        new TipLabel(text.append("</center></html>").toString(), SwingConstants.CENTER);
    l.setForeground(TEXT);
    l.setToolTipText("Point here to see " + AirAllocation.zoneName(zone) + " on the map");
    final Cell p = new Cell(null, new BorderLayout());
    p.setBackground(HEADER);
    p.add(l, BorderLayout.CENTER);
    p.setBorder(
        a.getZoneType() == ZoneType.OCEAN
            ? BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 3, 0, OCEAN),
                BorderFactory.createEmptyBorder(4, 6, 2, 6))
            : BorderFactory.createEmptyBorder(4, 6, 5, 6));
    track(p, null, zone);
    return p;
  }

  private JComponent rowHeader(final Row row) {
    final JLabel l =
        new TipLabel(
            "<html><b>"
                + AirAllocation.typeName(row.type())
                + " × "
                + row.count()
                + "</b><br><div style='width:190px'><font size=-2>now: "
                + AirAllocation.zoneName(row.from())
                + " · "
                + (reach(row).isEmpty()
                    ? "can't move this round: " + cantMoveText(row)
                    : "can go to "
                        + String.join(
                            ", ", reach(row).stream().map(AirAllocationGrid::zoneName).toList()))
                + "</font></div></html>",
            SwingConstants.LEFT);
    l.setIcon(
        uiContext
            .getUnitImageFactory()
            .getIcon(ImageKey.builder().player(host.player()).type(row.type()).build()));
    l.setForeground(TEXT);
    l.setToolTipText("Click to select this row: the small map shows where it can go");
    final Cell p = new Cell(row, new BorderLayout());
    p.setBackground(USABLE);
    p.add(l, BorderLayout.CENTER);
    // the selected row has a coloured bar on its left edge
    p.setBorder(
        BorderFactory.createCompoundBorder(
            row.equals(selectedRow)
                ? BorderFactory.createMatteBorder(0, 5, 0, 0, SELECTION_BAR)
                : BorderFactory.createEmptyBorder(0, 5, 0, 0),
            BorderFactory.createEmptyBorder(2, 4, 2, 8)));
    final MouseAdapter select =
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            selectedRow = row;
            refresh();
          }
        };
    p.addMouseListener(select);
    l.addMouseListener(select);
    l.setName("row|" + rowName(row));
    track(p, row, null);
    return p;
  }

  private JComponent cell(final Row row, final Territory column, final List<AirMove> staged) {
    final int moved = movedTo(row, column, staged);
    final int left = row.count() - movedOut(row, staged);
    final Cell p = new Cell(row, new BorderLayout(2, 0));
    p.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
    if (column.equals(row.from())) {
      return homeCell(p, row, column, staged, left);
    }
    // a full carrier front with none of this group in it is as closed as any other
    final Optional<String> problem =
        problem(row, column).or(() -> moved > 0 ? Optional.empty() : carrierFull(row, column));
    if (problem.isPresent()) {
      return blockedCell(p, row, column, problem.get());
    }
    p.setBackground(USABLE);
    final JButton minus = small("−");
    minus.setName("minus|" + rowName(row) + "|" + column.getName());
    minus.setToolTipText(
        moved > 0
            ? "Send one back to " + zoneName(row.from())
            : "None of these has been sent to " + zoneName(column));
    minus.addActionListener(
        e -> {
          if (movedTo(row, column, host.staged()) <= 0) {
            refuse(
                p,
                "No "
                    + plural(row.type(), 2)
                    + " from "
                    + zoneName(row.from())
                    + " are in "
                    + zoneName(column)
                    + " to send back.");
            return;
          }
          change(adjust(host.staged(), row, column, -1));
        });
    final JButton plus = small("+");
    plus.setName("plus|" + rowName(row) + "|" + column.getName());
    plus.setToolTipText(
        left > 0
            ? "Move one " + plural(row.type(), 1) + " here from " + zoneName(row.from())
            : "Move one here from where they went last");
    plus.addActionListener(e -> plus(p, row, column));
    final JLabel count = new TipLabel(String.valueOf(moved), SwingConstants.CENTER);
    count.setFont(count.getFont().deriveFont(Font.BOLD, 14f));
    count.setForeground(TEXT);
    count.setAlignmentX(CENTER_ALIGNMENT);
    final JPanel middle = new JPanel();
    middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
    middle.setOpaque(false);
    middle.add(count);
    // a full carrier front says so in the cell; otherwise the number of targets
    final Optional<String> carrier = carrierFull(row, column);
    final JLabel note;
    if (carrier.isPresent()) {
      note = new TipLabel(shortReason(carrier.get()), SwingConstants.CENTER);
      note.setFont(note.getFont().deriveFont(Font.BOLD, 10f));
      note.setForeground(new Color(0xB7, 0x1C, 0x1C));
      note.setToolTipText(carrierDetail(column, staged, carrier.get()));
    } else {
      note = targetNote(row, column);
    }
    if (note != null) {
      note.setAlignmentX(CENTER_ALIGNMENT);
      middle.add(note);
    }
    final JButton all = allHereButton(p, row, column);
    all.setAlignmentX(CENTER_ALIGNMENT);
    middle.add(Box.createVerticalStrut(2));
    middle.add(all);
    p.add(minus, BorderLayout.WEST);
    p.add(middle, BorderLayout.CENTER);
    p.add(plus, BorderLayout.EAST);
    return p;
  }

  /** Grey, hatched, no buttons: the reason in the cell, the full reason on hover and on a click. */
  private JComponent blockedCell(
      final Cell p, final Row row, final Territory column, final String reason) {
    p.setBackground(GREY);
    p.setHatched(true);
    final JLabel l =
        new TipLabel(
            "<html><center>" + shortReason(reason) + "</center></html>", SwingConstants.CENTER);
    l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
    l.setForeground(REASON);
    final String full = fullReason(row, column, reason);
    l.setToolTipText(full);
    p.setToolTipText(full);
    p.add(l, BorderLayout.CENTER);
    final MouseAdapter click =
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            refuse(p, AirAllocation.typeName(row.type()) + ": " + full);
          }
        };
    p.addMouseListener(click);
    l.addMouseListener(click);
    return p;
  }

  /** Why not even one more of the row could go to a carrier front (empty if one could). */
  private Optional<String> carrierFull(final Row row, final Territory column) {
    return plusProblem(row, column, host.staged(), 1);
  }

  /** "All here": the whole group into this column (or, in its own front, all of it back). */
  private JButton allHereButton(final JComponent cell, final Row row, final Territory column) {
    final JButton all = small("All here");
    all.setFont(all.getFont().deriveFont(10f));
    all.setName("allHere|" + rowName(row) + "|" + column.getName());
    all.setToolTipText(
        column.equals(row.from())
            ? "Keep all " + plural(row.type(), 2) + " in " + zoneName(column)
            : "Send all " + plural(row.type(), 2) + " here (as many as fit)");
    all.addActionListener(
        e -> {
          final List<AirMove> moves = allHere(host.staged(), row, column);
          if (moves.equals(host.staged())) {
            final Optional<String> full = carrierFull(row, column);
            refuse(
                cell,
                full.isPresent() && !column.equals(row.from())
                    ? carrierDetail(column, host.staged(), full.get())
                    : row.count() == 1
                        ? "Your 1 " + plural(row.type(), 1) + " is already here."
                        : "All "
                            + row.count()
                            + " "
                            + plural(row.type(), 2)
                            + " are already here.");
            return;
          }
          if (change(moves)) {
            final Deque<Territory> order = sentOrder.get(row);
            if (order != null) {
              order.clear();
            }
            if (!column.equals(row.from())) {
              sent(row, column);
              noteNoTargets(row, column);
            }
          }
        });
    return all;
  }

  /**
   * + in a cell: one aircraft from the home front if any are left there, otherwise from the column
   * this group last sent one to (so it moves straight from one front to another).
   */
  private void plus(final JComponent cell, final Row row, final Territory column) {
    final List<AirMove> staged = host.staged();
    final int left = row.count() - movedOut(row, staged);
    if (left > 0) {
      final Optional<String> problem = plusProblem(row, column, staged, left);
      if (problem.isPresent()) {
        refuse(cell, carrierDetail(column, staged, problem.get()));
        return;
      }
      if (change(adjust(staged, row, column, 1))) {
        sent(row, column);
        noteNoTargets(row, column);
      }
      return;
    }
    final Optional<Territory> source = pullSource(row, staged, column);
    if (source.isEmpty()) {
      refuse(
          cell,
          row.count() == 1
              ? "Your 1 " + plural(row.type(), 1) + " is already here."
              : "All " + row.count() + " " + plural(row.type(), 2) + " are already here.");
      return;
    }
    final List<AirMove> taken = adjust(staged, row, source.get(), -1);
    final Optional<String> problem = plusProblem(row, column, taken, 1);
    if (problem.isPresent()) {
      refuse(cell, carrierDetail(column, taken, problem.get()));
      return;
    }
    if (change(adjust(taken, row, column, 1))) {
      final Deque<Territory> order = sentOrder.get(row);
      if (order != null) {
        order.removeLastOccurrence(source.get());
      }
      sent(row, column);
      noteNoTargets(row, column);
    }
  }

  /** "Philippine Sea: 8/8 used" for a full carrier front; other reasons as they are. */
  private String carrierDetail(
      final Territory column, final List<AirMove> staged, final String reason) {
    if (!reason.startsWith("No carrier capacity")) {
      return reason;
    }
    try (GameData.Unlocker ignored = host.data().acquireReadLock()) {
      final CarrierLoad load =
          AirAllocation.carrierLoadAfter(host.player(), host.stage(), staged, column);
      return "No carrier room: "
          + zoneName(column)
          + " "
          + load.cost()
          + "/"
          + load.capacity()
          + " used.";
    }
  }

  /**
   * The row's own front: "Staying: 5". − sends one to the column it last went to (or the first
   * column that can take it); + pulls one back from the column it last went to; "All here" keeps
   * them all.
   */
  private JComponent homeCell(
      final Cell p,
      final Row row,
      final Territory column,
      final List<AirMove> staged,
      final int left) {
    p.setBackground(CURRENT);
    final JLabel l = new TipLabel("Staying: " + left, SwingConstants.CENTER);
    l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
    l.setForeground(TEXT);
    l.setToolTipText("Aircraft that stay in " + zoneName(column));
    l.setAlignmentX(CENTER_ALIGNMENT);
    final JButton minus = small("−");
    minus.setName("minus|" + rowName(row) + "|" + column.getName());
    final Optional<Territory> sendTo = sendTarget(row, staged, left);
    minus.setToolTipText(
        sendTo
            .map(t -> "Send one to " + zoneName(t))
            .orElse(left <= 0 ? "None left here." : "No front can take one more."));
    minus.addActionListener(
        e -> {
          final int now = row.count() - movedOut(row, host.staged());
          final Optional<Territory> to = sendTarget(row, host.staged(), now);
          if (to.isEmpty()) {
            refuse(
                p,
                now <= 0
                    ? "None of these " + plural(row.type(), 2) + " is staying here."
                    : reach(row).isEmpty()
                        ? "These " + plural(row.type(), 2) + " cannot move this round."
                        : "No front they can reach has room for one more.");
            return;
          }
          if (change(adjust(host.staged(), row, to.get(), 1))) {
            sent(row, to.get());
            noteNoTargets(row, to.get());
          }
        });
    final JButton plus = small("+");
    plus.setName("plus|" + rowName(row) + "|" + column.getName());
    final Optional<Territory> pullFrom = pullSource(row, staged, null);
    plus.setToolTipText(
        pullFrom.map(t -> "Bring one back from " + zoneName(t)).orElse("None of them is moving."));
    plus.addActionListener(
        e -> {
          final Optional<Territory> from = pullSource(row, host.staged(), null);
          if (from.isEmpty()) {
            refuse(
                p,
                row.count() == 1
                    ? "Your 1 " + plural(row.type(), 1) + " is already staying."
                    : "All " + row.count() + " " + plural(row.type(), 2) + " are already staying.");
            return;
          }
          final Deque<Territory> order = sentOrder.get(row);
          if (order != null) {
            order.removeLastOccurrence(from.get());
          }
          change(adjust(host.staged(), row, from.get(), -1));
        });
    final JPanel middle = new JPanel();
    middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
    middle.setOpaque(false);
    middle.add(Box.createVerticalGlue());
    middle.add(l);
    final JLabel note = targetNote(row, column);
    if (note != null) {
      note.setAlignmentX(CENTER_ALIGNMENT);
      middle.add(note);
    }
    final JButton all = allHereButton(p, row, column);
    all.setAlignmentX(CENTER_ALIGNMENT);
    middle.add(Box.createVerticalStrut(2));
    middle.add(all);
    middle.add(Box.createVerticalGlue());
    p.add(minus, BorderLayout.WEST);
    p.add(middle, BorderLayout.CENTER);
    p.add(plus, BorderLayout.EAST);
    return p;
  }

  private void sent(final Row row, final Territory column) {
    sentOrder.computeIfAbsent(row, k -> new ArrayDeque<>()).addLast(column);
  }

  /** Where the Staying cell's − sends one: the column last sent to, else the first that can. */
  private Optional<Territory> sendTarget(
      final Row row, final List<AirMove> staged, final int left) {
    if (left <= 0) {
      return Optional.empty();
    }
    final List<Territory> order = new ArrayList<>();
    final Deque<Territory> recent = sentOrder.get(row);
    if (recent != null) {
      recent.descendingIterator().forEachRemaining(order::add);
    }
    order.addAll(columns);
    for (final Territory column : order) {
      if (!column.equals(row.from())
          && problem(row, column).isEmpty()
          && plusProblem(row, column, staged, left).isEmpty()) {
        return Optional.of(column);
      }
    }
    return Optional.empty();
  }

  /**
   * Where one of the row's moved aircraft is taken from (the Staying cell's +, or a + once none are
   * left at home): the column most recently sent to, other than {@code except}.
   */
  private Optional<Territory> pullSource(
      final Row row, final List<AirMove> staged, final @Nullable Territory except) {
    final Deque<Territory> recent = sentOrder.get(row);
    if (recent != null) {
      for (final var it = recent.descendingIterator(); it.hasNext(); ) {
        final Territory t = it.next();
        if (!t.equals(except) && movedTo(row, t, staged) > 0) {
          return Optional.of(t);
        }
      }
    }
    for (int i = columns.size() - 1; i >= 0; i--) {
      final Territory t = columns.get(i);
      if (!t.equals(except) && movedTo(row, t, staged) > 0) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /**
   * The row's whole group moved to the column (as many as fit on carriers), or, in its own front,
   * all of it kept there. Other rows are untouched.
   */
  private List<AirMove> allHere(final List<AirMove> staged, final Row row, final Territory column) {
    // take the row's moves back, then send them all here
    final List<AirMove> without = new ArrayList<>();
    for (final AirMove m : staged) {
      if (!(m.from().equals(row.from()) && m.unitType().equals(row.type()))) {
        without.add(m);
      }
    }
    if (column.equals(row.from())) {
      return without;
    }
    if (problem(row, column).isPresent()) {
      return staged;
    }
    List<AirMove> moved = without;
    for (int i = 0; i < row.count(); i++) {
      if (plusProblem(row, column, moved, row.count() - i).isPresent()) {
        break;
      }
      moved = adjust(moved, row, column, 1);
    }
    // a carrier front may take none of this row: then keep its moves as they were
    return movedTo(row, column, moved) > movedTo(row, column, staged) ? moved : staged;
  }

  // ---- clicks that cannot do anything say why

  /** Shows why a click did nothing: the reason in the status line, and the cell flashes. */
  private void refuse(final JComponent cell, final String reason) {
    status.setText(
        "<html><div style='width:320px'><font color='#b71c1c'>" + reason + "</font></div></html>");
    final Color before = cell.getBackground();
    cell.setBackground(FLASH);
    final Timer timer = new Timer(450, e -> cell.setBackground(before));
    timer.setRepeats(false);
    timer.start();
  }

  /** The cell's short reason ("No targets") for a full reason from the rules. */
  private static String shortReason(final String reason) {
    final java.util.regex.Matcher rounds =
        java.util.regex.Pattern.compile("^(\\d+) rounds").matcher(reason);
    if (rounds.find()) {
      return rounds.group(1) + " rounds away";
    }
    if (reason.startsWith("Can't be reached")) {
      return "Out of reach";
    }
    if (reason.startsWith("No landing spot")) {
      return "No landing spot";
    }
    if (reason.startsWith("No carriers")) {
      return "No carriers";
    }
    if (reason.startsWith("No enemy") || reason.startsWith("No targets")) {
      return "No targets";
    }
    if (reason.startsWith("No carrier capacity")) {
      return "No carrier room";
    }
    if (reason.startsWith("Jets need fleet carriers")) {
      return "No fleet carrier room";
    }
    if (reason.contains("cannot fly from carriers")) {
      return "Can't use carriers";
    }
    if (reason.startsWith("Only fighters defend")) {
      return "Fighters only";
    }
    if (reason.startsWith("No strategic bombing value")) {
      return "Can't bomb";
    }
    final int i = reason.indexOf(" aircraft may not enter ");
    if (i >= 0 && reason.endsWith(" fronts.")) {
      final String tag =
          reason.substring(i + " aircraft may not enter ".length(), reason.length() - 8);
      return tag.equals("China") ? "China only" : tag + " front";
    }
    return "Not allowed";
  }

  /** "dive bomber" / "dive bombers". */
  private static String plural(final UnitType type, final int count) {
    final String name = AirAllocation.typeName(type).toLowerCase();
    return count == 1 ? name : name + "s";
  }

  /**
   * A grid cell. Its look never depends on the mouse except for a light tint while its row is
   * pointed at; a blocked cell is grey with a light diagonal hatch. Tooltips open above it.
   */
  private final class Cell extends JPanel {
    private static final long serialVersionUID = 1L;
    private final @Nullable Row row;
    private boolean hatched;

    Cell(final @Nullable Row row, final java.awt.LayoutManager layout) {
      super(layout);
      this.row = row;
      setOpaque(true);
    }

    void setHatched(final boolean hatched) {
      this.hatched = hatched;
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
      final Graphics2D g = (Graphics2D) graphics.create();
      final Color back = getBackground();
      g.setColor(row != null && row.equals(hoverRow) ? tint(back) : back);
      g.fillRect(0, 0, getWidth(), getHeight());
      if (hatched) {
        paintHatch(g, getWidth(), getHeight());
      }
      g.dispose();
    }

    @Override
    public Point getToolTipLocation(final MouseEvent e) {
      return above(this);
    }
  }

  private static void paintHatch(final Graphics2D g, final int width, final int height) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(HATCH);
    g.setStroke(new BasicStroke(1f));
    for (int x = -height; x < width; x += 8) {
      g.drawLine(x, height, x + height, 0);
    }
  }

  /** The hovered row's light tint over a cell's own colour. */
  private static Color tint(final Color c) {
    return new Color(
        (c.getRed() * 3 + 0xD6) / 4, (c.getGreen() * 3 + 0xE8) / 4, (c.getBlue() * 3 + 0xFF) / 4);
  }

  /** Where a component's tooltip opens: just above it, so it never covers the buttons. */
  private static Point above(final JComponent c) {
    final String text = c.getToolTipText();
    final JToolTip tip = c.createToolTip();
    tip.setTipText(text == null ? "" : text);
    return new Point(0, -tip.getPreferredSize().height - 2);
  }

  /** A label whose tooltip opens above it. */
  private static final class TipLabel extends JLabel {
    private static final long serialVersionUID = 1L;

    TipLabel(final String text) {
      super(text, SwingConstants.CENTER);
    }

    TipLabel(final String text, final int alignment) {
      super(text, alignment);
    }

    @Override
    public Point getToolTipLocation(final MouseEvent e) {
      return above(this);
    }
  }

  /** A button whose tooltip opens above it. */
  private static final class TipButton extends JButton {
    private static final long serialVersionUID = 1L;

    TipButton(final String text) {
      super(text);
    }

    @Override
    public Point getToolTipLocation(final MouseEvent e) {
      return above(this);
    }
  }

  /** The key above the grid: a swatch of each cell style. */
  private JComponent key() {
    final JPanel key = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    key.add(swatch(USABLE, false, null));
    key.add(keyText("can move here"));
    key.add(Box.createHorizontalStrut(10));
    key.add(swatch(USABLE, false, AMBER));
    key.add(keyText("can move here, no targets this round"));
    key.add(Box.createHorizontalStrut(10));
    key.add(swatch(GREY, true, null));
    key.add(keyText("can't move here (reason shown)"));
    key.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 6));
    return key;
  }

  private static JLabel keyText(final String text) {
    final JLabel l = new JLabel(text);
    l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
    l.setForeground(TEXT);
    l.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
    return l;
  }

  private static JComponent swatch(
      final Color back, final boolean hatched, final @Nullable Color mark) {
    final JComponent s =
        new JComponent() {
          private static final long serialVersionUID = 1L;

          @Override
          protected void paintComponent(final Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(back);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (hatched) {
              paintHatch(g, getWidth(), getHeight());
            }
            if (mark != null) {
              g.setColor(mark);
              g.fillRect(5, getHeight() - 5, getWidth() - 10, 2);
            }
            g.setColor(LINES.darker());
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.dispose();
          }
        };
    s.setPreferredSize(new Dimension(26, 16));
    return s;
  }

  /** The fronts this row can move to this round. */
  private List<Territory> reach(final Row row) {
    final List<Territory> result = new ArrayList<>();
    for (final Territory column : columns) {
      if (!column.equals(row.from()) && problem(row, column).isEmpty()) {
        result.add(column);
      }
    }
    return result;
  }

  private static JButton small(final String text) {
    final JButton b = new TipButton(text);
    b.setMargin(new Insets(0, 5, 0, 5));
    b.setFocusable(false);
    return b;
  }

  private Optional<String> problem(final Row row, final Territory column) {
    if (!AirAllocation.canMove(host.player(), row.type(), row.from(), column)) {
      return Optional.of(farReason(row, column));
    }
    return entry.get(key(row.type(), column));
  }

  // ---- request 9 item 3: say why a front is closed and how to get there

  /** "Japanese" for the Japanese, "German" for the Germans: for "no German carriers here". */
  private static String adjective(final GamePlayer player) {
    // request 32: "America Pacific" when the game gives the player a display name
    final String shown = games.strategy.triplea.formatter.PlayerNames.display(player);
    if (!shown.equals(player.getName())) {
      return shown;
    }
    final String name = player.getName();
    return name.endsWith("ns") ? name.substring(0, name.length() - 1) : name;
  }

  /**
   * The full reason a front is closed to this row, naming the front: "Philippine Sea: carrier-only
   * — no Japanese carriers here (0 spots)", "Americas: no Japanese landing spot".
   */
  private String fullReason(final Row row, final Territory column, final String reason) {
    if (reason.matches("^\\d+ rounds.*") || reason.startsWith("Can't be reached")) {
      return reason;
    }
    final String nation = adjective(host.player());
    if (reason.startsWith("No carriers")) {
      return zoneName(column) + ": carrier-only — no " + nation + " carriers here (0 spots)";
    }
    if (reason.startsWith("No landing spot")) {
      return zoneName(column) + ": no " + nation + " landing spot";
    }
    return zoneName(column) + ": " + carrierDetail(column, host.staged(), reason);
  }

  /** What a front on the way needs: "needs carriers", "needs a landing spot", or nothing. */
  private String need(final Row row, final Territory via) {
    final Optional<String> p = entry.get(key(row.type(), via));
    if (p == null || p.isEmpty()) {
      return "";
    }
    if (p.get().startsWith("No carriers")) {
      return " (needs carriers)";
    }
    if (p.get().startsWith("No landing spot")) {
      return " (needs a landing spot)";
    }
    return " (closed: " + shortReason(p.get()).toLowerCase() + ")";
  }

  /**
   * How to reach a front more than one step away: "2 rounds: via Philippine Sea (needs carriers) or
   * North Pacific (needs carriers)".
   */
  private String farReason(final Row row, final Territory column) {
    final List<List<Territory>> routes = routes(row.from(), column);
    if (routes.isEmpty()) {
      return "Can't be reached from " + zoneName(row.from()) + ".";
    }
    final int rounds = routes.get(0).size();
    final List<String> firstSteps = new ArrayList<>();
    for (final List<Territory> route : routes) {
      final String step = zoneName(route.get(0)) + need(row, route.get(0));
      if (!firstSteps.contains(step)) {
        firstSteps.add(step);
      }
    }
    final StringBuilder sb =
        new StringBuilder(rounds + " rounds: via " + String.join(" or ", firstSteps));
    if (rounds > 2) {
      final List<Territory> first = routes.get(0);
      sb.append(", then ");
      sb.append(
          String.join(
              ", then ",
              first.subList(1, first.size() - 1).stream()
                  .map(z -> zoneName(z) + need(row, z))
                  .toList()));
    }
    final String there = need(row, column);
    if (!there.isEmpty()) {
      sb.append("; ").append(zoneName(column)).append(" itself").append(there);
    }
    return sb.append('.').toString();
  }

  /**
   * The shortest ways from one front to another (up to 4), each as the fronts entered in order,
   * ending with the destination; empty if there is no way.
   */
  private List<List<Territory>> routes(final Territory from, final Territory to) {
    final Map<Territory, Integer> distance = new HashMap<>();
    final Map<Territory, List<Territory>> parents = new HashMap<>();
    final ArrayDeque<Territory> queue = new ArrayDeque<>();
    distance.put(from, 0);
    queue.add(from);
    while (!queue.isEmpty()) {
      final Territory z = queue.poll();
      for (final Territory n : AirZoneAttachment.get(z).orElseThrow().getAdjacentZones()) {
        if (!distance.containsKey(n)) {
          distance.put(n, distance.get(z) + 1);
          queue.add(n);
        }
        if (distance.get(n) == distance.get(z) + 1) {
          parents.computeIfAbsent(n, k -> new ArrayList<>()).add(z);
        }
      }
    }
    if (!distance.containsKey(to) || to.equals(from)) {
      return List.of();
    }
    final List<List<Territory>> result = new ArrayList<>();
    collect(to, from, parents, new ArrayList<>(), result);
    return result;
  }

  private static void collect(
      final Territory at,
      final Territory from,
      final Map<Territory, List<Territory>> parents,
      final List<Territory> tail,
      final List<List<Territory>> result) {
    if (result.size() >= 4) {
      return;
    }
    if (at.equals(from)) {
      final List<Territory> route = new ArrayList<>(tail);
      java.util.Collections.reverse(route);
      result.add(route);
      return;
    }
    tail.add(at);
    for (final Territory p : parents.getOrDefault(at, List.of())) {
      collect(p, from, parents, tail, result);
    }
    tail.remove(tail.size() - 1);
  }

  /** The fronts on the ways to a far front, for the mini map (empty for a neighbour). */
  private List<Territory> routeZones(final Territory from, final Territory to) {
    if (AirAllocation.canMove(from, to)) {
      return List.of();
    }
    final List<Territory> zones = new ArrayList<>();
    for (final List<Territory> route : routes(from, to)) {
      for (final Territory z : route) {
        if (!zones.contains(z)) {
          zones.add(z);
        }
      }
    }
    return zones;
  }

  /** "Philippine Sea — no carriers; Manchuria — no landing spot" for a row that can't move. */
  private String cantMoveText(final Row row) {
    final List<String> parts = new ArrayList<>();
    for (final Territory n : AirZoneAttachment.get(row.from()).orElseThrow().getAdjacentZones()) {
      problem(row, n).ifPresent(p -> parts.add(zoneName(n) + " — " + shortReason(p).toLowerCase()));
    }
    return parts.isEmpty() ? "no neighbouring fronts" : String.join("; ", parts);
  }

  private Optional<String> plusProblem(
      final Row row, final Territory column, final List<AirMove> staged, final int left) {
    if (left <= 0) {
      return Optional.of("All of this group is already moving.");
    }
    if (AirZoneAttachment.get(column).orElseThrow().getZoneType() == ZoneType.OCEAN) {
      try (GameData.Unlocker ignored = host.data().acquireReadLock()) {
        return AirAllocation.destinationProblem(
            host.player(), host.stage(), staged, row.from(), row.type(), 1, column);
      }
    }
    return Optional.empty();
  }

  /** Carrier spaces the nation's fighters take in the front (held for them in the bomber step). */
  private static int fighterCarrierCost(final GamePlayer player, final Territory zone) {
    return zone
        .getUnitCollection()
        .getMatches(u -> u.isOwnedBy(player) && AirAllocation.isInStage(u, Stage.FIGHTERS))
        .stream()
        .mapToInt(u -> Math.max(0, u.getUnitAttachment().getCarrierCost()))
        .sum();
  }

  private JComponent footerLabel(final String text) {
    final JLabel l = new JLabel(text);
    l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
    l.setForeground(TEXT);
    l.setOpaque(true);
    l.setBackground(FOOTER);
    l.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 8));
    return l;
  }

  private JComponent footerCell(final String text, final boolean bold) {
    final JLabel l =
        new JLabel("<html><center>" + text + "</center></html>", SwingConstants.CENTER);
    l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, 12f));
    l.setForeground(TEXT);
    l.setOpaque(true);
    l.setBackground(FOOTER);
    l.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
    return l;
  }

  // ---- hover and selection: drive the small map and the main map

  /** Hovering the component (or anything in it) shows its row and column on the maps. */
  private void track(
      final Component component, final @Nullable Row row, final @Nullable Territory column) {
    component.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(final MouseEvent e) {
            hoverRow = row;
            hoverColumn = column;
            host.hoverZone(column);
            miniMap.repaint();
            grid.repaint();
          }

          @Override
          public void mouseExited(final MouseEvent e) {
            hoverRow = null;
            hoverColumn = null;
            host.hoverZone(null);
            miniMap.repaint();
            grid.repaint();
          }
        });
    if (component instanceof Container container) {
      for (final Component child : container.getComponents()) {
        track(child, row, column);
      }
    }
  }

  // ---- counting

  private static int movedTo(final Row row, final Territory column, final List<AirMove> staged) {
    return staged.stream()
        .filter(
            m ->
                m.from().equals(row.from())
                    && m.to().equals(column)
                    && m.unitType().equals(row.type()))
        .mapToInt(AirMove::count)
        .sum();
  }

  private static int movedOut(final Row row, final List<AirMove> staged) {
    return staged.stream()
        .filter(m -> m.from().equals(row.from()) && m.unitType().equals(row.type()))
        .mapToInt(AirMove::count)
        .sum();
  }

  /** The nation's aircraft of this step (bombers or fighters) in the front once moves are made. */
  private int totalAfter(
      final GamePlayer player, final Territory zone, final List<AirMove> staged) {
    int count;
    try (GameData.Unlocker ignored = host.data().acquireReadLock()) {
      count = AirAllocation.getAircraft(player, zone, host.stage()).size();
    }
    for (final AirMove m : staged) {
      if (m.from().equals(zone)) {
        count -= m.count();
      }
      if (m.to().equals(zone)) {
        count += m.count();
      }
    }
    return count;
  }

  /**
   * Allies: their aircraft in the front once their staged moves are made. Enemies: their aircraft
   * as last revealed (where the map shows them). "Italy<br>
   * 1 fighter, 1 bomber".
   */
  private String othersText(final Territory zone, final boolean allies) {
    final GamePlayer player = host.player();
    final List<String> parts = new ArrayList<>();
    try (GameData.Unlocker ignored = host.data().acquireReadLock()) {
      for (final GamePlayer other : host.data().getPlayerList().getPlayers()) {
        if (other.equals(player)) {
          continue;
        }
        final boolean allied = host.data().getRelationshipTracker().isAllied(player, other);
        final boolean enemy = host.data().getRelationshipTracker().isAtWar(player, other);
        if ((allies && !allied) || (!allies && !enemy)) {
          continue;
        }
        int fighters = 0;
        int bombers = 0;
        for (final Unit u :
            zone.getUnitCollection()
                .getMatches(u -> u.isOwnedBy(other) && Matches.unitIsAir().test(u))) {
          if (isFighter(u.getType())) {
            fighters++;
          } else {
            bombers++;
          }
        }
        if (allies) {
          for (final AirMove m : host.allied().getOrDefault(other, List.of())) {
            final int sign = m.to().equals(zone) ? 1 : m.from().equals(zone) ? -1 : 0;
            if (isFighter(m.unitType())) {
              fighters += sign * m.count();
            } else {
              bombers += sign * m.count();
            }
          }
        }
        if (fighters > 0 || bombers > 0) {
          final List<String> units = new ArrayList<>();
          if (fighters > 0) {
            units.add(fighters + (fighters == 1 ? " fighter" : " fighters"));
          }
          if (bombers > 0) {
            units.add(bombers + (bombers == 1 ? " bomber" : " bombers"));
          }
          parts.add(
              "<b>" + AirZoneOverlay.nationName(other) + "</b><br>" + String.join(", ", units));
        }
      }
    }
    return parts.isEmpty() ? "–" : String.join("<br>", parts);
  }

  private static boolean isFighter(final UnitType type) {
    return AirUnitAttachment.get(type).map(AirUnitAttachment::isFighter).orElse(false);
  }

  private static String key(final UnitType type, final Territory zone) {
    return type.getName() + "|" + zone.getName();
  }

  private static String rowName(final Row row) {
    return row.type().getName() + "@" + row.from().getName();
  }

  private static String zoneName(final Territory zone) {
    return AirAllocation.zoneName(zone);
  }

  // ---- changes

  /** Makes the moves (asking first if they overload carriers); false if the player said no. */
  private boolean change(final List<AirMove> moves) {
    final Optional<String> overload = newOverload(host.staged(), moves);
    if (overload.isPresent() && !confirm.test(overload.get())) {
      return false;
    }
    undo.push(List.copyOf(host.staged()));
    host.setStaged(moves);
    refresh();
    return true;
  }

  /**
   * Asks the player to confirm a carrier overload (package-private so tests can answer it). Returns
   * true to go ahead.
   */
  java.util.function.Predicate<String> confirm =
      message ->
          JOptionPane.showConfirmDialog(
                  this,
                  message,
                  "Carriers overloaded",
                  JOptionPane.YES_NO_OPTION,
                  JOptionPane.WARNING_MESSAGE)
              == JOptionPane.YES_OPTION;

  /**
   * In the bomber step: the question to ask if these moves put more aircraft on a front's carriers
   * than before and more than they hold, counting the fighters already there.
   */
  private Optional<String> newOverload(final List<AirMove> before, final List<AirMove> after) {
    if (host.stage() != Stage.BOMBERS) {
      return Optional.empty();
    }
    try (GameData.Unlocker ignored = host.data().acquireReadLock()) {
      for (final Territory zone : columns) {
        final int was = AirAllocation.carrierOverload(host.player(), before, zone);
        final int now = AirAllocation.carrierOverload(host.player(), after, zone);
        if (now > was) {
          final CarrierLoad load =
              AirAllocation.carrierLoadAfter(host.player(), host.stage(), after, zone);
          final int fighters = fighterCarrierCost(host.player(), zone);
          return Optional.of(
              "<html>"
                  + zoneName(zone)
                  + ": your carriers hold "
                  + load.capacity()
                  + ".<br>This puts "
                  + load.cost()
                  + " bombers there beside "
                  + fighters
                  + " fighters.<br><br><b>In fighter allocation you will have to move "
                  + now
                  + (now == 1 ? " fighter" : " fighters")
                  + " off "
                  + zoneName(zone)
                  + ".</b><br><br>Make this move?</html>");
        }
      }
    }
    return Optional.empty();
  }

  /**
   * "3 targets" (or amber "no targets this round") for the row's type in the column, with what they
   * are on hover; null for fighters, which have no targets of their own.
   */
  private @Nullable JLabel targetNote(final Row row, final Territory column) {
    final Integer targets = targetCount.get(key(row.type(), column));
    if (targets == null) {
      return null;
    }
    final JLabel note;
    if (targets == 0) {
      note = new TipLabel("<html><center>no targets<br>this round</center></html>");
      note.setFont(note.getFont().deriveFont(Font.BOLD, 10f));
      note.setForeground(AMBER);
      note.setToolTipText(noTargetsText(row, column));
    } else if (LongRangeRaid.allowsMove(host.player(), row.type(), row.from(), column)) {
      // request 26: the Amerika Bomber's raid, straight from its launch box
      note =
          new TipLabel(
              "<html><center><b>"
                  + LongRangeRaid.LABEL
                  + "</b><br>"
                  + targets
                  + (targets == 1 ? " target" : " targets")
                  + "</center></html>");
      note.setFont(note.getFont().deriveFont(10f));
      note.setForeground(TEXT);
      note.setToolTipText(
          "<html><b>"
              + LongRangeRaid.LABEL
              + "</b>: straight from "
              + zoneName(row.from())
              + ", beyond normal range.<br>Factories it may bomb now: "
              + String.join(
                  ", ",
                  AirCombat.getTargets(host.player(), column, row.type()).stream()
                      .map(t -> t.territory().getName())
                      .toList())
              + ".<br>Only factories in these territories can be raided: "
              + String.join(", ", LongRangeRaid.targets(host.data()))
              + ".</html>");
    } else {
      note = new TipLabel(targets + (targets == 1 ? " target" : " targets"));
      note.setFont(note.getFont().deriveFont(10f));
      note.setForeground(TEXT);
      note.setToolTipText(targetBreakdown(row, column));
    }
    note.setName("targets|" + rowName(row) + "|" + column.getName());
    return note;
  }

  /** "3 Dutch": land targets held by players that never take a turn, or "". */
  private static String turnlessText(
      final GamePlayer player, final Territory zone, final UnitType type) {
    final Map<String, Integer> byOwner = new java.util.TreeMap<>();
    for (final AirCombat.Target t : AirCombat.getTargets(player, zone, type)) {
      final GamePlayer owner = t.territory().getOwner();
      if (t.kind() == TargetKind.LAND
          && games.strategy.triplea.delegate.TurnlessPlayers.isTurnless(owner)) {
        byOwner.merge(owner.getName(), 1, Integer::sum);
      }
    }
    return String.join(
        ", ", byOwner.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList());
  }

  /**
   * "7 targets: 3 land, 4 sea (2 with subs)" or "5 targets: 5 factories/SPCs". Each target is one
   * territory or sea zone the type could strike.
   */
  private String targetBreakdown(final Row row, final Territory column) {
    final Map<TargetKind, Integer> kinds =
        targetKinds.getOrDefault(key(row.type(), column), Map.of());
    final int land = kinds.getOrDefault(TargetKind.LAND, 0);
    final int subs = kinds.getOrDefault(TargetKind.SUBS, 0);
    final int sea = kinds.getOrDefault(TargetKind.SURFACE, 0) + subs;
    final int strategic = kinds.getOrDefault(TargetKind.STRATEGIC, 0);
    final int total = land + sea + strategic;
    final List<String> parts = new ArrayList<>();
    final int neutral = neutralTargets.getOrDefault(key(row.type(), column), 0);
    if (land > 0) {
      final String turnless = turnlessTargets.getOrDefault(key(row.type(), column), "");
      final List<String> held = new ArrayList<>();
      if (neutral > 0) {
        held.add(neutral + " neutral");
      }
      if (!turnless.isEmpty()) {
        held.add(turnless);
      }
      parts.add(land + " land" + (held.isEmpty() ? "" : " (" + String.join(", ", held) + ")"));
    }
    if (sea > 0) {
      parts.add(sea + " sea" + (subs > 0 ? " (" + subs + " with subs)" : ""));
    }
    if (strategic > 0) {
      parts.add(strategic + " factories/SPCs");
    }
    return "<html>"
        + total
        + (total == 1 ? " target: " : " targets: ")
        + String.join(", ", parts)
        + "<br>"
        + games.strategy.triplea.delegate.air.AirRange.reachText(host.player(), column, row.type())
        + "<br><font size=-2>Each target is one territory or sea zone your "
        + plural(row.type(), 2)
        + "<br>here could strike this round.</font></html>";
  }

  /** The gentle note after moving aircraft into a front where they have nothing to attack. */
  private void noteNoTargets(final Row row, final Territory column) {
    final Integer targets = targetCount.get(key(row.type(), column));
    if (targets != null && targets == 0) {
      status.setText(
          "<html><div style='width:320px'><font color='#8a5a00'>"
              + noTargetsText(row, column)
              + "</font></div></html>");
    }
  }

  private String noTargetsText(final Row row, final Territory column) {
    return "There is nothing in "
        + zoneName(column)
        + " for your "
        + plural(row.type(), 2)
        + " to attack this round. They can still stop there on the way somewhere else. ("
        + games.strategy.triplea.delegate.air.AirRange.reachText(host.player(), column, row.type())
        + ".)";
  }

  private static List<AirMove> adjust(
      final List<AirMove> staged, final Row row, final Territory column, final int delta) {
    final List<AirMove> result = new ArrayList<>();
    boolean found = false;
    for (final AirMove m : staged) {
      if (m.from().equals(row.from()) && m.to().equals(column) && m.unitType().equals(row.type())) {
        found = true;
        final int count = m.count() + delta;
        if (count > 0) {
          result.add(new AirMove(m.from(), m.to(), m.unitType(), count));
        }
      } else {
        result.add(m);
      }
    }
    if (!found && delta > 0) {
      result.add(new AirMove(row.from(), column, row.type(), delta));
    }
    return result;
  }

  /** Shows why Done was refused, in the status line. */
  void showError(final String message) {
    status.setText("<html><font color='#c62828'>" + message + "</font></html>");
  }

  /** Called when the step's moves change elsewhere (the Actions tab's Undo). */
  void movesChangedElsewhere() {
    refresh();
  }

  // ---- the mini map of the fronts

  /**
   * A small world map drawn from the real territories. The selected row's current front is in its
   * colour with a bright outline ("(here)"); the fronts it can reach this round (the grid's usable
   * columns for that row) in their colours, labelled; bombing zones it can reach as a dashed
   * outline of the home region; everything else flat grey. A hovered column is outlined in white.
   */
  private final class MiniMap extends JComponent {
    private static final long serialVersionUID = 1L;
    private static final int MAP_WIDTH = 440;
    private static final int MAP_HEIGHT = 230;
    private static final int TITLE = 22;
    private static final int OX = 6;
    private static final Color BACK = new Color(0x2B, 0x30, 0x37);
    private static final Color SEA = new Color(0x45, 0x4A, 0x52);
    private static final Color LAND = new Color(0x6B, 0x70, 0x78);
    private static final Color HERE = new Color(0xFF, 0xF5, 0x9D);
    private final Map<Territory, ZoneShape> shapes = new LinkedHashMap<>();
    private final Map<Territory, Rectangle> shapeBounds = new HashMap<>();
    private final Map<Territory, Point> labelAt = new HashMap<>();
    private final Map<Territory, Color> colors;
    private final Set<Territory> bombZones = new LinkedHashSet<>();
    private final List<Polygon> land = new ArrayList<>();
    private final Rectangle world;
    // the land drawn for the last view, kept while the view stays the same
    private @Nullable Rectangle baseView;
    private @Nullable BufferedImage base;

    MiniMap() {
      final MapData mapData = uiContext.getMapData();
      final GameData data = host.data();
      Rectangle box = null;
      try (GameData.Unlocker ignored = data.acquireReadLock()) {
        colors = AirMapMode.frontColors(data);
        for (final Territory t : data.getMap().getTerritories()) {
          if (AirZoneAttachment.isAirZone(t)) {
            continue;
          }
          for (final Polygon p : mapData.getPolygons(t)) {
            box = box == null ? p.getBounds() : box.union(p.getBounds());
            if (!t.isWater()) {
              land.add(p);
            }
          }
        }
        final int width = mapData.getMapDimensions().width;
        for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
          final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
          final List<Territory> covered = new ArrayList<>(a.getCoveredTerritories());
          if (covered.isEmpty() || a.getZoneType() == ZoneType.RESERVE) {
            continue;
          }
          shapes.put(zone, ZoneShape.of(covered, mapData));
          Rectangle r = null;
          for (final Territory t : covered) {
            for (final Polygon p : mapData.getPolygons(t)) {
              r = r == null ? p.getBounds() : r.union(p.getBounds());
            }
          }
          if (r != null) {
            shapeBounds.put(zone, r);
          }
          labelAt.put(zone, AirMapMode.medoid(covered, mapData, width));
          if (a.getZoneType() == ZoneType.BOMB) {
            bombZones.add(zone);
          }
        }
      }
      world = box == null ? new Rectangle(0, 0, 1, 1) : box;
      setPreferredSize(new Dimension(MAP_WIDTH + 2 * OX, MAP_HEIGHT + TITLE + 6));
    }

    /**
     * The part of the world to show: the row's fronts with a margin, widened to the panel's shape,
     * at least a quarter of the world wide, kept inside the world where it fits.
     */
    private Rectangle view(final List<Territory> zones) {
      Rectangle r = null;
      for (final Territory zone : zones) {
        final Rectangle b = shapeBounds.get(zone);
        if (b != null) {
          r = r == null ? new Rectangle(b) : r.union(b);
        }
      }
      if (r == null) {
        r = new Rectangle(world);
      }
      double w = Math.max(r.width * 1.3, world.width / 4.0);
      double h = Math.max(r.height * 1.3, w * MAP_HEIGHT / MAP_WIDTH);
      w = Math.max(w, h * MAP_WIDTH / MAP_HEIGHT);
      if (w > world.width) {
        w = world.width;
        h = w * MAP_HEIGHT / MAP_WIDTH;
      }
      double x = r.getCenterX() - w / 2;
      double y = r.getCenterY() - h / 2;
      x = Math.max(world.x, Math.min(world.x + world.width - w, x));
      y =
          h >= world.height
              ? world.y - (h - world.height) / 2
              : Math.max(world.y, Math.min(world.y + world.height - h, y));
      return new Rectangle((int) x, (int) y, (int) Math.ceil(w), (int) Math.ceil(h));
    }

    private BufferedImage base(final Rectangle view) {
      if (base == null || !view.equals(baseView)) {
        final BufferedImage img =
            new BufferedImage(MAP_WIDTH, MAP_HEIGHT, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(SEA);
        g.fillRect(0, 0, MAP_WIDTH, MAP_HEIGHT);
        final double scale = MAP_WIDTH / (double) view.width;
        g.scale(scale, scale);
        g.translate(-view.x, -view.y);
        g.setColor(LAND);
        for (final Polygon p : land) {
          if (p.getBounds().intersects(view)) {
            g.fillPolygon(p);
          }
        }
        g.dispose();
        base = img;
        baseView = view;
      }
      return base;
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
      final Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setColor(BACK);
      g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
      final Row row = hoverRow != null ? hoverRow : selectedRow;
      g.setColor(Color.WHITE);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
      g.drawString(title(row), 8, 16);
      final int oy = TITLE;
      final List<Territory> reach = new ArrayList<>();
      // a hovered far front: the fronts on the way to it are shown too, outlined dashed
      final List<Territory> route =
          row != null
                  && row == hoverRow
                  && hoverColumn != null
                  && !LongRangeRaid.allowsMove(host.player(), row.type(), row.from(), hoverColumn)
              ? routeZones(row.from(), hoverColumn)
              : List.of();
      if (row != null) {
        reach.addAll(reach(row));
        reach.add(row.from());
        route.stream().filter(z -> !reach.contains(z)).forEach(reach::add);
      }
      final Rectangle view = view(reach);
      final double scale = MAP_WIDTH / (double) view.width;
      g.drawImage(base(view), OX, oy, null);
      if (row == null) {
        g.dispose();
        return;
      }
      final Graphics2D m = (Graphics2D) g.create();
      m.clipRect(OX, oy, MAP_WIDTH, MAP_HEIGHT);
      m.translate(OX, oy);
      m.scale(scale, scale);
      m.translate(-view.x, -view.y);
      final float px = (float) (1 / scale);
      // fronts in their colours (the air map's, unless another front shown here has it already),
      // then outlines on top
      final Set<Color> used = new LinkedHashSet<>();
      final List<Territory> homeFirst = new ArrayList<>(reach);
      homeFirst.remove(row.from());
      homeFirst.add(0, row.from());
      for (final Territory zone : homeFirst) {
        final ZoneShape shape = shapes.get(zone);
        if (shape != null && colors.containsKey(zone)) {
          Color color = colors.get(zone);
          for (int i = 0; used.contains(color) && i < AirMapMode.PALETTE.length; i++) {
            color = AirMapMode.PALETTE[i];
          }
          used.add(color);
          m.setColor(color);
          shape.fill(m);
          m.setColor(color.darker());
          m.setStroke(new BasicStroke(px));
          shape.draw(m);
        }
      }
      for (final Territory zone : reach) {
        final ZoneShape shape = shapes.get(zone);
        if (shape != null && bombZones.contains(zone)) {
          m.setColor(Color.BLACK);
          m.setStroke(new BasicStroke(4 * px));
          shape.draw(m);
          m.setColor(Color.WHITE);
          m.setStroke(
              new BasicStroke(
                  2.5f * px,
                  BasicStroke.CAP_BUTT,
                  BasicStroke.JOIN_ROUND,
                  10f,
                  new float[] {6 * px, 4 * px},
                  0f));
          shape.draw(m);
        }
      }
      final ZoneShape home = shapes.get(row.from());
      if (home != null) {
        m.setColor(Color.BLACK);
        m.setStroke(new BasicStroke(4.5f * px));
        home.draw(m);
        m.setColor(HERE);
        m.setStroke(new BasicStroke(2.5f * px));
        home.draw(m);
      }
      for (final Territory zone : route) {
        final ZoneShape shape = shapes.get(zone);
        if (shape != null) {
          m.setColor(Color.WHITE);
          m.setStroke(
              new BasicStroke(
                  2.5f * px,
                  BasicStroke.CAP_BUTT,
                  BasicStroke.JOIN_ROUND,
                  10f,
                  new float[] {7 * px, 5 * px},
                  0f));
          shape.draw(m);
        }
      }
      if (hoverColumn != null && shapes.containsKey(hoverColumn)) {
        m.setColor(Color.WHITE);
        m.setStroke(new BasicStroke(3.5f * px));
        shapes.get(hoverColumn).draw(m);
      }
      m.dispose();
      // labels, the current front's first; a label that would cover another moves down
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
      final FontMetrics fm = g.getFontMetrics();
      final List<Rectangle> placed = new ArrayList<>();
      final List<Territory> order = new ArrayList<>(reach);
      order.remove(row.from());
      order.add(0, row.from());
      for (final Territory zone : order) {
        final Point at = labelAt.get(zone);
        if (at == null) {
          continue;
        }
        final boolean here = zone.equals(row.from());
        final String text = zoneName(zone) + (here ? " (here)" : "");
        final int w = fm.stringWidth(text) + 6;
        final int x =
            Math.max(
                OX, Math.min(OX + MAP_WIDTH - w, OX + (int) ((at.x - view.x) * scale) - w / 2));
        int y =
            Math.max(oy, Math.min(oy + MAP_HEIGHT - 15, oy + (int) ((at.y - view.y) * scale) - 7));
        Rectangle box = new Rectangle(x, y, w, 15);
        for (int tries = 0; tries < 8 && overlaps(box, placed); tries++) {
          y = y + 16 > oy + MAP_HEIGHT - 15 ? oy : y + 16;
          box = new Rectangle(x, y, w, 15);
        }
        placed.add(box);
        g.setColor(
            zone.equals(hoverColumn) ? new Color(0, 0, 0, 235) : new Color(0x1E, 0x22, 0x28, 215));
        g.fillRect(x, y, w, 15);
        g.setColor(here ? HERE : Color.WHITE);
        g.drawString(text, x + 3, y + 12);
      }
      g.dispose();
    }

    private boolean overlaps(final Rectangle box, final List<Rectangle> placed) {
      return placed.stream().anyMatch(box::intersects);
    }

    private String title(final @Nullable Row row) {
      if (row == null) {
        return "No " + aircraftWord() + " to move this round";
      }
      final String plural = AirAllocation.typeName(row.type()).toLowerCase() + "s";
      final boolean sameTypeElsewhere =
          rows.stream().anyMatch(r -> r.type().equals(row.type()) && !r.equals(row));
      return "Where your "
          + plural
          + (sameTypeElsewhere ? " in " + zoneName(row.from()) : "")
          + " can go this round";
    }
  }
}
