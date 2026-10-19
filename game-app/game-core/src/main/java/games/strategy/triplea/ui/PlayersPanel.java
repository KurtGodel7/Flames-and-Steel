package games.strategy.triplea.ui;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.framework.IGame;
import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import lombok.experimental.UtilityClass;
import org.triplea.swing.jpanel.JPanelBuilder;

/** Panel to show who is playing which players. */
@UtilityClass
public class PlayersPanel {

  /**
   * "Dutch: no turns; at peace with every nation" or "Dutch: no turns; at war with Japanese; allied
   * with British, Americans".
   */
  static String turnlessText(final GamePlayer player) {
    final var tracker = player.getData().getRelationshipTracker();
    final java.util.List<String> war = new java.util.ArrayList<>();
    final java.util.List<String> allied = new java.util.ArrayList<>();
    for (final GamePlayer other : player.getData().getPlayerList().getPlayers()) {
      if (other.equals(player)) {
        continue;
      }
      if (tracker.isAtWar(player, other)) {
        war.add(other.getName());
      } else if (tracker.isAllied(player, other)) {
        allied.add(other.getName());
      }
    }
    final StringBuilder sb = new StringBuilder(player.getName() + ": no turns; ");
    if (war.isEmpty() && allied.isEmpty()) {
      return sb.append("at peace with every nation").toString();
    }
    final java.util.List<String> parts = new java.util.ArrayList<>();
    if (!war.isEmpty()) {
      parts.add("at war with " + String.join(", ", war));
    }
    if (!allied.isEmpty()) {
      parts.add("allied with " + String.join(", ", allied));
    }
    return sb.append(String.join("; ", parts)).toString();
  }

  /**
   * Displays a dialog that shows which node (user) is controlling each player (nation, power,
   * etc.).
   */
  public static void showPlayers(final IGame game, final Component parent) {
    final JPanel panel = new JPanelBuilder().boxLayoutVertical().build();
    for (final String player : game.getPlayerManager().getPlayers()) {
      final GamePlayer gamePlayer = game.getData().getPlayerList().getPlayerId(player);
      if (games.strategy.triplea.delegate.TurnlessPlayers.isTurnless(gamePlayer)) {
        // Flames and Steel: a player that never takes a turn (the Dutch)
        panel.add(new JLabel(turnlessText(gamePlayer), JLabel.RIGHT));
      } else if (gamePlayer.isAi()) {
        panel.add(
            new JLabel(
                gamePlayer.getPlayerType().name + " is " + gamePlayer.getName(), JLabel.RIGHT));
      } else {
        panel.add(
            new JLabel(
                game.getPlayerManager().getNode(player).getName() + " is " + gamePlayer.getName(),
                JLabel.RIGHT));
      }
    }

    // a turnless player has no seat, so it may be missing from the player manager
    for (final GamePlayer gamePlayer : game.getData().getPlayerList().getPlayers()) {
      if (games.strategy.triplea.delegate.TurnlessPlayers.isTurnless(gamePlayer)
          && !game.getPlayerManager().getPlayers().contains(gamePlayer.getName())) {
        panel.add(new JLabel(turnlessText(gamePlayer), JLabel.RIGHT));
      }
    }

    JOptionPane.showMessageDialog(
        JOptionPane.getFrameForComponent(parent), panel, "Players", JOptionPane.PLAIN_MESSAGE);
  }
}
