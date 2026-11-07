package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.apc.Apcs;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * Flames and Steel: choose what each of the nation's SPCs (unit type "apc") builds. Runs on the
 * EDT.
 */
public final class ApcProductionDialog {
  private static final String NOTHING = "(nothing)";

  private ApcProductionDialog() {}

  /**
   * What the player chose: each SPC's unit type (null: nothing) and, request 62, the sea zone the
   * ships of each SPC building ships go to.
   */
  public record Choices(
      Map<Unit, UnitType> types, Map<Unit, games.strategy.engine.data.Territory> seaZones) {}

  private static String territoryName(final Unit apc, final GameData data) {
    final java.util.Optional<games.strategy.engine.data.Territory> t =
        Apcs.territoryOf(apc, data.getMap());
    return t.isPresent() ? t.get().getName() : "?";
  }

  /** Shows the window; returns each APC's choice (null: builds nothing). */
  static Choices show(final Component parent, final GamePlayer player, final GameData data) {
    final JPanel panel = new JPanel(new GridBagLayout());
    final Map<Unit, JComboBox<Object>> boxes = new LinkedHashMap<>();
    final Map<Unit, JComboBox<games.strategy.engine.data.Territory>> zoneBoxes =
        new LinkedHashMap<>();
    build(panel, player, data, boxes, zoneBoxes);
    JOptionPane.showOptionDialog(
        parent,
        panel,
        games.strategy.triplea.formatter.PlayerNames.display(player) + " SPC production",
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.PLAIN_MESSAGE,
        null,
        new String[] {"Done"},
        "Done");
    return choices(boxes, zoneBoxes);
  }

  /** The choices the boxes show now. */
  static Choices choices(
      final Map<Unit, JComboBox<Object>> boxes,
      final Map<Unit, JComboBox<games.strategy.engine.data.Territory>> zoneBoxes) {
    final Map<Unit, UnitType> result = new HashMap<>();
    final Map<Unit, games.strategy.engine.data.Territory> zones = new HashMap<>();
    for (final Map.Entry<Unit, JComboBox<Object>> entry : boxes.entrySet()) {
      final Object selected = entry.getValue().getSelectedItem();
      final UnitType type = selected instanceof UnitType ? (UnitType) selected : null;
      result.put(entry.getKey(), type);
      final JComboBox<games.strategy.engine.data.Territory> zoneBox = zoneBoxes.get(entry.getKey());
      if (type != null
          && type.getUnitAttachment().isSea()
          && zoneBox != null
          && zoneBox.getItemCount() > 0) {
        zones.put(entry.getKey(), (games.strategy.engine.data.Territory) zoneBox.getSelectedItem());
      }
    }
    return new Choices(result, zones);
  }

  /** Fills the panel: one row per SPC with its unit type box and, for ships, its sea zone. */
  static void build(
      final JPanel panel,
      final GamePlayer player,
      final GameData data,
      final Map<Unit, JComboBox<Object>> boxes,
      final Map<Unit, JComboBox<games.strategy.engine.data.Territory>> zoneBoxes) {
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(3, 4, 3, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 3;
    panel.add(
        new JLabel(
            "<html><b>"
                + player.getName()
                + ": SPC production</b><br>Each Special Production Center (SPC) builds one advanced unit type at no PU cost."
                + "<br>The units appear in the SPC's territory near the end of the turn.</html>"),
        c);
    c.gridwidth = 1;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      // request 11 item 3: say which SPCs sit idle and why
      final List<String> idle = Apcs.idleLines(player, data.getMap());
      final java.util.Optional<String> nothing = Apcs.buildsNothingWarning(player);
      if (!idle.isEmpty() || nothing.isPresent()) {
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        final JLabel warning =
            new JLabel(
                "<html><font color='#E07000'>"
                    + String.join("<br>", idle)
                    + nothing.map(n -> (idle.isEmpty() ? "" : "<br>") + n + ".").orElse("")
                    + "</font></html>");
        panel.add(warning, c);
        c.gridwidth = 1;
      }
      final List<Object> options = new ArrayList<>();
      options.add(NOTHING);
      options.addAll(Apcs.getBuildableTypes(player));
      for (final Unit apc : Apcs.getOwnApcs(player, data.getMap())) {
        c.gridy++;
        c.gridx = 0;
        // request 50: when this SPC next gets its free units, e.g. "free Panther in 1 turn"
        final List<String> free = Apcs.freeUnitsStatus(apc, player);
        panel.add(
            new JLabel(
                "SPC in "
                    + territoryName(apc, data)
                    + (free.isEmpty() ? "" : " (" + String.join(", ", free) + ")")),
            c);
        final JComboBox<Object> box = new JComboBox<>(options.toArray());
        box.setRenderer(
            new DefaultListCellRenderer() {
              @Override
              public Component getListCellRendererComponent(
                  final JList<?> list,
                  final Object value,
                  final int index,
                  final boolean isSelected,
                  final boolean cellHasFocus) {
                Object shown = value;
                if (value instanceof UnitType) {
                  final UnitType type = (UnitType) value;
                  // request 25: names in words, e.g. "Type XXI (1 per turn)"
                  shown =
                      games.strategy.triplea.ui.research.ResearchView.displayName(type.getName())
                          + " ("
                          + Apcs.describeRate(type)
                          + ")";
                }
                return super.getListCellRendererComponent(
                    list, shown, index, isSelected, cellHasFocus);
              }
            });
        final java.util.Optional<UnitType> current = Apcs.getProduction(apc, player);
        if (current.isPresent()) {
          box.setSelectedItem(current.get());
        } else {
          box.setSelectedItem(NOTHING);
        }
        boxes.put(apc, box);
        c.gridx = 1;
        panel.add(box, c);
        // request 62: where this SPC's ships go: a choice with two or more sea zones next to it
        final java.util.Optional<games.strategy.engine.data.Territory> at =
            Apcs.territoryOf(apc, data.getMap());
        final List<games.strategy.engine.data.Territory> zones =
            at.map(t -> Apcs.seaZonesNextTo(t, data.getMap())).orElse(List.of());
        final JComboBox<games.strategy.engine.data.Territory> zoneBox =
            new JComboBox<>(zones.toArray(games.strategy.engine.data.Territory[]::new));
        at.flatMap(t -> Apcs.shipsGoTo(apc, t, player, data.getMap()))
            .ifPresent(zoneBox::setSelectedItem);
        zoneBox.setRenderer(
            new DefaultListCellRenderer() {
              @Override
              public Component getListCellRendererComponent(
                  final JList<?> list,
                  final Object value,
                  final int index,
                  final boolean isSelected,
                  final boolean cellHasFocus) {
                return super.getListCellRendererComponent(
                    list,
                    value instanceof games.strategy.engine.data.Territory t ? t.getName() : value,
                    index,
                    isSelected,
                    cellHasFocus);
              }
            });
        zoneBoxes.put(apc, zoneBox);
        final JPanel ships = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        final Runnable refreshShips =
            () -> {
              ships.removeAll();
              final Object type = box.getSelectedItem();
              if (type instanceof UnitType t && t.getUnitAttachment().isSea()) {
                if (zones.isEmpty()) {
                  ships.add(
                      new JLabel(
                          "<html><font color='#E07000'>no sea zone next to it</font></html>"));
                } else if (zones.size() == 1) {
                  ships.add(new JLabel("Ships go to: " + zones.get(0).getName()));
                } else {
                  ships.add(new JLabel("Ships go to:"));
                  ships.add(zoneBox);
                }
              }
              ships.revalidate();
              ships.repaint();
              final java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(ships);
              if (w != null) {
                w.pack();
              }
            };
        refreshShips.run();
        box.addActionListener(e -> refreshShips.run());
        c.gridx = 2;
        panel.add(ships, c);
      }
    }
  }
}
