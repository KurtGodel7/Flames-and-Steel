package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
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
 * Flames and Steel: one window per nation and stage to move aircraft between air zones. The player
 * picks a zone, a type, a count and a destination, and adds the move; the list of moves is sent
 * when the window closes. Runs on the EDT.
 */
public final class AirAllocationDialog {
  private final GamePlayer player;
  private final Stage stage;
  private final GameData data;
  // aircraft of this stage by zone and type, before any move
  private final Map<Territory, IntegerMap<UnitType>> available = new LinkedHashMap<>();
  private final DefaultListModel<AirMove> moves = new DefaultListModel<>();
  private final JComboBox<Territory> fromBox = new JComboBox<>();
  private final JComboBox<UnitType> typeBox = new JComboBox<>();
  private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
  private final JComboBox<Territory> toBox = new JComboBox<>();
  private final JLabel status = new JLabel(" ");

  private AirAllocationDialog(
      final GamePlayer player, final Stage stage, final GameData data, final List<AirMove> start) {
    this.player = player;
    this.stage = stage;
    this.data = data;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
        final IntegerMap<UnitType> types = new IntegerMap<>();
        for (final Unit unit : AirAllocation.getAircraft(player, zone, stage)) {
          types.add(unit.getType(), 1);
        }
        if (!types.isEmpty()) {
          available.put(zone, types);
        }
      }
    }
    start.forEach(moves::addElement);
  }

  /** Shows the window; returns the moves (an empty list means no aircraft move). */
  static List<AirMove> show(
      final Component parent,
      final GamePlayer player,
      final Stage stage,
      final GameData data,
      final List<AirMove> start) {
    return new AirAllocationDialog(player, stage, data, start).show(parent);
  }

  private List<AirMove> show(final Component parent) {
    final JPanel panel = new JPanel(new BorderLayout(6, 6));
    panel.add(
        new JLabel(
            "<html><b>"
                + player.getName()
                + (stage == Stage.BOMBERS ? ": bomber" : ": fighter")
                + " allocation</b><br>Your choices stay hidden until every nation has chosen."
                + "<br>Aircraft may move to an adjacent zone, into the reserve, or from the"
                + " reserve to any zone.</html>"),
        BorderLayout.NORTH);
    panel.add(buildMovePanel(), BorderLayout.CENTER);
    panel.add(buildMoveList(), BorderLayout.SOUTH);
    typeBox.setRenderer(
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
                value instanceof UnitType unitType ? unitType.getName() : value,
                index,
                isSelected,
                cellHasFocus);
          }
        });
    fromBox.addActionListener(e -> updateTypes());
    typeBox.addActionListener(e -> updateCount());
    available.keySet().forEach(fromBox::addItem);
    updateTypes();
    JOptionPane.showOptionDialog(
        parent,
        panel,
        player.getName() + " air allocation",
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.PLAIN_MESSAGE,
        null,
        new String[] {"Done"},
        "Done");
    final List<AirMove> result = new ArrayList<>();
    for (int i = 0; i < moves.size(); i++) {
      result.add(moves.get(i));
    }
    return result;
  }

  private JPanel buildMovePanel() {
    final JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Move aircraft"));
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridy = 0;
    addRow(panel, c, "From", fromBox);
    addRow(panel, c, "Aircraft", typeBox);
    addRow(panel, c, "How many", countSpinner);
    addRow(panel, c, "To", toBox);
    final JButton add = new JButton("Add move");
    add.addActionListener(e -> addMove());
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

  private JPanel buildMoveList() {
    final JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Moves (everything else stays where it is)"));
    final JList<AirMove> list = new JList<>(moves);
    list.setCellRenderer(
        (jlist, move, index, selected, focus) -> {
          final JLabel label = new JLabel(AirAllocation.describe(List.of(move)).get(0));
          if (selected) {
            label.setOpaque(true);
            label.setBackground(jlist.getSelectionBackground());
            label.setForeground(jlist.getSelectionForeground());
          }
          return label;
        });
    final JScrollPane scroll = new JScrollPane(list);
    scroll.setPreferredSize(new Dimension(420, 120));
    panel.add(scroll, BorderLayout.CENTER);
    final JButton remove = new JButton("Remove selected move");
    remove.addActionListener(
        e -> {
          if (list.getSelectedIndex() >= 0) {
            moves.remove(list.getSelectedIndex());
            updateCount();
          }
        });
    panel.add(remove, BorderLayout.SOUTH);
    return panel;
  }

  private void updateTypes() {
    typeBox.removeAllItems();
    toBox.removeAllItems();
    final Territory from = (Territory) fromBox.getSelectedItem();
    if (from == null) {
      status.setText("No aircraft to move in this stage.");
      return;
    }
    available.get(from).keySet().forEach(typeBox::addItem);
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      AirAllocation.getDestinations(player, from, data.getMap()).forEach(toBox::addItem);
    }
    updateCount();
  }

  private void updateCount() {
    final int left = left();
    countSpinner.setModel(new SpinnerNumberModel(Math.max(left, 0) > 0 ? 1 : 0, 0, left, 1));
    countSpinner.setValue(left);
  }

  /** Aircraft of the selected zone and type not yet in a move. */
  private int left() {
    final Territory from = (Territory) fromBox.getSelectedItem();
    final UnitType type = (UnitType) typeBox.getSelectedItem();
    if (from == null || type == null) {
      return 0;
    }
    int used = 0;
    for (int i = 0; i < moves.size(); i++) {
      final AirMove move = moves.get(i);
      if (move.from().equals(from) && move.unitType().equals(type)) {
        used += move.count();
      }
    }
    return Math.max(0, available.get(from).getInt(type) - used);
  }

  private void addMove() {
    final Territory from = (Territory) fromBox.getSelectedItem();
    final UnitType type = (UnitType) typeBox.getSelectedItem();
    final Territory to = (Territory) toBox.getSelectedItem();
    final int count = (Integer) countSpinner.getValue();
    if (from == null || type == null || to == null || count <= 0) {
      status.setText("Choose a zone, an aircraft type, a number and a destination.");
      return;
    }
    final List<AirMove> trial = new ArrayList<>();
    for (int i = 0; i < moves.size(); i++) {
      trial.add(moves.get(i));
    }
    trial.add(new AirMove(from, to, type, count));
    final String error;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      error = AirAllocation.validate(player, stage, trial, data.getMap()).orElse(null);
    }
    if (error != null) {
      status.setText("<html>" + error + "</html>");
      return;
    }
    moves.addElement(new AirMove(from, to, type, count));
    status.setText(" ");
    updateCount();
  }
}
