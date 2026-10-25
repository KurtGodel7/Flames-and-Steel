package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.Player;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirStage.Choice;
import games.strategy.triplea.delegate.air.AirStage.Landing;
import games.strategy.triplea.delegate.air.AirStage.Stage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Plays the simultaneous air stages in a test (requests 35 and 38): each nation answers the bomber
 * and fighter stages, and planes go home, with the choices given here (nothing for a nation not
 * given), and the stage steps run on the game's own "airStage" delegate, which hands the choices to
 * the air battles. Then {@link #battles()} runs "airStrikes".
 *
 * <p>A choice the stage refuses is not offered again: the nation then chooses nothing, and the
 * reason it was given is kept in {@link #problems()}.
 */
public final class AirStageRunner {
  private final GameData data;
  private final Map<GamePlayer, Choice> bombers = new LinkedHashMap<>();
  private final Map<GamePlayer, Choice> fighters = new LinkedHashMap<>();
  private final Map<GamePlayer, Choice> home = new LinkedHashMap<>();
  private final Map<GamePlayer, Player> clients = new LinkedHashMap<>();
  private final List<String> problems = Collections.synchronizedList(new ArrayList<>());
  private final Set<GamePlayer> asked = Collections.synchronizedSet(new LinkedHashSet<>());
  private int die = 0;
  private Consumer<IDelegateBridge> setUp = b -> {};
  private IDelegateBridge last;

  public AirStageRunner(final GameData data) {
    this.data = data;
  }

  /** The nation's bomber targets, from the bases the bombers are on. */
  public AirStageRunner bombers(final GamePlayer player, final TargetAssignment... targets) {
    bombers.put(player, Choice.of(List.of(targets)));
    return this;
  }

  /** The nation's fighter placements (cover over territories), from the bases they are on. */
  public AirStageRunner cover(final GamePlayer player, final TargetAssignment... placements) {
    fighters.put(player, Choice.of(List.of(placements)));
    return this;
  }

  /** The nation's landings at planes go home (others get the default). */
  public AirStageRunner home(final GamePlayer player, final Landing... landings) {
    home.put(player, new Choice(List.of(), List.of(landings)));
    return this;
  }

  /** The remainder die every roll gets: 0 always hits, 5 always misses. */
  public AirStageRunner die(final int value) {
    die = value;
    return this;
  }

  /** More set-up for every bridge (e.g. answers to other questions). */
  public AirStageRunner bridgeSetUp(final Consumer<IDelegateBridge> more) {
    setUp = more;
    return this;
  }

  /** The bridge of the last step run. */
  public IDelegateBridge lastBridge() {
    return last;
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
    for (final String step :
        List.of("airBomberStage", "airRevealBombers", "airFighterStage", "airRevealFighters")) {
      step(step);
    }
    return this;
  }

  /** Runs the air battles ("airStrikes"). */
  public AirStageRunner battles() {
    final AirCombatDelegate combat = (AirCombatDelegate) data.getDelegate("airCombat");
    final IDelegateBridge bridge = bridge();
    advanceToStep(bridge, "airStrikes");
    combat.setDelegateBridgeAndPlayer(bridge);
    combat.start();
    combat.end();
    return this;
  }

  /** Runs planes go home. */
  public AirStageRunner planesHome() {
    step("airPlanesHome");
    return this;
  }

  private void step(final String step) {
    final AirStageDelegate stage = (AirStageDelegate) data.getDelegate("airStage");
    if (stage == null) {
      throw new IllegalStateException("the game has no airStage delegate");
    }
    final IDelegateBridge bridge = bridge();
    advanceToStep(bridge, step);
    stage.setDelegateBridgeAndPlayer(bridge);
    stage.start();
    stage.end();
  }

  /** A bridge for any step: remainder dice as set, every nation answered from here. */
  public IDelegateBridge bridge() {
    final GamePlayer any = data.getPlayerList().getPlayers().get(0);
    final IDelegateBridge bridge = newDelegateBridge(any);
    when(bridge.getRemotePlayer(any(GamePlayer.class)))
        .thenAnswer(i -> client(i.getArgument(0)));
    when(bridge.getRandom(anyInt(), any(), any(), anyString()))
        .thenAnswer(i -> Math.min(die, i.<Integer>getArgument(0) - 1));
    setUp.accept(bridge);
    last = bridge;
    return bridge;
  }

  private synchronized Player client(final GamePlayer player) {
    return clients.computeIfAbsent(
        player,
        p -> {
          final Player client = mock(Player.class);
          when(client.selectAirStage(any(), any(), any(), any(), anyString()))
              .thenAnswer(
                  i -> {
                    asked.add(p);
                    final String problem = i.getArgument(4);
                    if (!problem.isEmpty()) {
                      problems.add(p.getName() + ": " + problem);
                      return Choice.none();
                    }
                    final Stage s = i.getArgument(0);
                    return (switch (s) {
                          case BOMBERS -> bombers;
                          case FIGHTERS -> fighters;
                          case HOME -> home;
                        })
                        .getOrDefault(p, Choice.none());
                  });
          return client;
        });
  }
}
