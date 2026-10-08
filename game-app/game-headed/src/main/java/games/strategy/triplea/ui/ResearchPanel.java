package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameDataEvent;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.research.ResearchCenters;
import java.awt.BorderLayout;
import java.util.List;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Flames and Steel: shows every nation's research centres, focus and star tracks. Research is
 * public, so every player sees all nations.
 */
class ResearchPanel extends JPanel {
  private static final String FILLED = "<font color='#e0a800'>&#9733;</font>";
  private static final String EMPTY = "<font color='#9a9a9a'>&#9734;</font>";

  private final JEditorPane text = new JEditorPane("text/html", "");
  private GameData gameData;

  ResearchPanel(final GameData data) {
    super(new BorderLayout());
    gameData = data;
    text.setEditable(false);
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

  private void listen() {
    gameData.addGameDataEventListener(GameDataEvent.TECH_ATTACHMENT_CHANGED, () -> refresh());
    gameData.addGameDataEventListener(GameDataEvent.GAME_STEP_CHANGED, () -> refresh());
  }

  private void refresh() {
    final String html;
    try (GameData.Unlocker ignored = gameData.acquireReadLock()) {
      html = render(gameData.getPlayerList().getPlayers());
    }
    SwingUtilities.invokeLater(
        () -> {
          text.setText(html);
          text.setCaretPosition(0);
        });
  }

  private static String render(final List<GamePlayer> players) {
    final StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif'>");
    for (final GamePlayer player : players) {
      final int centers = ResearchCenters.getResearchCenters(player);
      if (centers == 0) {
        continue;
      }
      sb.append("<h3>")
          .append(player.getName())
          .append(": ")
          .append(centers)
          .append(" research centre")
          .append(centers == 1 ? "" : "s")
          .append("</h3>");
      final List<String> focus = player.getTechAttachment().getResearchFocus();
      for (int i = 0; i < focus.size(); i++) {
        final String secondary = ResearchCenters.getSecondary(focus.get(i));
        sb.append("Centre ")
            .append(i + 1)
            .append(": ")
            .append(ResearchCenters.getPrimary(focus.get(i)))
            .append(secondary.isEmpty() ? "" : " / " + secondary)
            .append("<br>");
      }
      sb.append("<table cellpadding='1'>");
      for (final TechnologyFrontier category : ResearchCenters.getCategories(player)) {
        sb.append("<tr><td valign='top'><b>").append(category.getName()).append("</b></td><td>");
        for (final TechAdvance tech : category.getTechs()) {
          sb.append(renderTech(player, tech)).append("<br>");
        }
        sb.append("</td></tr>");
      }
      sb.append("</table>");
    }
    return sb.append("</body></html>").toString();
  }

  private static String renderTech(final GamePlayer player, final TechAdvance tech) {
    if (ResearchCenters.isOwned(player, tech)) {
      return "&#10003; " + tech.getName();
    }
    final int required = ResearchCenters.getStarsRequired(tech);
    final int stars = Math.min(required, ResearchCenters.getStars(player, tech));
    return tech.getName()
        + " "
        + FILLED.repeat(stars)
        + EMPTY.repeat(required - stars)
        + (stars >= required ? " <i>unlocked, takes effect at end of turn</i>" : "");
  }
}
