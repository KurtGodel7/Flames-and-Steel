package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.research.ResearchCenters;
import games.strategy.triplea.delegate.research.ResearchCenters.Mode;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/** Flames and Steel: asks the player for each research centre's focus. Runs on the EDT. */
public final class ResearchFocusDialog {
  /** The player's choices: one "primary|secondary" entry per centre, and the idle centre. */
  public record Choice(List<String> focus, int idleCenter) {}

  private final GamePlayer player;
  private final int centers;
  private final Mode mode;
  private final boolean capitalLost;
  private final List<String> categories = new ArrayList<>();
  private final List<String> categoryLabels = new ArrayList<>();
  private final List<JComboBox<String>> primaryBoxes = new ArrayList<>();
  private final List<JComboBox<String>> secondaryBoxes = new ArrayList<>();
  private final List<JLabel> centerLabels = new ArrayList<>();
  private JComboBox<String> idleBox;
  private final List<String> defaultFocus;
  private final int defaultIdle;

  private ResearchFocusDialog(final GamePlayer player, final GameData data) {
    this.player = player;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      centers = ResearchCenters.getResearchCenters(player);
      capitalLost = ResearchCenters.hasLostCapital(player, data.getMap());
      defaultIdle = ResearchCenters.getDefaultIdleCenter(player, data.getMap());
      defaultFocus = ResearchCenters.getDefaultFocus(player, defaultIdle, data.getMap());
      final List<TechnologyFrontier> unfinished = ResearchCenters.getUnfinishedCategories(player);
      mode =
          ResearchCenters.getMode(
              unfinished.size(), ResearchCenters.getWorkingResearchCenters(player, data.getMap()));
      for (final TechnologyFrontier category : unfinished) {
        categories.add(category.getName());
        categoryLabels.add(describe(category));
      }
    }
  }

  static Optional<Choice> show(
      final Component parent, final GamePlayer player, final GameData data) {
    return new ResearchFocusDialog(player, data).show(parent);
  }

  private String describe(final TechnologyFrontier category) {
    final Optional<TechAdvance> next = ResearchCenters.getNextTech(player, category);
    return next.map(
            tech ->
                category.getName()
                    + "  (next: "
                    + tech.getName()
                    + " "
                    + ResearchCenters.getStars(player, tech)
                    + "/"
                    + ResearchCenters.getStarsRequired(player, tech)
                    + ")")
        .orElse(category.getName());
  }

  private Optional<Choice> show(final Component parent) {
    final JPanel panel = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(3, 3, 3, 3);
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 3;
    panel.add(new JLabel(header()), c);
    c.gridwidth = 1;

    if (capitalLost && centers > 0) {
      c.gridy++;
      c.gridx = 0;
      panel.add(new JLabel("Idle centre (capital occupied):"), c);
      final String[] centerNames = new String[centers];
      for (int i = 0; i < centers; i++) {
        centerNames[i] = "Research centre " + (i + 1);
      }
      idleBox = new JComboBox<>(centerNames);
      idleBox.setSelectedIndex(Math.max(0, defaultIdle));
      idleBox.addActionListener(e -> updateIdle());
      c.gridx = 1;
      panel.add(idleBox, c);
    }

    if (mode != Mode.AUTOMATIC) {
      c.gridy++;
      c.gridx = 1;
      panel.add(new JLabel("Primary focus"), c);
      c.gridx = 2;
      if (mode == Mode.PRIMARY_AND_SECONDARY) {
        panel.add(new JLabel("Secondary focus"), c);
      }
      for (int i = 0; i < centers; i++) {
        c.gridy++;
        c.gridx = 0;
        final JLabel label = new JLabel("Research centre " + (i + 1));
        centerLabels.add(label);
        panel.add(label, c);
        final JComboBox<String> primary = newCategoryBox();
        primary.setSelectedItem(ResearchCenters.getPrimary(defaultFocus.get(i)));
        primaryBoxes.add(primary);
        c.gridx = 1;
        panel.add(primary, c);
        final JComboBox<String> secondary = newCategoryBox();
        secondary.setSelectedItem(ResearchCenters.getSecondary(defaultFocus.get(i)));
        secondaryBoxes.add(secondary);
        if (mode == Mode.PRIMARY_AND_SECONDARY) {
          c.gridx = 2;
          panel.add(secondary, c);
        }
      }
      updateIdle();
    }

    JOptionPane.showOptionDialog(
        parent,
        panel,
        player.getName() + " research",
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.PLAIN_MESSAGE,
        null,
        new String[] {"Research"},
        "Research");
    return Optional.of(new Choice(selectedFocus(), selectedIdle()));
  }

  private String header() {
    final String rule =
        switch (mode) {
          case PRIMARY_AND_SECONDARY ->
              "Each centre: 40% primary focus, 20% secondary focus, 40% random category.";
          case PRIMARY_ONLY ->
              "Too few categories left for secondary focus: 50% primary focus, 50% random.";
          case AUTOMATIC -> "Every unfinished category gains one star this turn.";
        };
    return "<html>"
        + player.getName()
        + " has "
        + centers
        + " research centre"
        + (centers == 1 ? "" : "s")
        + ".<br>"
        + rule
        + "<br>New techs take effect at the end of this turn.</html>";
  }

  private JComboBox<String> newCategoryBox() {
    final JComboBox<String> box = new JComboBox<>(categories.toArray(String[]::new));
    box.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              final JList<?> list,
              final Object value,
              final int index,
              final boolean isSelected,
              final boolean cellHasFocus) {
            final int i = categories.indexOf(value);
            return super.getListCellRendererComponent(
                list, i < 0 ? value : categoryLabels.get(i), index, isSelected, cellHasFocus);
          }
        });
    return box;
  }

  private void updateIdle() {
    final int idle = selectedIdle();
    for (int i = 0; i < primaryBoxes.size(); i++) {
      final boolean working = i != idle;
      primaryBoxes.get(i).setEnabled(working);
      secondaryBoxes.get(i).setEnabled(working);
      centerLabels.get(i).setText("Research centre " + (i + 1) + (working ? "" : " (idle)"));
    }
  }

  private int selectedIdle() {
    return idleBox == null ? -1 : idleBox.getSelectedIndex();
  }

  private List<String> selectedFocus() {
    if (primaryBoxes.isEmpty()) {
      return defaultFocus;
    }
    final List<String> focus = new ArrayList<>();
    for (int i = 0; i < centers; i++) {
      focus.add(
          ResearchCenters.toFocusEntry(
              selected(primaryBoxes.get(i)),
              mode == Mode.PRIMARY_AND_SECONDARY
                  ? selected(secondaryBoxes.get(i))
                  : ResearchCenters.getSecondary(defaultFocus.get(i))));
    }
    return focus;
  }

  private static String selected(final JComboBox<String> box) {
    final Object item = box.getSelectedItem();
    return item == null ? "" : item.toString();
  }
}
