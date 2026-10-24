package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.Player;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirStage.Choice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plays request 35's simultaneous air stages in a test: each nation answers the bomber and fighter
 * stages with the choices given here (nothing for a nation not given), and the four stage steps run
 * on the game's own "airStage" delegate, which hands the choices to the air battles. Then the test
 * runs "airStrikes" as before.
 *
 * <p>A choice the stage refuses is not offered again: the nation then chooses nothing, and the
 * reason it was given is kept in {@link #problems()}.
 */
public final class AirStageRunner {
  private final GameData data;
  private final Map<GamePlayer, Choice> bombers = new LinkedHashMap<>();
  private final Map<GamePlayer, Choice> fighters = new LinkedHashMap<>();
  private final Map<GamePlayer, Player> clients = new LinkedHashMap<>();
  private final List<String> problems = Collections.synchronizedList(new ArrayList<>());
  private final Set<GamePlayer> asked = Collections.synchronizedSet(new LinkedHashSet<>());

  public AirStageRunner(final GameData data) {
    this.data = data;
  }

  /** The nation's bomber targets, from the boxes the bombers are in (no moves). */
  public AirStageRunner bombers(final GamePlayer player, final TargetAssignment... targets) {
    return bombers(player, new Choice(List.of(), List.of(targets)));
  }

  public AirStageRunner bombers(final GamePlayer player, final Choice choice) {
    bombers.put(player, choice);
    return this;
  }

  /** The nation's fighter placements (cover over territories), from the boxes they are in. */
  public AirStageRunner cover(final GamePlayer player, final TargetAssignment... placements) {
    return cover(player, new Choice(List.of(), List.of(placements)));
  }

  public AirStageRunner cover(final GamePlayer player, final Choice choice) {
    fighters.put(player, choice);
    return this;
  }

  /** The reasons the stages gave for refusing a choice, "Nation: reason". */
  public List<String> problems() {
    synchronized (problems) {
      return List.copyOf(problems);
    }
  }

  /** The nations the stages asked to choose. */
  public Set<GamePlayer> asked() {
    synchronized (asked) {
      return Set.copyOf(asked);
    }
  }

  /** Runs the bomber stage and its reveal, then the fighter stage and its reveal. */
  public AirStageRunner run() {
    final AirStageDelegate stage = (AirStageDelegate) data.getDelegate("airStage");
    if (stage == null) {
      throw new IllegalStateException("the game has no airStage delegate");
    }
    for (final String step :
        List.of("airBomberStage", "airRevealBombers", "airFighterStage", "airRevealFighters")) {
      final IDelegateBridge bridge = bridge();
      advanceToStep(bridge, step);
      stage.setDelegateBridgeAndPlayer(bridge);
      stage.start();
      stage.end();
    }
    return this;
  }

  private IDelegateBridge bridge() {
    final GamePlayer any = data.getPlayerList().getPlayers().get(0);
    final IDelegateBridge bridge = newDelegateBridge(any);
    when(bridge.getRemotePlayer(any(GamePlayer.class)))
        .thenAnswer(i -> client(i.getArgument(0)));
    return bridge;
  }

  private synchronized Player client(final GamePlayer player) {
    return clients.computeIfAbsent(
        player,
        p -> {
          final Player client = mock(Player.class);
          when(client.selectAirStage(any(), any(), any(), any(), any(), anyString()))
              .thenAnswer(
                  i -> {
                    asked.add(p);
                    final String problem = i.getArgument(5);
                    if (!problem.isEmpty()) {
                      problems.add(p.getName() + ": " + problem);
                      return Choice.none();
                    }
                    final Stage s = i.getArgument(0);
                    return (s == Stage.BOMBERS ? bombers : fighters)
                        .getOrDefault(p, Choice.none());
                  });
          return client;
        });
  }
}
