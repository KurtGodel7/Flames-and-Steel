package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.player.Player;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirStage;
import games.strategy.triplea.delegate.air.AirStage.Choice;
import games.strategy.triplea.formatter.PlayerNames;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.Interruptibles;

/**
 * Flames and Steel (request 35 part 3): the air phase in two simultaneous stages. Steps (none
 * belongs to a nation):
 *
 * <ul>
 *   <li>"airBomberStage": every nation with bombers that can act is asked at once for its bomber
 *       moves and targets; nobody waits for anyone until all are done.
 *   <li>"airRevealBombers": the bomber moves are made and every choice is shown.
 *   <li>"airFighterStage": every nation with fighter-types that can act is asked at once for its
 *       fighter moves and placements, seeing every bomber choice.
 *   <li>"airRevealFighters": the fighter moves are made; the targets and placements go to the air
 *       battles ("airStrikes", {@link AirCombatDelegate}).
 * </ul>
 *
 * <p>Each answer is checked as it arrives (range, caps with the allies' answers so far, carrier
 * room, entry rules); a refused one goes back to that nation with the reason. Answers are kept here,
 * hidden from the players until the reveal, and saved with the game, so a game saved mid-stage asks
 * only the nations not yet done when it is loaded.
 */
@Slf4j
public class AirStageDelegate extends BaseTripleADelegate {
  private static final int ATTEMPTS = 10;

  private Map<GamePlayer, Choice> bomberChoices = new LinkedHashMap<>();
  private Map<GamePlayer, Choice> fighterChoices = new LinkedHashMap<>();
  private final transient Object lock = new Object();

  @Override
  public void start() {
    super.start();
    switch (stepName()) {
      case "airBomberStage" -> runStage(Stage.BOMBERS);
      case "airFighterStage" -> runStage(Stage.FIGHTERS);
      case "airRevealBombers" -> reveal(Stage.BOMBERS);
      case "airRevealFighters" -> reveal(Stage.FIGHTERS);
      default -> {
        // not an air stage step
      }
    }
  }

  private String stepName() {
    return getData().getSequence().getStep().getName();
  }

  /** Whether the game's sequence plays the air phase in stages (request 35). */
  public static boolean usesStages(final games.strategy.engine.data.GameState data) {
    for (final GameStep step : data.getSequence().getSteps()) {
      if (step.getName().equals("airBomberStage") || step.getName().equals("airFighterStage")) {
        return true;
      }
    }
    return false;
  }

  /** The nations in turn order (the order their own steps come in), each once. */
  private List<GamePlayer> turnOrder() {
    final List<GamePlayer> order = new ArrayList<>();
    for (final GameStep step : getData().getSequence().getSteps()) {
      final GamePlayer p = step.getPlayerId();
      if (p != null && !p.isNull() && !order.contains(p)) {
        order.add(p);
      }
    }
    return order;
  }

  private Map<GamePlayer, Choice> choices(final Stage stage) {
    return stage == Stage.BOMBERS ? bomberChoices : fighterChoices;
  }

  /** Asks every nation not done yet at once, and waits for all of them. */
  private void runStage(final Stage stage) {
    final Map<GamePlayer, Choice> accepted = choices(stage);
    final Map<GamePlayer, List<TargetAssignment>> bombers =
        stage == Stage.FIGHTERS ? AirStage.targetsOf(bomberChoices) : Map.of();
    final Map<GamePlayer, List<AirMove>> bomberMoves =
        stage == Stage.FIGHTERS ? AirStage.movesOf(bomberChoices) : Map.of();
    final List<GamePlayer> order = new ArrayList<>();
    for (final GamePlayer p : turnOrder()) {
      final boolean done;
      synchronized (lock) {
        done = accepted.containsKey(p);
      }
      if (!done
          && !Peacetime.isAtPeace(p)
          && AirStage.hasWork(p, stage, bombers, getData().getMap())) {
        order.add(p);
      }
    }
    if (order.isEmpty()) {
      return;
    }
    final String name = stage == Stage.BOMBERS ? "Bombers" : "Fighters";
    bridge
        .getHistoryWriter()
        .startEvent(
            name
                + ": "
                + String.join(", ", order.stream().map(PlayerNames::display).toList())
                + " choose at the same time");
    // proxies taken here, in the delegate's own thread
    final Map<GamePlayer, Player> remotes = new LinkedHashMap<>();
    for (final GamePlayer p : getData().getPlayerList().getPlayers()) {
      final Player remote = bridge.getRemotePlayer(p);
      if (remote != null) {
        remotes.put(p, remote);
      }
    }
    final List<GamePlayer> waiting = new ArrayList<>(order);
    final List<Thread> threads = new ArrayList<>();
    for (final GamePlayer p : order) {
      final Player remote = remotes.get(p);
      final Thread t =
          new Thread(
              () -> ask(p, remote, stage, order, accepted, bombers, bomberMoves, waiting, remotes),
              "air stage " + p.getName());
      threads.add(t);
    }
    threads.forEach(Thread::start);
    bridge.leaveDelegateExecution();
    try {
      for (final Thread t : threads) {
        Interruptibles.join(t);
      }
    } finally {
      bridge.enterDelegateExecution();
    }
    synchronized (lock) {
      for (final GamePlayer p : order) {
        accepted.putIfAbsent(p, Choice.none());
      }
    }
  }

  /** One nation's question, asked again with the reason until its answer is allowed. */
  private void ask(
      final GamePlayer p,
      final Player remote,
      final Stage stage,
      final List<GamePlayer> order,
      final Map<GamePlayer, Choice> accepted,
      final Map<GamePlayer, List<TargetAssignment>> bombers,
      final Map<GamePlayer, List<AirMove>> bomberMoves,
      final List<GamePlayer> waiting,
      final Map<GamePlayer, Player> remotes) {
    Choice start = Choice.none();
    String problem = "";
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      Choice choice;
      try {
        choice =
            remote == null
                ? Choice.none()
                : remote.selectAirStage(stage, order, start, bombers, bomberMoves, problem);
      } catch (final RuntimeException e) {
        log.warn("Air stage: " + p.getName() + " could not be asked", e);
        choice = Choice.none();
      }
      if (choice == null) {
        choice = Choice.none();
      }
      final Optional<String> error;
      synchronized (lock) {
        final Map<GamePlayer, Choice> others = new LinkedHashMap<>(accepted);
        others.remove(p);
        error = AirStage.problem(p, stage, choice, others, bombers);
        if (error.isEmpty()) {
          accepted.put(p, choice);
          waiting.remove(p);
        }
      }
      if (error.isEmpty()) {
        tellWaiting(waiting, remotes);
        return;
      }
      problem = error.get();
      start = choice;
    }
    synchronized (lock) {
      accepted.put(p, Choice.none());
      waiting.remove(p);
    }
    tellWaiting(waiting, remotes);
  }

  /** Every player hears who is still choosing (names only). */
  private void tellWaiting(final List<GamePlayer> waiting, final Map<GamePlayer, Player> remotes) {
    final List<String> names;
    synchronized (lock) {
      names = waiting.stream().map(PlayerNames::display).toList();
    }
    for (final Player remote : remotes.values()) {
      final Thread t =
          new Thread(
              () -> {
                try {
                  remote.airStageWaiting(names);
                } catch (final RuntimeException e) {
                  // only a progress note
                }
              },
              "air stage progress");
      t.setDaemon(true);
      t.start();
    }
  }

  /** The stage's moves are made and its choices go to the air battles. */
  private void reveal(final Stage stage) {
    final Map<GamePlayer, Choice> accepted;
    synchronized (lock) {
      accepted = new LinkedHashMap<>(choices(stage));
    }
    final String name = stage == Stage.BOMBERS ? "Bomber" : "Fighter";
    bridge.getHistoryWriter().startEvent(name + " moves and choices revealed");
    for (final Map.Entry<GamePlayer, Choice> e : accepted.entrySet()) {
      final GamePlayer p = e.getKey();
      final Choice c = e.getValue();
      AirAllocation.describe(c.moves())
          .forEach(
              line ->
                  bridge.getHistoryWriter().addChildToEvent(PlayerNames.display(p) + ": " + line));
      for (final TargetAssignment a : c.targets()) {
        bridge
            .getHistoryWriter()
            .addChildToEvent(
                PlayerNames.display(p)
                    + ": "
                    + a.count()
                    + " "
                    + AirAllocation.typeName(a.unitType())
                    + " in "
                    + AirAllocation.zoneName(a.zone())
                    + " → "
                    + a.target().describe());
      }
      if (!c.moves().isEmpty()) {
        bridge.addChange(AirAllocation.toChange(p, stage, c.moves()));
      }
    }
    final AirCombatDelegate combat = combatDelegate();
    if (combat != null) {
      if (stage == Stage.BOMBERS) {
        combat.setStageBombers(AirStage.targetsOf(accepted));
      } else {
        combat.setStageCover(AirStage.targetsOf(accepted));
      }
    }
    if (stage == Stage.FIGHTERS) {
      synchronized (lock) {
        bomberChoices.clear();
        fighterChoices.clear();
      }
    }
  }

  private AirCombatDelegate combatDelegate() {
    for (final var d : getData().getDelegates()) {
      if (d instanceof AirCombatDelegate combat) {
        return combat;
      }
    }
    return null;
  }

  /** For tests: the answers kept for the stage. */
  Map<GamePlayer, Choice> accepted(final Stage stage) {
    synchronized (lock) {
      return new LinkedHashMap<>(choices(stage));
    }
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    return false;
  }

  @Override
  public Serializable saveState() {
    final State state = new State();
    state.superState = super.saveState();
    synchronized (lock) {
      state.bombers = new LinkedHashMap<>(bomberChoices);
      state.fighters = new LinkedHashMap<>(fighterChoices);
    }
    return state;
  }

  @Override
  public void loadState(final Serializable state) {
    final State s = (State) state;
    super.loadState(s.superState);
    synchronized (lock) {
      bomberChoices = s.bombers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.bombers);
      fighterChoices = s.fighters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.fighters);
    }
  }

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return null;
  }

  private static final class State implements Serializable {
    @Serial private static final long serialVersionUID = 6015853316520941877L;

    private Serializable superState;
    private Map<GamePlayer, Choice> bombers;
    private Map<GamePlayer, Choice> fighters;
  }
}
