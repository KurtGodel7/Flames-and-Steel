package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.events.GameDataChangeListener;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.ui.panels.map.MapPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Flames and Steel Air tab: where every nation's aircraft are. Rows are the air boxes, columns the
 * nations, cells "fighters/bombers"; a totals row on top. Clicking a box shows it on the map.
 */
public final class AirPanel extends JPanel {
  private static final long serialVersionUID = 1L;

  private final MapPanel mapPanel;
  private final Consumer<Territory> zoneSelected;
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
  private final AtomicBoolean refreshQueued = new AtomicBoolean();
  private final GameDataChangeListener changeListener = change -> queueRefresh();

  public AirPanel(
      final GameData data, final MapPanel mapPanel, final Consumer<Territory> zoneSelected) {
    super(new BorderLayout());
    this.data = data;
    this.mapPanel = mapPanel;
    this.zoneSelected = zoneSelected;
    final JLabel legend = new JLabel("Aircraft per box: fighters / bombers");
    legend.setFont(legend.getFont().deriveFont(Font.ITALIC));
    add(legend, BorderLayout.NORTH);
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
    queueRefresh();
  }

  /** Kept for the frame's step listener; the table shows every nation. */
  public void followTurn() {
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
    updating = true;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final List<GamePlayer> nations = new ArrayList<>();
      // fronts first, then carrier fronts, then bombing zones
      final List<Territory> zones = new ArrayList<>(AirZoneAttachment.getAirZones(data.getMap()));
      zones.sort(
          java.util.Comparator.comparingInt(
              z ->
                  switch (AirZoneAttachment.get(z).orElseThrow().getZoneType()) {
                    case OCEAN -> 1;
                    case BOMB -> 2;
                    default -> 0;
                  }));
      for (final GamePlayer p : data.getPlayerList().getPlayers()) {
        if (zones.stream()
            .anyMatch(z -> z.anyUnitsMatch(u -> u.isOwnedBy(p) && Matches.unitIsAir().test(u)))) {
          nations.add(p);
        }
      }
      final List<Object> header = new ArrayList<>();
      header.add("Box");
      nations.forEach(p -> header.add(AirZoneOverlay.shortName(p)));
      final List<Object[]> rows = new ArrayList<>();
      rowZones.clear();
      final int[][] totals = new int[nations.size()][2];
      final List<Object[]> zoneRows = new ArrayList<>();
      for (final Territory zone : zones) {
        final Object[] row = new Object[nations.size() + 1];
        final ZoneType type = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
        row[0] =
            AirAllocation.zoneName(zone)
                + (type == ZoneType.OCEAN
                    ? " (carriers)"
                    : type == ZoneType.BOMB ? " (bombing)" : "");

        for (int i = 0; i < nations.size(); i++) {
          final GamePlayer p = nations.get(i);
          int fighters = 0;
          int bombers = 0;
          for (final Unit u :
              zone.getUnitCollection()
                  .getMatches(u -> u.isOwnedBy(p) && Matches.unitIsAir().test(u))) {
            if (AirUnitAttachment.get(u.getType())
                .map(AirUnitAttachment::isFighter)
                .orElse(false)) {
              fighters++;
            } else {
              bombers++;
            }
          }
          totals[i][0] += fighters;
          totals[i][1] += bombers;

          row[i + 1] = fighters + bombers == 0 ? "" : fighters + " / " + bombers;
        }
        zoneRows.add(row);
        rowZones.add(zone);
      }
      final Object[] total = new Object[nations.size() + 1];
      total[0] = "All boxes";
      for (int i = 0; i < nations.size(); i++) {
        total[i + 1] = totals[i][0] + " / " + totals[i][1];
      }
      rows.add(total);
      rows.addAll(zoneRows);
      rowZones.add(0, null);
      model.setDataVector(rows.toArray(Object[][]::new), header.toArray());
      table.getColumnModel().getColumn(0).setPreferredWidth(170);
      for (int i = 1; i < header.size(); i++) {
        table.getColumnModel().getColumn(i).setPreferredWidth(46);
      }
    } finally {
      updating = false;
    }
  }
}
