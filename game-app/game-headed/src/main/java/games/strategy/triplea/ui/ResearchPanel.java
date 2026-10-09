package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameDataEvent;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.delegate.research.ResearchCenters;
import games.strategy.triplea.delegate.research.ResearchCenters.Mode;
import games.strategy.triplea.ui.research.ResearchView;
import games.strategy.triplea.ui.research.ResearchView.Category;
import games.strategy.triplea.ui.research.ResearchView.State;
import games.strategy.triplea.ui.research.ResearchView.Tech;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Flames and Steel: compact research summary for one nation (research is public): per category the
 * current tech with its stars and the focus tags. Clicking opens the full research screen.
 */
class ResearchPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final String FILLED = "<font color='#e0a800'>&#9733;</font>";
  private static final String EMPTY = "<font color='#9a9a9a'>&#9734;</font>";
  private static final String[] CENTER_COLORS = {
    "#1e88e5", "#8e24aa", "#00897b", "#f4511e", "#6d4c41"
  };

  private final JEditorPane text = new JEditorPane("text/html", "");
  private final JComboBox<GamePlayer> nationBox = new JComboBox<>();
  private final Consumer<GamePlayer> opener;
  private GameData gameData;
  private boolean updating;

  /** Whether the nation shown follows the player whose turn it is. */
  private boolean followTurn = true;

  ResearchPanel(final GameData data, final Consumer<GamePlayer> opener) {
    super(new BorderLayout());
    this.opener = opener;
    gameData = data;
    text.setEditable(false);
    text.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    text.setToolTipText("Click to open the research screen");
    text.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            openScreen();
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
          if (!updating) {
            followTurn = false;
            refresh();
          }
        });
    final JButton open = new JButton("Research screen");
    open.addActionListener(e -> openScreen());
    final JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    top.add(nationBox);
    top.add(open);
    add(top, BorderLayout.NORTH);
    add(new JScrollPane(text), BorderLayout.CENTER);
    listen();
    refresh();
  }

  /** Whether any nation in this game has research centres. */
  static boolean isUsed(final GameData data) {
    return data.getPlayerList().getPlayers().stream()
        .anyMatch(p -> ResearchCenters.getResearchCenters(p) > 0);
  }

  void setGameData(final GameData data) {
    gameData = data;
    listen();
    refresh();
  }

  private void openScreen() {
    if (nationBox.getSelectedItem() instanceof GamePlayer p) {
      opener.accept(p);
    }
  }

  private void listen() {
    final GameData listening = gameData;
    gameData.addGameDataEventListener(
        GameDataEvent.TECH_ATTACHMENT_CHANGED, () -> refreshIfCurrent(listening));
    gameData.addGameDataEventListener(
        GameDataEvent.GAME_STEP_CHANGED,
        () -> {
          followTurn = true;
          refreshIfCurrent(listening);
        });
  }

  private void refreshIfCurrent(final GameData listening) {
    if (listening == gameData) {
      refresh();
    }
  }

  private void refresh() {
    SwingUtilities.invokeLater(
        () -> {
          final GamePlayer shown = chooseNation();
          if (shown == null) {
            text.setText("");
            return;
          }
          text.setText(render(ResearchView.read(gameData, shown)));
          text.setCaretPosition(0);
        });
  }

  private @Nullable GamePlayer chooseNation() {
    final List<GamePlayer> nations = ResearchView.nations(gameData);
    GamePlayer selected =
        nationBox.getSelectedItem() instanceof GamePlayer p
            ? gameData.getPlayerList().getPlayerId(p.getName())
            : null;
    if (followTurn || selected == null || !nations.contains(selected)) {
      selected = ResearchView.defaultNation(gameData);
    }
    updating = true;
    try {
      final List<GamePlayer> current = new ArrayList<>();
      for (int i = 0; i < nationBox.getItemCount(); i++) {
        current.add(nationBox.getItemAt(i));
      }
      if (!current.equals(nations)) {
        nationBox.removeAllItems();
        nations.forEach(nationBox::addItem);
      }
      nationBox.setSelectedItem(selected);
    } finally {
      updating = false;
    }
    return selected;
  }

  private static String render(final ResearchView view) {
    final StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif'>");
    sb.append("<b>").append(view.player().getName()).append("</b><br>");
    sb.append(view.centersText()).append("<br>");
    if (!view.oddsText().isEmpty()) {
      sb.append("<font size='-1'>").append(view.oddsText()).append("</font>");
    }
    sb.append("<table cellpadding='1' cellspacing='0'>");
    for (final Category category : view.categories()) {
      sb.append("<tr><td valign='top'><b>")
          .append(category.name())
          .append("</b></td><td valign='top'>");
      if (category.isComplete()) {
        sb.append("<font color='#2e7d32'>complete</font>");
      } else {
        final Tech tech = category.techs().get(category.nextIndex());
        sb.append(tech.displayName()).append(" ");
        if (tech.fixedRound() > 0) {
          sb.append("(turn ").append(tech.fixedRound()).append(")");
        } else {
          sb.append(FILLED.repeat(tech.stars()))
              .append(EMPTY.repeat(tech.required() - tech.stars()));
        }
      }
      final long unlocked =
          category.techs().stream().filter(t -> t.state() == State.UNLOCKED).count();
      if (unlocked > 0) {
        sb.append(" <font color='#b28000'><b>+")
            .append(unlocked)
            .append(" at end of turn</b></font>");
      }
      sb.append("</td><td valign='top'>")
          .append(focusTags(view, category.name()))
          .append("</td></tr>");
    }
    sb.append("</table>");
    return sb.append("</body></html>").toString();
  }

  private static String focusTags(final ResearchView view, final String category) {
    if (view.mode() == Mode.AUTOMATIC) {
      return "";
    }
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < view.storedFocus().size() && i < view.centers(); i++) {
      final String color = CENTER_COLORS[i % CENTER_COLORS.length];
      final String entry = view.storedFocus().get(i);
      if (ResearchCenters.getPrimary(entry).equals(category)) {
        sb.append("<font color='")
            .append(color)
            .append("'><b>C")
            .append(i + 1)
            .append("P</b></font> ");
      }
      if (view.mode() == Mode.PRIMARY_AND_SECONDARY
          && ResearchCenters.getSecondary(entry).equals(category)) {
        sb.append("<font color='").append(color).append("'>C").append(i + 1).append("S</font> ");
      }
    }
    return sb.toString();
  }
}
