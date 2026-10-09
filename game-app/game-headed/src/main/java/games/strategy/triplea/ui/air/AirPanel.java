package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.events.GameDataChangeListener;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.ui.panels.map.MapPanel;
import games.strategy.triplea.ui.research.ResearchView;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Flames and Steel Air tab: a nation's aircraft by type and air zone, with totals. Clicking a zone
 * shows it on the map.
 */
public final class AirPanel extends JPanel {
  private static final long serialVersionUID = 1L;

  private final MapPanel mapPanel;
  private final Consumer<Territory> zoneSelected;
  private final JComboBox<GamePlayer> nationBox = new JComboBox<>();
  private final JLabel summary = new JLabel(" ");
  private final DefaultTableModel model =
      new DefaultTableModel() {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(final int row, final int column) {
          return false;
        }
      };
  private final JTable table = new JTable(model);
  private final List<Territory> rowZones = new ArrayList<>();
  private GameData data;
  private boolean updating;
  private boolean followTurn = true;
  private final AtomicBoolean refreshQueued = new AtomicBoolean();
  private final GameDataChangeListener changeListener = change -> queueRefresh();

  public AirPanel(
      final GameData data, final MapPanel mapPanel, final Consumer<Territory> zoneSelected) {
    super(new BorderLayout());
    this.data = data;
    this.mapPanel = mapPanel;
    this.zoneSelected = zoneSelected;
    nationBox.setRenderer(
        new DefaultListCellRenderer() {
          private static final long serialVersionUID = 1L;

          @Override
          public Component getListCellRendererComponent(
              final JList<?> list,
              final Object value,
              final int index,
              final boolean isSelected,
              final boolean cellHasFocus) {
            return super.getListCellRendererComponent(
                list,
                value instanceof GamePlayer p ? p.getName() : value,
                index,
                isSelected,
                cellHasFocus);
          }
        });
    nationBox.addActionListener(
        e -> {
          if (!updating) {
            followTurn = false;
            refresh();
          }
        });
    final JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    top.add(nationBox);
    top.add(summary);
    add(top, BorderLayout.NORTH);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setFillsViewportHeight(true);
    table.setDefaultRenderer(
        Object.class,
        new DefaultTableCellRenderer() {
          private static final long serialVersionUID = 1L;

          @Override
          public Component getTableCellRendererComponent(
              final JTable t,
              final Object value,
              final boolean isSelected,
              final boolean hasFocus,
              final int row,
              final int column) {
            final Component c =
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
            c.setFont(c.getFont().deriveFont(row == 0 ? Font.BOLD : Font.PLAIN));
            setHorizontalAlignment(column == 0 ? LEFT : CENTER);
            return c;
          }
        });
    table
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              final int row = table.getSelectedRow();
              if (!e.getValueIsAdjusting() && !updating && row > 0 && row < rowZones.size()) {
                final Territory zone = rowZones.get(row);
                mapPanel.centerOn(zone);
                zoneSelected.accept(zone);
              }
            });
    add(new JScrollPane(table), BorderLayout.CENTER);
    setGameData(data);
  }

  public void setGameData(final GameData newData) {
    data.removeDataChangeListener(changeListener);
    data = newData;
    data.addDataChangeListener(changeListener);
    followTurn = true;
    queueRefresh();
  }

  private void queueRefresh() {
    if (refreshQueued.compareAndSet(false, true)) {
      SwingUtilities.invokeLater(
          () -> {
            refreshQueued.set(false);
            refresh();
          });
    }
  }

  private void refresh() {
    final GamePlayer nation;
    final List<Territory> zones;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      nation = chooseNation();
      zones = AirZoneAttachment.getAirZones(data.getMap());
    }
    updating = true;
    try {
      rowZones.clear();
      if (nation == null) {
        model.setDataVector(new Object[0][0], new Object[0]);
        return;
      }
      try (GameData.Unlocker ignored = data.acquireReadLock()) {
        fillTable(nation, zones);
      }
    } finally {
      updating = false;
    }
  }

  private void fillTable(final GamePlayer nation, final List<Territory> zones) {
    final Set<UnitType> types = new LinkedHashSet<>();
    final List<Territory> used = new ArrayList<>();
    for (final Territory zone : zones) {
      final List<Unit> aircraft = aircraft(nation, zone);
      if (!aircraft.isEmpty()) {
        used.add(zone);
        aircraft.forEach(u -> types.add(u.getType()));
      }
    }
    final List<UnitType> columns = new ArrayList<>(types);
    final List<Object> header = new ArrayList<>();
    header.add("Zone");
    columns.forEach(t -> header.add(ResearchView.displayName(t.getName())));
    header.add("Total");
    final List<Object[]> rows = new ArrayList<>();
    rows.add(row("All zones", allAircraft(nation, used), columns));
    rowZones.add(null);
    for (final Territory zone : used) {
      rows.add(row(zone.getName().replaceFirst("^Air - ", ""), aircraft(nation, zone), columns));
      rowZones.add(zone);
    }
    model.setDataVector(rows.toArray(Object[][]::new), header.toArray());
    table.getColumnModel().getColumn(0).setPreferredWidth(170);
    for (int i = 1; i < header.size(); i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(70);
    }
    final int total = allAircraft(nation, used).size();
    summary.setText(
        total + " aircraft in " + used.size() + " zone" + (used.size() == 1 ? "" : "s"));
  }

  private List<Unit> allAircraft(final GamePlayer nation, final List<Territory> zones) {
    final List<Unit> all = new ArrayList<>();
    zones.forEach(z -> all.addAll(aircraft(nation, z)));
    return all;
  }

  private static List<Unit> aircraft(final GamePlayer nation, final Territory zone) {
    return zone.getUnitCollection()
        .getMatches(u -> u.isOwnedBy(nation) && Matches.unitIsAir().test(u));
  }

  /** One table row: count per type, "3 (1 hit)" when some are damaged, then the total. */
  private static Object[] row(
      final String name, final List<Unit> aircraft, final List<UnitType> columns) {
    final Object[] row = new Object[columns.size() + 2];
    row[0] = name;
    for (int i = 0; i < columns.size(); i++) {
      final UnitType type = columns.get(i);
      final long count = aircraft.stream().filter(u -> u.getType().equals(type)).count();
      final long hit =
          aircraft.stream().filter(u -> u.getType().equals(type) && u.getHits() > 0).count();
      row[i + 1] =
          count == 0 ? "" : hit == 0 ? String.valueOf(count) : count + " (" + hit + " hit)";
    }
    row[columns.size() + 1] = aircraft.size();
    return row;
  }

  private @Nullable GamePlayer chooseNation() {
    final List<GamePlayer> nations =
        data.getPlayerList().getPlayers().stream().filter(p -> !p.isNull()).toList();
    GamePlayer selected =
        nationBox.getSelectedItem() instanceof GamePlayer p
            ? data.getPlayerList().getPlayerId(p.getName())
            : null;
    if (followTurn || selected == null) {
      final GamePlayer current = data.getSequence().getStep().getPlayerId();
      selected = current != null ? current : selected;
    }
    if (selected == null && !nations.isEmpty()) {
      selected = nations.get(0);
    }
    updating = true;
    try {
      final List<GamePlayer> current = new ArrayList<>();
      for (int i = 0; i < nationBox.getItemCount(); i++) {
        current.add(nationBox.getItemAt(i));
      }
      if (!current.equals(nations)) {
        nationBox.removeAllItems();
        nations.forEach(nationBox::addItem);
      }
      nationBox.setSelectedItem(selected);
    } finally {
      updating = false;
    }
    return selected;
  }

  /** Follow the nation whose turn it is again (called when the step changes). */
  public void followTurn() {
    followTurn = true;
    queueRefresh();
  }
}
