package games.strategy.triplea.ui.air;

import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.formatter.PlayerNames;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * Flames and Steel (request 38 section 5): aircraft that lost their base (carrier sunk, land
 * captured) move one space. One line per aircraft: where it lands, from the spots in reach, nearest
 * first; one with none is lost.
 */
public final class FreeLandingDialog {
  private FreeLandingDialog() {}

  /** Asks where each aircraft lands; an aircraft left out lands at its first spot. */
  public static Map<Unit, Territory> show(
      final Component parent, final Map<Unit, List<Territory>> options, final String why) {
    final JPanel panel = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridy = 0;
    c.gridx = 0;
    c.gridwidth = 2;
    panel.add(
        new JLabel(
            "<html><b>"
                + why
                + "</b><br>These aircraft have no base: each may move one space to your land or"
                + " one of your carriers with room. Any with nowhere to land are lost.</html>"),
        c);
    c.gridwidth = 1;
    final Map<Unit, JComboBox<Territory>> boxes = new LinkedHashMap<>();
    for (final Map.Entry<Unit, List<Territory>> e : options.entrySet()) {
      c.gridy++;
      c.gridx = 0;
      final Unit u = e.getKey();
      panel.add(
          new JLabel(
              PlayerNames.display(u.getOwner()) + " " + AirCombat.unitName(u.getType())),
          c);
      c.gridx = 1;
      if (e.getValue().isEmpty()) {
        panel.add(new JLabel("nowhere to land: lost"), c);
        continue;
      }
      final JComboBox<Territory> box = new JComboBox<>(e.getValue().toArray(new Territory[0]));
      boxes.put(u, box);
      panel.add(box, c);
    }
    JOptionPane.showMessageDialog(parent, panel, "Aircraft without a base", JOptionPane.PLAIN_MESSAGE);
    final Map<Unit, Territory> result = new LinkedHashMap<>();
    boxes.forEach(
        (u, box) -> {
          if (box.getSelectedItem() instanceof Territory t) {
            result.put(u, t);
          }
        });
    return result;
  }

  /** For tests: the first spot of each. */
  static Map<Unit, Territory> defaults(final Map<Unit, List<Territory>> options) {
    final Map<Unit, Territory> result = new LinkedHashMap<>();
    options.forEach(
        (u, spots) -> {
          if (!spots.isEmpty()) {
            result.put(u, new ArrayList<>(spots).get(0));
          }
        });
    return result;
  }
}
