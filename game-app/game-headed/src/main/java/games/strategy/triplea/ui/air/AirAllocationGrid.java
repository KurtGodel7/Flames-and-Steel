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
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel allocation grid: one window per allocation step. Rows are the nation's aircraft
 * groups (type and current box); columns are the boxes it could use this round. A cell's + moves
 * one aircraft from the row's current box into that column and − moves it back, so every aircraft
 * is always somewhere. Cells where the type cannot go are greyed with the reason; usable cells of
 * aircraft that strike show their number of targets. Bottom rows show the nation's totals, allies'
 * moves so far and enemies' last revealed aircraft.
 */
final class AirAllocationGrid extends JDialog {
  private static final long serialVersionUID = 1L;
  private static final Color CURRENT = new Color(0xFF, 0xF1, 0xC4);
  private static final Color GREY = new Color(0xDD, 0xDD, 0xDD);
  private static final Color GREY_TEXT = new Color(0x88, 0x88, 0x88);
  private static final Color USABLE = Color.WHITE;
  private static final Color MOVED = new Color(0xD8, 0xF5, 0xDC);
  private static final Color OCEAN = new Color(0xE0, 0xA8, 0x00);

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

  /** One row: the nation's aircraft of a type in a box. */
  private record Row(Territory from, UnitType type, int count) {}

  private final Host host;
  private final UiContext uiContext;
  private final List<Row> rows = new ArrayList<>();
  private final List<Territory> columns = new ArrayList<>();
  // per (type, column): why the type cannot be there (empty if it can), and its target count
  private final Map<String, Optional<String>> entry = new HashMap<>();
  private final Map<String, Integer> targetCount = new HashMap<>();
  private final Deque<List<AirMove>> undo = new ArrayDeque<>();
  private final JLabel header = new JLabel();
  private final JLabel status = new JLabel();
  private final JPanel grid = new JPanel(new GridBagLayout());
  private final JButton undoButton = new JButton("Undo");
  private final AdjacencyDiagram diagram;

  AirAllocationGrid(final Frame owner, final UiContext uiContext, final Host host) {
    super(owner, host.player().getName() + " air allocation", false);
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
    diagram = new AdjacencyDiagram();
    computeLayout();

    header.setFont(header.getFont().deriveFont(Font.BOLD, 15f));
    status.setFont(status.getFont().deriveFont(Font.PLAIN, 13f));
    final JPanel top = new JPanel(new BorderLayout());
    final JPanel titles = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    titles.add(header, c);
    c.gridy = 1;
    titles.add(status, c);
    top.add(titles, BorderLayout.CENTER);
    top.add(diagram, BorderLayout.EAST);
    top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

    final JButton reset = new JButton("Reset");
    reset.addActionListener(e -> change(List.of()));
    undoButton.addActionListener(
        e -> {
          if (!undo.isEmpty()) {
            host.setStaged(undo.pop());
            refresh();
          }
        });
    final JButton done = new JButton("Done");
    done.setFont(done.getFont().deriveFont(Font.BOLD));
    done.addActionListener(e -> host.done());
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
    buttons.add(undoButton);
    buttons.add(reset);
    buttons.add(done);

    getContentPane().setLayout(new BorderLayout());
    getContentPane().add(top, BorderLayout.NORTH);
    final JScrollPane scroll = new JScrollPane(grid);
    scroll.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
    getContentPane().add(scroll, BorderLayout.CENTER);
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
          if (problem.isEmpty() && !isFighter(type)) {
            targetCount.put(key(type, zone), AirCombat.countTargets(player, zone, type));
          }
        }
      }
      // the nation's current boxes, then every box next to one that some row may use
      for (final Territory zone : zones) {
        final boolean usable =
            rows.stream()
                .anyMatch(
                    r ->
                        AirAllocation.canMove(r.from(), zone)
                            && entry.get(key(r.type(), zone)).isEmpty());
        if (current.contains(zone) || usable) {
          columns.add(zone);
        }
      }
    }
  }

  private void refresh() {
    final GamePlayer player = host.player();
    final List<AirMove> staged = host.staged();
    final int moving = staged.stream().mapToInt(AirMove::count).sum();
    header.setText(
        player.getName()
            + "  ·  round "
            + host.round()
            + "  ·  step "
            + (host.stage() == Stage.BOMBERS ? "1 of 2: bombers" : "2 of 2: fighters"));
    final int total = rows.stream().mapToInt(Row::count).sum();
    status.setText(
        total == 0
            ? "No " + (host.stage() == Stage.BOMBERS ? "bombers" : "fighters") + " to allocate."
            : moving == 0
                ? "All " + total + " aircraft stay where they are. Use + to move them."
                : moving + " of " + total + " aircraft moving; the rest stay.");
    undoButton.setEnabled(!undo.isEmpty());
    grid.removeAll();
    final GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.BOTH;
    c.insets = new Insets(1, 1, 1, 1);
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
        grid.add(cell(row, columns.get(i), staged), c);
      }
    }
    // totals and other nations
    c.gridy++;
    c.gridx = 0;
    grid.add(footerLabel("Your aircraft after moves"), c);
    for (int i = 0; i < columns.size(); i++) {
      c.gridx = i + 1;
      grid.add(footerCell(String.valueOf(totalAfter(player, columns.get(i), staged))), c);
    }
    c.gridy++;
    c.gridx = 0;
    grid.add(footerLabel("Allies (moves so far)"), c);
    for (int i = 0; i < columns.size(); i++) {
      c.gridx = i + 1;
      grid.add(footerCell(othersText(columns.get(i), true)), c);
    }
    c.gridy++;
    c.gridx = 0;
    grid.add(footerLabel("Enemies (last revealed)"), c);
    for (int i = 0; i < columns.size(); i++) {
      c.gridx = i + 1;
      grid.add(footerCell(othersText(columns.get(i), false)), c);
    }
    grid.revalidate();
    grid.repaint();
    diagram.repaint();
  }

  private JComponent headerCell(final String text) {
    final JLabel l = new JLabel(text, SwingConstants.CENTER);
    l.setFont(l.getFont().deriveFont(Font.BOLD));
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
        text.append("<br><font size=-2>carriers ")
            .append(load.cost())
            .append('/')
            .append(load.capacity());
        if (load.jetCost() > 0 || load.fleetCapacity() > 0) {
          text.append(", jets ").append(load.jetCost()).append('/').append(load.fleetCapacity());
        }
        text.append("</font>");
      }
    }
    final JLabel l = new JLabel(text.append("</center></html>").toString(), SwingConstants.CENTER);
    l.setOpaque(true);
    l.setBackground(new Color(0xEC, 0xEF, 0xF1));
    l.setBorder(
        a.getZoneType() == ZoneType.OCEAN
            ? BorderFactory.createMatteBorder(0, 0, 3, 0, OCEAN)
            : BorderFactory.createEmptyBorder(4, 6, 4, 6));
    l.setToolTipText("Point here to see " + AirAllocation.zoneName(zone) + " on the map");
    l.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(final MouseEvent e) {
            host.hoverZone(zone);
            diagram.highlight(zone);
          }

          @Override
          public void mouseExited(final MouseEvent e) {
            host.hoverZone(null);
            diagram.highlight(null);
          }
        });
    return l;
  }

  private JComponent rowHeader(final Row row) {
    final JLabel l =
        new JLabel(
            "<html><b>"
                + AirAllocation.typeName(row.type())
                + " × "
                + row.count()
                + "</b><br><font size=-2>now: "
                + AirAllocation.zoneName(row.from())
                + "</font></html>",
            uiContext
                .getUnitImageFactory()
                .getIcon(ImageKey.builder().player(host.player()).type(row.type()).build()),
            SwingConstants.LEFT);
    l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 8));
    return l;
  }

  private JComponent cell(final Row row, final Territory column, final List<AirMove> staged) {
    final int moved = movedTo(row, column, staged);
    final int left = row.count() - movedOut(row, staged);
    final JPanel p = new JPanel(new BorderLayout(2, 0));
    p.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
    if (column.equals(row.from())) {
      p.setBackground(CURRENT);
      final JLabel l = new JLabel(String.valueOf(left), SwingConstants.CENTER);
      l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
      l.setToolTipText("Stay in " + AirAllocation.zoneName(column));
      p.add(l, BorderLayout.CENTER);
      return p;
    }
    final Optional<String> problem = problem(row, column);
    if (problem.isPresent()) {
      p.setBackground(GREY);
      final JLabel l = new JLabel("–", SwingConstants.CENTER);
      l.setForeground(GREY_TEXT);
      l.setToolTipText(problem.get());
      p.setToolTipText(problem.get());
      p.add(l, BorderLayout.CENTER);
      return p;
    }
    p.setBackground(moved > 0 ? MOVED : USABLE);
    final JButton minus = small("−");
    minus.setEnabled(moved > 0);
    minus.addActionListener(e -> change(adjust(staged, row, column, -1)));
    final JButton plus = small("+");
    final Optional<String> plusProblem = plusProblem(row, column, staged, left);
    plus.setEnabled(plusProblem.isEmpty());
    plus.setToolTipText(plusProblem.orElse("Move one more " + AirAllocation.typeName(row.type())));
    plus.addActionListener(e -> change(adjust(staged, row, column, 1)));
    final JLabel count = new JLabel(String.valueOf(moved), SwingConstants.CENTER);
    count.setFont(count.getFont().deriveFont(Font.BOLD, 14f));
    final JPanel middle = new JPanel(new BorderLayout());
    middle.setOpaque(false);
    middle.add(count, BorderLayout.CENTER);
    final Integer targets = targetCount.get(key(row.type(), column));
    if (targets != null) {
      final JLabel t =
          new JLabel(targets + (targets == 1 ? " target" : " targets"), SwingConstants.CENTER);
      t.setFont(t.getFont().deriveFont(10f));
      middle.add(t, BorderLayout.SOUTH);
    }
    p.add(minus, BorderLayout.WEST);
    p.add(middle, BorderLayout.CENTER);
    p.add(plus, BorderLayout.EAST);
    return p;
  }

  private static JButton small(final String text) {
    final JButton b = new JButton(text);
    b.setMargin(new Insets(0, 5, 0, 5));
    b.setFocusable(false);
    return b;
  }

  private Optional<String> problem(final Row row, final Territory column) {
    if (!AirAllocation.canMove(row.from(), column)) {
      return Optional.of("Two boxes away: needs two rounds.");
    }
    return entry.get(key(row.type(), column));
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

  private JComponent footerLabel(final String text) {
    final JLabel l = new JLabel(text);
    l.setFont(l.getFont().deriveFont(Font.ITALIC, 12f));
    l.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 8));
    return l;
  }

  private JComponent footerCell(final String text) {
    final JLabel l =
        new JLabel("<html><center>" + text + "</center></html>", SwingConstants.CENTER);
    l.setFont(l.getFont().deriveFont(11f));
    l.setOpaque(true);
    l.setBackground(new Color(0xF4, 0xF4, 0xF4));
    l.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
    return l;
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

  /** All the nation's aircraft in the box once its moves are made (both stages). */
  private int totalAfter(
      final GamePlayer player, final Territory zone, final List<AirMove> staged) {
    int count;
    try (GameData.Unlocker ignored = host.data().acquireReadLock()) {
      count =
          zone.getUnitCollection()
              .countMatches(u -> u.isOwnedBy(player) && Matches.unitIsAir().test(u));
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
   * Allies: their aircraft in the box once their staged moves are made. Enemies: their aircraft as
   * last revealed (where the map shows them). "ITA 2F/1B".
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
          parts.add(AirZoneOverlay.shortName(other) + " " + fighters + "F/" + bombers + "B");
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

  // ---- changes

  private void change(final List<AirMove> moves) {
    undo.push(List.copyOf(host.staged()));
    host.setStaged(moves);
    refresh();
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

  // ---- the small map of boxes

  /** A small diagram of the air boxes and their links, laid out roughly as on the map. */
  private final class AdjacencyDiagram extends JComponent {
    private static final long serialVersionUID = 1L;
    private final Map<Territory, Point> position = new LinkedHashMap<>();
    private @Nullable Territory highlighted;

    AdjacencyDiagram() {
      setPreferredSize(new Dimension(360, 200));
      setToolTipText("Air boxes and their links (reference)");
      layoutNodes();
    }

    void highlight(final @Nullable Territory zone) {
      highlighted = zone;
      repaint();
    }

    private void layoutNodes() {
      final MapData mapData = uiContext.getMapData();
      final GameData data = host.data();
      final Map<Territory, Point> raw = new LinkedHashMap<>();
      try (GameData.Unlocker ignored = data.acquireReadLock()) {
        final int width = mapData.getMapDimensions().width;
        for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
          final List<Territory> covered =
              new ArrayList<>(AirZoneAttachment.get(zone).orElseThrow().getCoveredTerritories());
          if (covered.isEmpty()) {
            continue;
          }
          Point best = null;
          long bestScore = Long.MAX_VALUE;
          for (final Territory a : covered) {
            final Point pa = mapData.getCenter(a);
            long score = 0;
            for (final Territory b : covered) {
              final Point pb = mapData.getCenter(b);
              final int dx = Math.abs(pa.x - pb.x);
              final long wx = Math.min(dx, width - dx);
              final long dy = pa.y - pb.y;
              score += (long) Math.sqrt((double) (wx * wx + dy * dy));
            }
            if (score < bestScore) {
              bestScore = score;
              best = pa;
            }
          }
          final boolean bomb =
              AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.BOMB;
          raw.put(zone, new Point(best.x, best.y));
        }
      }
      if (raw.isEmpty()) {
        return;
      }
      final int minX = raw.values().stream().mapToInt(p -> p.x).min().orElse(0);
      final int maxX = raw.values().stream().mapToInt(p -> p.x).max().orElse(1);
      final int minY = raw.values().stream().mapToInt(p -> p.y).min().orElse(0);
      final int maxY = raw.values().stream().mapToInt(p -> p.y).max().orElse(1);
      raw.forEach(
          (zone, p) ->
              position.put(
                  zone,
                  new Point(
                      24 + (int) ((p.x - minX) * 312.0 / Math.max(1, maxX - minX)) + nudge(zone),
                      14 + (int) ((p.y - minY) * 160.0 / Math.max(1, maxY - minY)) + nudge(zone))));
    }

    /** Bombing zones sit a little below and right of their front, so both can be seen. */
    private int nudge(final Territory zone) {
      return AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.BOMB ? 12 : 0;
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
      final Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(new Color(0x22, 0x2A, 0x33));
      g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
      final Set<Territory> current = new LinkedHashSet<>();
      rows.forEach(r -> current.add(r.from()));
      g.setStroke(new BasicStroke(1f));
      g.setColor(new Color(0x9A, 0xA5, 0xB1));
      position.forEach(
          (zone, p) -> {
            for (final Territory other :
                AirZoneAttachment.get(zone).orElseThrow().getAdjacentZones()) {
              final Point q = position.get(other);
              if (q != null && zone.getName().compareTo(other.getName()) < 0) {
                g.drawLine(p.x, p.y, q.x, q.y);
              }
            }
          });
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
      final FontMetrics fm = g.getFontMetrics();
      position.forEach(
          (zone, p) -> {
            final ZoneType type = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
            final boolean mine = current.contains(zone);
            final boolean usable = columns.contains(zone);
            g.setColor(
                zone.equals(highlighted)
                    ? Color.ORANGE
                    : mine
                        ? new Color(0xFF, 0xD5, 0x4F)
                        : usable ? new Color(0x81, 0xC7, 0x84) : new Color(0x60, 0x6B, 0x75));
            if (type == ZoneType.BOMB) {
              g.fillRect(p.x - 4, p.y - 4, 8, 8);
            } else {
              g.fillOval(p.x - 5, p.y - 5, 10, 10);
            }
            if (type == ZoneType.OCEAN) {
              g.setColor(OCEAN);
              g.drawOval(p.x - 7, p.y - 7, 14, 14);
            }
            g.setColor(Color.WHITE);
            final String label = shortLabel(zone);
            g.drawString(label, p.x - fm.stringWidth(label) / 2, p.y + 15);
          });
      g.dispose();
    }

    /**
     * "B·Ger" for Bomb Germany, "N Atlantic" for North Atlantic and Arctic, else the first word.
     */
    private String shortLabel(final Territory zone) {
      final String[] words = AirAllocation.zoneName(zone).split(" ");
      if (words.length > 1 && words[0].equals("Bomb")) {
        return "B·" + words[1].substring(0, Math.min(3, words[1].length()));
      }
      if (words.length > 1
          && List.of("North", "South", "East", "West", "Western", "Eastern", "Middle")
              .contains(words[0])) {
        return words[0].charAt(0) + " " + words[1];
      }
      return words[0];
    }
  }
}
