package games.strategy.triplea.ui.research;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameDataEvent;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.delegate.data.ResearchResults;
import games.strategy.triplea.delegate.data.ResearchResults.Breakthrough;
import games.strategy.triplea.delegate.research.ResearchCenters;
import games.strategy.triplea.delegate.research.ResearchCenters.Mode;
import games.strategy.triplea.ui.research.ResearchBoard.Tag;
import games.strategy.triplea.ui.research.ResearchView.Category;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Flames and Steel research screen: every category of a nation with its techs in order, coloured by
 * state, with star tracks. During the nation's research step it also sets each centre's focus and
 * rolls; the results then appear on the board in place. Otherwise it is read-only.
 */
public final class ResearchScreen extends JDialog {
  private static final long serialVersionUID = 1L;

  /** The player's choices: one "primary|secondary" entry per centre, and the idle centre. */
  public record Choice(List<String> focus, int idleCenter) {}

  private static final Color[] CENTER_COLORS = {
    new Color(0x1E, 0x88, 0xE5),
    new Color(0x8E, 0x24, 0xAA),
    new Color(0x00, 0x89, 0x7B),
    new Color(0xF4, 0x51, 0x1E),
    new Color(0x6D, 0x4C, 0x41)
  };

  private GameData data;
  private final JComboBox<GamePlayer> nationBox = new JComboBox<>();
  private final JLabel centersLabel = new JLabel();
  private final JLabel oddsLabel = new JLabel();
  private final JLabel statusLabel = new JLabel(" ");
  private final JPanel focusBar = new JPanel();
  private final ResearchBoard board;
  private final JPanel resultsPanel = new JPanel();
  private final JButton actionButton = new JButton();
  private @Nullable ResearchView view;
  private boolean updatingNations;

  // the acting nation's research step
  private @Nullable GamePlayer acting;
  private @Nullable CompletableFuture<Choice> pendingChoice;
  private @Nullable CompletableFuture<Void> pendingContinue;
  private final List<String> primaries = new ArrayList<>();
  private final List<String> secondaries = new ArrayList<>();
  private int idleCenter = -1;
  private int activeSlot = -1;
  private @Nullable ResearchResults results;
  private Map<String, Integer> gained = Map.of();

  public ResearchScreen(final Frame owner, final GameData data) {
    super(owner, "Research", false);
    this.data = data;
    board =
        new ResearchBoard(
            new ResearchBoard.Listener() {
              @Override
              public void categoryClicked(final String category) {
                onCategoryClicked(category);
              }

              @Override
              public void categoryHovered(final @Nullable String category) {
                onCategoryHovered(category);
              }
            });
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(final WindowEvent e) {
            onClose();
          }
        });

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
          if (!updatingNations && nationBox.getSelectedItem() instanceof GamePlayer p) {
            showView(p);
          }
        });

    final JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    final JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
    titleRow.add(new JLabel("Nation:"));
    titleRow.add(nationBox);
    centersLabel.setFont(centersLabel.getFont().deriveFont(Font.BOLD, 14f));
    titleRow.add(centersLabel);
    titleRow.add(oddsLabel);
    top.add(titleRow);
    focusBar.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 2));
    top.add(focusBar);
    top.add(legend());

    final JScrollPane scroll = new JScrollPane(board);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getVerticalScrollBar().setUnitIncrement(23);

    resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
    resultsPanel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
    statusLabel.setForeground(new Color(0xB0, 0x30, 0x00));
    buttons.add(statusLabel);
    actionButton.setFont(actionButton.getFont().deriveFont(Font.BOLD, 14f));
    actionButton.addActionListener(e -> onAction());
    buttons.add(actionButton);
    final JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(resultsPanel, BorderLayout.CENTER);
    bottom.add(buttons, BorderLayout.EAST);

    getContentPane().setLayout(new BorderLayout());
    getContentPane().add(top, BorderLayout.NORTH);
    getContentPane().add(scroll, BorderLayout.CENTER);
    getContentPane().add(bottom, BorderLayout.SOUTH);
    listen();
  }

  public void setGameData(final GameData newData) {
    data = newData;
    listen();
    if (view != null) {
      final GamePlayer same = data.getPlayerList().getPlayerId(view.player().getName());
      if (same != null) {
        showNation(same);
      }
    }
  }

  private void listen() {
    final GameData listening = data;
    final Runnable refresh =
        () ->
            SwingUtilities.invokeLater(
                () -> {
                  if (data == listening && view != null && isVisible()) {
                    showView(view.player());
                  }
                });
    data.addGameDataEventListener(GameDataEvent.TECH_ATTACHMENT_CHANGED, refresh);
    data.addGameDataEventListener(GameDataEvent.GAME_STEP_CHANGED, refresh);
  }

  /** Opens the screen on a nation, read-only unless that nation is choosing its research now. */
  public void showNation(final GamePlayer nation) {
    fillNations(nation);
    showView(nation);
    open();
  }

  /** Starts the nation's research choice. Completes when the player presses Research. */
  public CompletableFuture<Choice> askForChoice(final GamePlayer nation) {
    if (!nation.equals(acting) || pendingContinue != null) {
      acting = nation;
      results = null;
      pendingContinue = null;
      try (GameData.Unlocker ignored = data.acquireReadLock()) {
        idleCenter = ResearchCenters.getDefaultIdleCenter(nation, data.getMap());
        final List<String> focus =
            ResearchCenters.getDefaultFocus(nation, idleCenter, data.getMap());
        primaries.clear();
        secondaries.clear();
        focus.forEach(
            entry -> {
              primaries.add(ResearchCenters.getPrimary(entry));
              secondaries.add(ResearchCenters.getSecondary(entry));
            });
      }
      activeSlot = -1;
      gained = Map.of();
    }
    pendingChoice = new CompletableFuture<>();
    statusLabel.setText(" ");
    fillNations(nation);
    showView(nation);
    if (activeSlot < 0) {
      activeSlot = nextSlot(-1);
      showView(nation);
    }
    open();
    return pendingChoice;
  }

  /** Shows the roll on the board. Completes when the player presses Continue. */
  public CompletableFuture<Void> showResults(final GamePlayer nation, final ResearchResults r) {
    acting = nation;
    results = r;
    pendingChoice = null;
    activeSlot = -1;
    pendingContinue = new CompletableFuture<>();
    final Map<String, Integer> stars = new HashMap<>();
    for (final Breakthrough b : r.getDetails()) {
      if (b.gainedStar()) {
        stars.merge(b.tech(), 1, Integer::sum);
      }
    }
    gained = Map.copyOf(stars);
    fillNations(nation);
    showView(nation);
    open();
    return pendingContinue;
  }

  /** The game is ending: release anyone waiting and close. */
  public void shutDown() {
    if (pendingChoice != null) {
      pendingChoice.cancel(true);
    }
    if (pendingContinue != null) {
      pendingContinue.complete(null);
    }
    dispose();
  }

  /** Whether a nation is waiting for its research choice on this screen. */
  public boolean isChoosing() {
    return pendingChoice != null;
  }

  /** Reopens the screen where the acting nation left off. */
  public void reopen() {
    if (acting != null) {
      fillNations(acting);
      showView(acting);
    }
    open();
  }

  private void open() {
    if (!isVisible()) {
      final Rectangle owner = getOwner().getBounds();
      setSize(
          new Dimension(
              Math.max(900, (int) (owner.width * 0.92)),
              Math.max(600, (int) (owner.height * 0.9))));
      setLocationRelativeTo(getOwner());
      setVisible(true);
    }
    toFront();
  }

  private void fillNations(final GamePlayer selected) {
    updatingNations = true;
    try {
      nationBox.removeAllItems();
      for (final GamePlayer p : ResearchView.nations(data)) {
        nationBox.addItem(p);
      }
      nationBox.setSelectedItem(selected);
    } finally {
      updatingNations = false;
    }
  }

  private boolean editing() {
    return view != null && pendingChoice != null && view.player().equals(acting);
  }

  private void showView(final GamePlayer nation) {
    view = ResearchView.read(data, nation);
    setTitle(nation.getName() + " research");
    centersLabel.setText(nation.getName() + ": " + view.centersText());
    oddsLabel.setText(view.oddsText());
    board.setView(view);
    board.setNewStars(nation.equals(acting) && results != null ? gained : Map.of());
    board.setSelecting(editing() && activeSlot >= 0);
    board.setTags(tags());
    board.setGreyed(greyed());
    buildFocusBar();
    buildResults();
    updateButton();
  }

  private boolean workingCenter(final int center) {
    return view != null && (!view.capitalLost() || center != idleCenter);
  }

  private boolean hasSecondary() {
    return view != null && view.mode() == Mode.PRIMARY_AND_SECONDARY;
  }

  private int slotCount() {
    return view == null || view.mode() == Mode.AUTOMATIC ? 0 : 2 * view.centers();
  }

  private boolean slotUsable(final int slot) {
    return workingCenter(slot / 2) && (slot % 2 == 0 || hasSecondary());
  }

  private int nextSlot(final int after) {
    for (int s = after + 1; s < slotCount(); s++) {
      if (slotUsable(s)) {
        return s;
      }
    }
    return -1;
  }

  private String slotValue(final int slot) {
    final List<String> list = slot % 2 == 0 ? primaries : secondaries;
    return slot / 2 < list.size() ? list.get(slot / 2) : "";
  }

  private void setSlotValue(final int slot, final String category) {
    (slot % 2 == 0 ? primaries : secondaries).set(slot / 2, category);
  }

  /** Focus tags per category: the choices being made, or the nation's stored focus. */
  private Map<String, List<Tag>> tags() {
    final Map<String, List<Tag>> tags = new LinkedHashMap<>();
    if (view == null || view.mode() == Mode.AUTOMATIC) {
      return tags;
    }
    final boolean editing = editing() || (view.player().equals(acting) && results != null);
    for (int center = 0; center < view.centers(); center++) {
      if (editing && !workingCenter(center)) {
        continue;
      }
      final Color color = CENTER_COLORS[center % CENTER_COLORS.length];
      final String primary;
      final String secondary;
      if (editing) {
        primary = primaries.get(center);
        secondary = secondaries.get(center);
      } else {
        final String entry =
            center < view.storedFocus().size() ? view.storedFocus().get(center) : "";
        primary = ResearchCenters.getPrimary(entry);
        secondary = ResearchCenters.getSecondary(entry);
      }
      tags.computeIfAbsent(primary, k -> new ArrayList<>())
          .add(new Tag("C" + (center + 1) + " P", color, true));
      if (hasSecondary() && !secondary.isEmpty()) {
        tags.computeIfAbsent(secondary, k -> new ArrayList<>())
            .add(new Tag("C" + (center + 1) + " S", color, false));
      }
    }
    return tags;
  }

  /** While choosing a slot: categories taken by another slot can't be chosen. */
  private Set<String> greyed() {
    final Set<String> greyed = new HashSet<>();
    if (!editing() || activeSlot < 0) {
      return greyed;
    }
    for (int s = 0; s < slotCount(); s++) {
      if (s != activeSlot && slotUsable(s)) {
        greyed.add(slotValue(s));
      }
    }
    view.categories().stream().filter(Category::isComplete).forEach(c -> greyed.add(c.name()));
    return greyed;
  }

  private Optional<String> whyNot(final String category) {
    if (view == null) {
      return Optional.empty();
    }
    for (final Category c : view.categories()) {
      if (c.name().equals(category) && c.isComplete()) {
        return Optional.of(category + " is complete.");
      }
    }
    for (int s = 0; s < slotCount(); s++) {
      if (s != activeSlot && slotUsable(s) && slotValue(s).equals(category)) {
        return Optional.of(category + " is already the focus of " + slotName(s) + ".");
      }
    }
    return Optional.empty();
  }

  private static String slotName(final int slot) {
    return "centre " + (slot / 2 + 1) + (slot % 2 == 0 ? " (primary)" : " (secondary)");
  }

  private void onCategoryClicked(final String category) {
    if (!editing() || activeSlot < 0) {
      return;
    }
    final Optional<String> reason = whyNot(category);
    if (reason.isPresent()) {
      statusLabel.setText(reason.get());
      return;
    }
    setSlotValue(activeSlot, category);
    activeSlot = nextSlot(activeSlot);
    statusLabel.setText(" ");
    showView(acting);
  }

  private void onCategoryHovered(final @Nullable String category) {
    if (!editing() || activeSlot < 0) {
      return;
    }
    statusLabel.setText(
        category == null
            ? " "
            : whyNot(category)
                .orElse("Click to make " + category + " the focus of " + slotName(activeSlot)));
  }

  private void buildFocusBar() {
    focusBar.removeAll();
    if (view == null || view.centers() == 0) {
      focusBar.revalidate();
      return;
    }
    final boolean editing = editing();
    if (view.mode() == Mode.AUTOMATIC) {
      focusBar.add(new JLabel("No focus needed: every unfinished category gains a star."));
    } else if (!editing) {
      focusBar.add(
          new JLabel(
              view.player().equals(acting) && results != null
                  ? "Focus used this turn is tagged on the categories (P primary, S secondary)."
                  : "Last chosen focus is tagged on the categories (P primary, S secondary)."));
    } else {
      final ButtonGroup idleGroup = new ButtonGroup();
      for (int center = 0; center < view.centers(); center++) {
        final JPanel box = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        final Color color = CENTER_COLORS[center % CENTER_COLORS.length];
        box.setBorder(BorderFactory.createLineBorder(color, 2, true));
        final JLabel name = new JLabel(" Centre " + (center + 1));
        name.setForeground(color);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        box.add(name);
        final boolean working = workingCenter(center);
        box.add(slotButton(2 * center, "P: ", working));
        if (hasSecondary()) {
          box.add(slotButton(2 * center + 1, "S: ", working));
        }
        if (view.capitalLost()) {
          final JRadioButton idle = new JRadioButton("idle", center == idleCenter);
          final int c = center;
          idle.addActionListener(
              e -> {
                idleCenter = c;
                activeSlot = nextSlot(-1);
                showView(acting);
              });
          idleGroup.add(idle);
          box.add(idle);
        }
        focusBar.add(box);
      }
      focusBar.add(Box.createHorizontalStrut(8));
      focusBar.add(
          new JLabel(
              activeSlot >= 0
                  ? "Click a category for " + slotName(activeSlot) + "."
                  : "Click a P or S button to change it."));
    }
    focusBar.revalidate();
    focusBar.repaint();
  }

  private JToggleButton slotButton(final int slot, final String prefix, final boolean working) {
    final String value = slotValue(slot);
    final JToggleButton button =
        new JToggleButton(prefix + (value.isEmpty() ? "(choose)" : value), slot == activeSlot);
    button.setEnabled(working);
    button.setFocusable(false);
    button.addActionListener(
        e -> {
          activeSlot = activeSlot == slot ? -1 : slot;
          showView(acting);
        });
    return button;
  }

  private void buildResults() {
    resultsPanel.removeAll();
    if (view != null && view.player().equals(acting) && results != null) {
      if (results.getDetails().isEmpty()) {
        resultsPanel.add(
            new JLabel(
                "<html>"
                    + (results.getBreakthroughs().isEmpty()
                        ? "No breakthroughs this turn."
                        : String.join("<br>", results.getBreakthroughs()))
                    + "</html>"));
      }
      for (final Breakthrough b : results.getDetails()) {
        resultsPanel.add(new JLabel(resultLine(b)));
      }
      if (results.getDetails().stream().anyMatch(Breakthrough::unlocked)) {
        final JLabel note =
            new JLabel("Gold techs are complete and take effect at the end of the turn.");
        note.setIcon(swatch(ResearchBoard.UNLOCKED_FILL, ResearchBoard.UNLOCKED_BORDER));
        resultsPanel.add(note);
      }
    } else if (view != null && view.hasFixedTechs()) {
      resultsPanel.add(new JLabel("Fixed techs arrive at the end of the turn shown on them."));
    }
    resultsPanel.revalidate();
    resultsPanel.repaint();
  }

  private static String resultLine(final Breakthrough b) {
    final StringBuilder sb = new StringBuilder("<html><b>");
    sb.append(
        b.center() > 0
            ? "Centre " + b.center() + "</b> (" + b.source() + ")"
            : capitalize(b.source()) + "</b>");
    sb.append(": ");
    if (!b.gainedStar()) {
      sb.append(b.category() == null ? "no category left" : "no breakthrough");
      return sb.append("</html>").toString();
    }
    sb.append(b.category())
        .append(" &rarr; ")
        .append(ResearchView.displayName(b.tech()))
        .append(" ");
    for (int s = 0; s < b.required(); s++) {
      if (s == b.stars() - 1) {
        sb.append("<font color='#ff4f00'>&#9733;</font>");
      } else if (s < b.stars()) {
        sb.append("<font color='#e0a800'>&#9733;</font>");
      } else {
        sb.append("<font color='#9a9a9a'>&#9734;</font>");
      }
    }
    if (b.unlocked()) {
      sb.append(" &nbsp;<b>complete, unlocks at end of turn</b>");
    }
    return sb.append("</html>").toString();
  }

  private static String capitalize(final String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private void updateButton() {
    if (pendingContinue != null && view != null && view.player().equals(acting)) {
      actionButton.setText("Continue");
      actionButton.setEnabled(true);
    } else if (pendingChoice != null) {
      final boolean own = view != null && view.player().equals(acting);
      actionButton.setText(own ? "Research" : "Back to " + acting.getName());
      actionButton.setEnabled(!pendingChoice.isDone());
    } else {
      actionButton.setText("Close");
      actionButton.setEnabled(true);
    }
    getRootPane().setDefaultButton(actionButton);
  }

  private void onAction() {
    if (pendingContinue != null && view != null && view.player().equals(acting)) {
      final CompletableFuture<Void> done = pendingContinue;
      pendingContinue = null;
      results = null;
      acting = null;
      gained = Map.of();
      setVisible(false);
      done.complete(null);
    } else if (pendingChoice != null) {
      if (view == null || !view.player().equals(acting)) {
        fillNations(acting);
        showView(acting);
        return;
      }
      final List<String> focus = new ArrayList<>();
      for (int i = 0; i < primaries.size(); i++) {
        focus.add(ResearchCenters.toFocusEntry(primaries.get(i), secondaries.get(i)));
      }
      final Optional<String> error;
      try (GameData.Unlocker ignored = data.acquireReadLock()) {
        error = ResearchCenters.validateFocus(acting, focus, idleCenter, data.getMap());
      }
      if (error.isPresent()) {
        statusLabel.setText(error.get());
        return;
      }
      statusLabel.setText("Rolling…");
      final CompletableFuture<Choice> choice = pendingChoice;
      activeSlot = -1;
      choice.complete(new Choice(focus, idleCenter));
      showView(acting);
      actionButton.setEnabled(false);
    } else {
      setVisible(false);
    }
  }

  private void onClose() {
    if (pendingContinue != null) {
      onAction();
    } else {
      // a pending choice stays; the Research tab reopens it
      setVisible(false);
    }
  }

  private JPanel legend() {
    final JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 2));
    legend.add(
        legendLabel("Researched", swatch(ResearchBoard.OWNED_FILL, ResearchBoard.OWNED_BORDER)));
    legend.add(
        legendLabel(
            "Not yet researched", swatch(ResearchBoard.OPEN_FILL, ResearchBoard.OPEN_BORDER)));
    legend.add(
        legendLabel(
            "Complete, unlocks at end of turn",
            swatch(ResearchBoard.UNLOCKED_FILL, ResearchBoard.UNLOCKED_BORDER)));
    legend.add(legendLabel("Star gained", starIcon(true)));
    legend.add(legendLabel("Star still needed", starIcon(false)));
    legend.add(
        legendLabel("New this turn", swatch(ResearchBoard.OPEN_FILL, ResearchBoard.NEW_MARK)));
    legend.add(new JLabel("▸ next tech in the category"));
    return legend;
  }

  private static JLabel legendLabel(final String text, final Icon icon) {
    final JLabel label = new JLabel(text, icon, JLabel.LEFT);
    label.setFont(label.getFont().deriveFont(11f));
    return label;
  }

  static Icon swatch(final Color fill, final Color border) {
    return new Icon() {
      @Override
      public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        final Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(fill);
        g2.fillRoundRect(x, y + 1, 22, 12, 6, 6);
        g2.setColor(border);
        g2.drawRoundRect(x, y + 1, 22, 12, 6, 6);
        g2.dispose();
      }

      @Override
      public int getIconWidth() {
        return 24;
      }

      @Override
      public int getIconHeight() {
        return 14;
      }
    };
  }

  static Icon starIcon(final boolean filled) {
    return new Icon() {
      @Override
      public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        final Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ResearchBoard.paintStar(g2, x + 7, y + 7, 6, filled);
        g2.dispose();
      }

      @Override
      public int getIconWidth() {
        return 15;
      }

      @Override
      public int getIconHeight() {
        return 14;
      }
    };
  }
}
