package games.strategy.triplea.ui.air;

import games.strategy.engine.data.UnitType;
import games.strategy.triplea.formatter.UnitNames;
import games.strategy.triplea.ui.research.ResearchView;
import games.strategy.ui.ScrollableTextField;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (request 9 item 3): "which aircraft go with the fleet" when carriers change air
 * front. One row per aircraft type with the stock up/down number field, set to as many as fit. Runs
 * on the EDT.
 */
public final class FleetAircraftDialog {
  private FleetAircraftDialog() {}

  /** The chosen counts, or null if the player cancelled the move. */
  public static @Nullable IntegerMap<UnitType> show(
      final Component parent,
      final IntegerMap<UnitType> available,
      final IntegerMap<UnitType> suggested,
      final String description) {
    final JPanel panel = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(3, 4, 3, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 2;
    panel.add(
        new JLabel(
            "<html><b>Which aircraft go with the fleet?</b><br>"
                + description
                + "<br>Aircraft that stay remain in their front.</html>"),
        c);
    c.gridwidth = 1;
    final Map<UnitType, ScrollableTextField> fields = new LinkedHashMap<>();
    for (final UnitType type :
        available.keySet().stream().sorted(Comparator.comparing(UnitType::getName)).toList()) {
      c.gridy++;
      c.gridx = 0;
      panel.add(
          new JLabel(
              ResearchView.displayName(UnitNames.of(type))
                  + " ("
                  + available.getInt(type)
                  + " in the front)"),
          c);
      final ScrollableTextField field = new ScrollableTextField(0, available.getInt(type));
      field.setValue(suggested.getInt(type));
      fields.put(type, field);
      c.gridx = 1;
      panel.add(field, c);
    }
    final int answer =
        JOptionPane.showOptionDialog(
            parent,
            panel,
            "Aircraft sail with the fleet",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            new String[] {"Move", "Cancel move"},
            "Move");
    if (answer != 0) {
      return null;
    }
    final IntegerMap<UnitType> chosen = new IntegerMap<>();
    fields.forEach(
        (type, field) -> {
          if (field.getValue() > 0) {
            chosen.put(type, field.getValue());
          }
        });
    return chosen;
  }
}
