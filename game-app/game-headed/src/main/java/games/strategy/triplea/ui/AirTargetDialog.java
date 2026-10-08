package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel: one window per nation to send its surviving bombers against targets in their
 * air zones. Bombers left without a target do not attack. Runs on the EDT.
 */
public final class AirTargetDialog {
  private final GamePlayer player;
  private final GameData data;
  private final Map<Territory, IntegerMap<UnitType>> available = new LinkedHashMap<>();
  private final Map<Territory, List<Target>> targets = new LinkedHashMap<>();
  private final DefaultListModel<TargetAssignment> assignments = new DefaultListModel<>();
  private final JComboBox<Territory> zoneBox = new JComboBox<>();
  private final JComboBox<UnitType> typeBox = new JComboBox<>();
  private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 0, 1));
  private final JComboBox<Target> targetBox = new JComboBox<>();
  private final JLabel status = new JLabel(" ");

  private AirTargetDialog(
      final GamePlayer player, final GameData data, final List<TargetAssignment> start) {
    this.player = player;
    this.data = data;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
        final List<Target> zoneTargets = AirCombat.getTargets(player, zone);
        final IntegerMap<UnitType> types = new IntegerMap<>();
        for (final Unit unit : AirCombat.getBombers(player, zone)) {
          types.add(unit.getType(), 1);
        }
        if (!types.isEmpty() && !zoneTargets.isEmpty()) {
          available.put(zone, types);
          targets.put(zone, zoneTargets);
        }
      }
    }
    start.forEach(assignments::addElement);
  }

  /** Shows the window; returns the assignments (empty: no bomber attacks). */
  static List<TargetAssignment> show(
      final Component parent,
      final GamePlayer player,
      final GameData data,
      final List<TargetAssignment> start) {
    return new AirTargetDialog(player, data, start).show(parent);
  }

  private List<TargetAssignment> show(final Component parent) {
    final JPanel panel = new JPanel(new BorderLayout(6, 6));
    panel.add(
        new JLabel(
            "<html><b>"
                + player.getName()
                + ": bomber targets</b><br>Send surviving bombers against targets in their zone."
                + "<br>Bombers without a target do not attack.</html>"),
        BorderLayout.NORTH);
    panel.add(buildChoicePanel(), BorderLayout.CENTER);
    panel.add(buildList(), BorderLayout.SOUTH);
    typeBox.setRenderer(nameRenderer());
    targetBox.setRenderer(nameRenderer());
    zoneBox.addActionListener(e -> updateZone());
    typeBox.addActionListener(e -> updateType());
    available.keySet().forEach(zoneBox::addItem);
    updateZone();
    JOptionPane.showOptionDialog(
        parent,
        panel,
        player.getName() + " bomber targets",
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.PLAIN_MESSAGE,
        null,
        new String[] {"Done"},
        "Done");
    final List<TargetAssignment> result = new ArrayList<>();
    for (int i = 0; i < assignments.size(); i++) {
      result.add(assignments.get(i));
    }
    return result;
  }

  private static DefaultListCellRenderer nameRenderer() {
    return new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(
          final JList<?> list,
          final Object value,
          final int index,
          final boolean isSelected,
          final boolean cellHasFocus) {
        Object shown = value;
        if (value instanceof UnitType) {
          shown = ((UnitType) value).getName();
        } else if (value instanceof Target) {
          shown = ((Target) value).describe();
        }
        return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
      }
    };
  }

  private JPanel buildChoicePanel() {
    final JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Send bombers"));
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridy = 0;
    addRow(panel, c, "Air zone", zoneBox);
    addRow(panel, c, "Aircraft", typeBox);
    addRow(panel, c, "How many", countSpinner);
    addRow(panel, c, "Target", targetBox);
    final JButton add = new JButton("Add");
    add.addActionListener(e -> addAssignment());
    c.gridx = 1;
    panel.add(add, c);
    c.gridy++;
    c.gridx = 0;
    c.gridwidth = 2;
    panel.add(status, c);
    return panel;
  }

  private static void addRow(
      final JPanel panel, final GridBagConstraints c, final String label, final Component field) {
    c.gridx = 0;
    panel.add(new JLabel(label), c);
    c.gridx = 1;
    panel.add(field, c);
    c.gridy++;
  }

  private JPanel buildList() {
    final JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Attacks"));
    final JList<TargetAssignment> list = new JList<>(assignments);
    list.setCellRenderer(
        (jlist, a, index, selected, focus) -> {
          final JLabel label =
              new JLabel(
                  a.count()
                      + " "
                      + a.unitType().getName()
                      + " from "
                      + a.zone().getName()
                      + " attack "
                      + a.target().describe());
          if (selected) {
            label.setOpaque(true);
            label.setBackground(jlist.getSelectionBackground());
            label.setForeground(jlist.getSelectionForeground());
          }
          return label;
        });
    final JScrollPane scroll = new JScrollPane(list);
    scroll.setPreferredSize(new Dimension(460, 120));
    panel.add(scroll, BorderLayout.CENTER);
    final JButton remove = new JButton("Remove selected");
    remove.addActionListener(
        e -> {
          if (list.getSelectedIndex() >= 0) {
            assignments.remove(list.getSelectedIndex());
            updateCount();
          }
        });
    panel.add(remove, BorderLayout.SOUTH);
    return panel;
  }

  private void updateZone() {
    typeBox.removeAllItems();
    final Territory zone = (Territory) zoneBox.getSelectedItem();
    if (zone == null) {
      status.setText("No bombers with targets.");
      return;
    }
    available.get(zone).keySet().forEach(typeBox::addItem);
    updateType();
  }

  private void updateType() {
    targetBox.removeAllItems();
    final Territory zone = (Territory) zoneBox.getSelectedItem();
    final UnitType type = (UnitType) typeBox.getSelectedItem();
    if (zone == null || type == null) {
      return;
    }
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      targets.get(zone).stream()
          .filter(t -> AirCombat.mayAttack(player, zone, type, t))
          .forEach(targetBox::addItem);
    }
    if (targetBox.getItemCount() == 0) {
      status.setText("No target in range for these aircraft.");
    }
    updateCount();
  }

  private void updateCount() {
    final int left = left();
    countSpinner.setModel(new SpinnerNumberModel(left, 0, left, 1));
  }

  private int left() {
    final Territory zone = (Territory) zoneBox.getSelectedItem();
    final UnitType type = (UnitType) typeBox.getSelectedItem();
    if (zone == null || type == null) {
      return 0;
    }
    int used = 0;
    for (int i = 0; i < assignments.size(); i++) {
      final TargetAssignment a = assignments.get(i);
      if (a.zone().equals(zone) && a.unitType().equals(type)) {
        used += a.count();
      }
    }
    return Math.max(0, available.get(zone).getInt(type) - used);
  }

  private void addAssignment() {
    final Territory zone = (Territory) zoneBox.getSelectedItem();
    final UnitType type = (UnitType) typeBox.getSelectedItem();
    final Target target = (Target) targetBox.getSelectedItem();
    final int count = (Integer) countSpinner.getValue();
    if (zone == null || type == null || target == null || count <= 0) {
      status.setText("Choose a zone, an aircraft type, a number and a target.");
      return;
    }
    assignments.addElement(new TargetAssignment(zone, type, count, target));
    status.setText(" ");
    updateCount();
  }
}
