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
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * Flames and Steel (request 38 section 5): aircraft that lost their base (carrier sunk, land
 * captured) move one space. One line per aircraft: where it lands, from the spots in reach, nearest
 * first; one with none is lost. Request 47: a short heading with the count ("2 aircraft must
 * land"), the how-to in its tooltip (and, at first, in grey under it), and "Next" to go through the
 * aircraft one by one, showing each on the map.
 */
public final class FreeLandingDialog {
  private static final String NEAREST = "(nearest spot)";
  static final String HELP =
      "These aircraft have no base: each may move one space to your land or one of your carriers"
          + " with room. Any with nowhere to land are lost. One left as it is lands at the nearest"
          + " spot.";

  private FreeLandingDialog() {}

  /** Asks where each aircraft lands; an aircraft left out lands at its first spot. */
  public static Map<Unit, Territory> show(
      final Component parent, final Map<Unit, List<Territory>> options, final String why) {
    return show(parent, options, why, null, true);
  }

  /**
   * As {@link #show(Component, Map, String)}; {@code showOnMap} brings an aircraft's place into
   * view on the map (null: no Next button), {@code showHelp} adds the how-to in grey.
   */
  public static Map<Unit, Territory> show(
      final Component parent,
      final Map<Unit, List<Territory>> options,
      final String why,
      final @Nullable Consumer<Unit> showOnMap,
      final boolean showHelp) {
    final JPanel panel = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridy = 0;
    c.gridx = 0;
    c.gridwidth = 2;
    final long landing = options.values().stream().filter(spots -> !spots.isEmpty()).count();
    final JLabel heading = new JLabel(heading(options.size(), landing));
    heading.setToolTipText(AirSelection.wrap(why + ". " + HELP));
    panel.add(heading, c);
    if (showHelp) {
      c.gridy++;
      panel.add(AirHelp.text(why + ". " + HELP), c);
    }
    c.gridwidth = 1;
    final Map<Unit, JComboBox<Object>> boxes = new LinkedHashMap<>();
    final List<Unit> units = new ArrayList<>(options.keySet());
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
      // request 40: nothing is chosen for the player; left as it is, the nearest spot is taken
      final List<Object> items = new ArrayList<>();
      items.add(NEAREST);
      items.addAll(e.getValue());
      final JComboBox<Object> box = new JComboBox<>(items.toArray());
      boxes.put(u, box);
      panel.add(box, c);
    }
    if (showOnMap != null && !units.isEmpty()) {
      // request 47: as '.' in the air stages: the next aircraft, shown on the map, its line chosen
      final int[] at = {-1};
      final JButton next = new JButton("Next aircraft ›");
      next.setToolTipText("Shows the next of these aircraft on the map and picks its line.");
      next.addActionListener(
          e -> {
            at[0] = (at[0] + 1) % units.size();
            final Unit u = units.get(at[0]);
            showOnMap.accept(u);
            final JComboBox<Object> box = boxes.get(u);
            if (box != null) {
              box.requestFocusInWindow();
            }
          });
      c.gridy++;
      c.gridx = 0;
      c.gridwidth = 2;
      panel.add(next, c);
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

  /** Request 47: "<b>2</b> aircraft must land" (and how many are lost). */
  static String heading(final int aircraft, final long canLand) {
    final long lost = aircraft - canLand;
    return "<html><b>"
        + canLand
        + "</b> aircraft must land"
        + (lost > 0 ? "; <b>" + lost + "</b> with nowhere to land are lost" : "")
        + "</html>";
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
