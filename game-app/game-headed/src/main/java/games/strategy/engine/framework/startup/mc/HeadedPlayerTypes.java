package games.strategy.engine.framework.startup.mc;

import static games.strategy.engine.framework.startup.ui.PlayerTypes.PLAYER_TYPE_HUMAN_LABEL;

import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.TripleAPlayer;
import games.strategy.triplea.settings.ClientSetting;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.triplea.ai.does.nothing.DoesNothingAi;
import org.triplea.ai.flowfield.FlowFieldAi;

public class HeadedPlayerTypes {

  private static final String PLAYER_TYPE_FLOW_FIELD_LABEL = "FlowField (AI)";

  public static final PlayerTypes.Type HUMAN_PLAYER =
      new PlayerTypes.Type(PLAYER_TYPE_HUMAN_LABEL) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new TripleAPlayer(name, getLabel(), false);
        }
      };

  /** A hidden player type to represent network connected players. */
  public static final PlayerTypes.Type CLIENT_PLAYER =
      new PlayerTypes.Type(PlayerTypes.PLAYER_TYPE_DEFAULT_LABEL, false) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new TripleAPlayer(name, getLabel(), true);
        }
      };

  public static final PlayerTypes.Type DOES_NOTHING_PLAYER =
      new PlayerTypes.Type(PlayerTypes.DOES_NOTHING_PLAYER_LABEL) {
        @Override
        public Player newPlayerWithName(String name) {
          return new DoesNothingAi(name, getLabel());
        }
      };

  private static boolean filterBetaPlayerType(final PlayerTypes.Type playerType) {
    if (playerType.getLabel().equals(PLAYER_TYPE_FLOW_FIELD_LABEL)) {
      return ClientSetting.showBetaFeatures.getValue().orElse(false);
    }
    return true;
  }

  private static PlayerTypes.Type getFlowFieldType() {
    return new PlayerTypes.Type(PLAYER_TYPE_FLOW_FIELD_LABEL) {
      @Override
      public Player newPlayerWithName(String name) {
        return new FlowFieldAi(name, getLabel());
      }
    };
  }

  /**
   * Flames and Steel (request 68, part B): the types offered for one nation of this game. For a
   * human-players-only game: Human (and the hidden network type), or only "Does Nothing" for a
   * nation that never takes a turn. {@code player} null means the "set all" list.
   */
  public static Collection<PlayerTypes.Type> getPlayerTypes(
      final @javax.annotation.Nullable games.strategy.engine.data.GameState data,
      final @javax.annotation.Nullable games.strategy.engine.data.GamePlayer player) {
    if (!games.strategy.triplea.delegate.HumanPlayersOnly.applies(data)) {
      return getPlayerTypes();
    }
    if (player != null && games.strategy.triplea.delegate.TurnlessPlayers.isTurnless(player)) {
      return List.of(DOES_NOTHING_PLAYER, CLIENT_PLAYER);
    }
    return List.of(HUMAN_PLAYER, CLIENT_PLAYER);
  }

  public static Collection<PlayerTypes.Type> getPlayerTypes() {
    return Stream.of(
            // The first item in this list will be the default when hosting
            List.of(HUMAN_PLAYER),
            PlayerTypes.getBuiltInPlayerTypes(),
            List.of(CLIENT_PLAYER, DOES_NOTHING_PLAYER, getFlowFieldType()))
        .flatMap(Collection::stream)
        .filter(HeadedPlayerTypes::filterBetaPlayerType)
        .collect(Collectors.toList());
  }
}
