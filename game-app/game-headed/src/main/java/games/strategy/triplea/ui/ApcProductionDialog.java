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

  private static String territoryName(final Unit apc, final GameData data) {
    final java.util.Optional<games.strategy.engine.data.Territory> t =
        Apcs.territoryOf(apc, data.getMap());
    return t.isPresent() ? t.get().getName() : "?";
  }

  /** Shows the window; returns each APC's choice (null: builds nothing). */
  static Map<Unit, UnitType> show(
      final Component parent, final GamePlayer player, final GameData data) {
    final Map<Unit, JComboBox<Object>> boxes = new LinkedHashMap<>();
    final JPanel panel = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(3, 4, 3, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 2;
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
        c.gridwidth = 2;
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
      }
    }
    JOptionPane.showOptionDialog(
        parent,
        panel,
        games.strategy.triplea.formatter.PlayerNames.display(player) + " SPC production",
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.PLAIN_MESSAGE,
        null,
        new String[] {"Done"},
        "Done");
    final Map<Unit, UnitType> result = new HashMap<>();
    for (final Map.Entry<Unit, JComboBox<Object>> entry : boxes.entrySet()) {
      final Object selected = entry.getValue().getSelectedItem();
      result.put(entry.getKey(), selected instanceof UnitType ? (UnitType) selected : null);
    }
    return result;
  }
}
