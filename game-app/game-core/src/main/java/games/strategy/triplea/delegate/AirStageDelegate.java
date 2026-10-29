package games.strategy.triplea.delegate;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.player.Player;
import games.strategy.triplea.delegate.air.AirBases;
import games.strategy.triplea.delegate.air.AirBases.Flight;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.delegate.air.AirHome;
import games.strategy.triplea.delegate.air.AirPriority;
import games.strategy.triplea.delegate.air.AirStage;
import games.strategy.triplea.delegate.air.AirStage.Choice;
import games.strategy.triplea.delegate.air.AirStage.Landing;
import games.strategy.triplea.delegate.air.AirStage.Stage;
import games.strategy.triplea.delegate.remote.IAirStageDelegate;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.PlayerNames;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Flames and Steel (requests 35 and 38): the air phase in simultaneous stages, and planes go home
 * at the end of the round. Steps (none belongs to a nation):
 *
 * <ul>
 *   <li>"airBomberStage": every nation with bombers that can fly is asked at once where they fly
 *       from their bases; nobody waits for anyone until all are done.
 *   <li>"airRevealBombers": the bombers take off (they are airborne over their targets) and every
 *       choice is shown.
 *   <li>"airFighterStage": every nation with fighter-types that can fly is asked at once where they
 *       escort or defend, seeing every bomber choice.
 *   <li>"airRevealFighters": the fighters take off; the targets and placements go to the air
 *       battles ("airStrikes", {@link AirCombatDelegate}).
 *   <li>"airPlanesHome": at the end of the round every nation at once lands its airborne aircraft
 *       and may rebase the others ({@link AirHome}); nothing is hidden.
 * </ul>
 *
 * <p>Each answer is checked as it arrives (range, landing spots for every flying aircraft, caps with
 * the allies' choices, priority); a refused one goes back to that nation with the reason. While a
 * nation's screen is open its choices so far are sent here ({@link #draft}) and passed to its
 * allies only. Request 38 section 12: when a nation with priority ({@link AirPriority}) chooses a
 * territory an ally it locks out has already finished with, that ally's aircraft there are handed
 * back and it is asked again, even after Done; the stage waits for that. In the fighter stage it
 * may not join a battle that ally's bombers were already sent to. Answers are kept here,
 * hidden from enemies until the reveal, and saved with the game.
 */
@Slf4j
public class AirStageDelegate extends BaseTripleADelegate implements IAirStageDelegate {
  private static final int ATTEMPTS = 10;

  private Map<GamePlayer, Choice> bomberChoices = new LinkedHashMap<>();
  private Map<GamePlayer, Choice> fighterChoices = new LinkedHashMap<>();
  private Map<GamePlayer, Choice> homeChoices = new LinkedHashMap<>();
  // the open stage's choices still being made, and who is still choosing
  private final transient Map<GamePlayer, List<TargetAssignment>> drafts = new LinkedHashMap<>();
  private final transient Set<GamePlayer> choosing = new HashSet<>();
  private transient @Nullable Stage open;
  private transient int asking = 0;
  private transient Map<GamePlayer, Player> remotes = new LinkedHashMap<>();
  private final transient Object lock = new Object();

  @Override
  public void start() {
    super.start();
    switch (stepName()) {
      case "airBomberStage" -> runStage(Stage.BOMBERS);
      case "airFighterStage" -> runStage(Stage.FIGHTERS);
      case "airRevealBombers" -> reveal(Stage.BOMBERS);
      case "airRevealFighters" -> reveal(Stage.FIGHTERS);
      case "airPlanesHome" -> {
        runStage(Stage.HOME);
        landAll();
      }
      default -> {
        // not an air stage step
      }
    }
  }

  private String stepName() {
    return getData().getSequence().getStep().getName();
  }

  /** Whether the game's sequence plays the air phase in stages (request 35). */
  public static boolean usesStages(final GameState data) {
    for (final GameStep step : data.getSequence().getSteps()) {
      if (step.getName().equals("airBomberStage") || step.getName().equals("airFighterStage")) {
        return true;
      }
    }
    return false;
  }

  /** The nations in turn order (the order their own steps come in), each once. */
  private List<GamePlayer> turnOrder() {
    return AirPriority.turnOrder(getData());
  }

  private Map<GamePlayer, Choice> choices(final Stage stage) {
    return switch (stage) {
      case BOMBERS -> bomberChoices;
      case FIGHTERS -> fighterChoices;
      case HOME -> homeChoices;
    };
  }

  /** The bomber targets revealed this round (for the fighter stage), by nation. */
  private Map<GamePlayer, List<TargetAssignment>> revealedBombers(final Stage stage) {
    return stage == Stage.FIGHTERS ? AirStage.targetsOf(bomberChoices) : Map.of();
  }

  private boolean hasWork(final GamePlayer p, final Stage stage) {
    if (stage == Stage.HOME) {
      return !AirHome.airborneGroups(p).isEmpty() || !AirHome.basedGroups(p).isEmpty();
    }
    return AirStage.hasWork(p, stage, revealedBombers(stage), getData().getMap());
  }

  /** Asks every nation not done yet at once, and waits until all are done (re-asks included). */
  private void runStage(final Stage stage) {
    final Map<GamePlayer, Choice> accepted = choices(stage);
    final List<GamePlayer> order = new ArrayList<>();
    for (final GamePlayer p : turnOrder()) {
      final boolean done;
      synchronized (lock) {
        done = accepted.containsKey(p);
      }
      if (!done && !Peacetime.isAtPeace(p) && hasWork(p, stage)) {
        order.add(p);
      }
    }
    if (order.isEmpty()) {
      return;
    }
    final String name =
        switch (stage) {
          case BOMBERS -> "Bombers";
          case FIGHTERS -> "Fighters";
          case HOME -> "Planes go home";
        };
    bridge
        .getHistoryWriter()
        .startEvent(
            name
                + ": "
                + String.join(", ", order.stream().map(PlayerNames::display).toList())
                + " choose at the same time");
    // proxies taken here, in the delegate's own thread
    final Map<GamePlayer, Player> found = new LinkedHashMap<>();
    for (final GamePlayer p : getData().getPlayerList().getPlayers()) {
      final Player remote = bridge.getRemotePlayer(p);
      if (remote != null) {
        found.put(p, remote);
      }
    }
    synchronized (lock) {
      remotes = found;
      open = stage;
      drafts.clear();
      choosing.clear();
      choosing.addAll(order);
      for (final GamePlayer p : order) {
        startAsk(p, stage, order, Choice.none(), "");
      }
    }
    bridge.leaveDelegateExecution();
    try {
      synchronized (lock) {
        while (asking > 0) {
          try {
            lock.wait();
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    } finally {
      bridge.enterDelegateExecution();
    }
    synchronized (lock) {
      for (final GamePlayer p : order) {
        accepted.putIfAbsent(p, Choice.none());
      }
      open = null;
      drafts.clear();
      choosing.clear();
    }
  }

  /** Starts asking one nation (call holding the lock). */
  private void startAsk(
      final GamePlayer p,
      final Stage stage,
      final List<GamePlayer> order,
      final Choice start,
      final String problem) {
    asking++;
    final Player remote = remotes.get(p);
    final Thread t =
        new Thread(
            () -> {
              try {
                ask(p, remote, stage, order, start, problem);
              } finally {
                synchronized (lock) {
                  asking--;
                  lock.notifyAll();
                }
              }
            },
            "air stage " + p.getName());
    t.start();
  }

  /** One nation's question, asked again with the reason until its answer is allowed. */
  private void ask(
      final GamePlayer p,
      final @Nullable Player remote,
      final Stage stage,
      final List<GamePlayer> order,
      final Choice first,
      final String firstProblem) {
    final Map<GamePlayer, List<TargetAssignment>> bombers = revealedBombers(stage);
    Choice start = first;
    String problem = firstProblem;
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      Choice choice;
      try {
        choice =
            remote == null
                ? Choice.none()
                : remote.selectAirStage(stage, order, start, bombers, problem);
      } catch (final RuntimeException e) {
        log.warn("Air stage: " + p.getName() + " could not be asked", e);
        choice = Choice.none();
      }
      if (choice == null) {
        choice = Choice.none();
      }
      final Optional<String> error;
      synchronized (lock) {
        error = check(p, stage, choice, bombers);
        if (error.isEmpty()) {
          choices(stage).put(p, choice);
          choosing.remove(p);
          drafts.remove(p);
          if (stage != Stage.HOME) {
            reopenLockedOut(p, stage, order, choice);
          }
        }
      }
      if (error.isEmpty()) {
        tellAllies();
        tellWaiting();
        return;
      }
      problem = error.get();
      start = choice;
    }
    synchronized (lock) {
      choices(stage).put(p, Choice.none());
      choosing.remove(p);
      drafts.remove(p);
    }
    tellAllies();
    tellWaiting();
  }

  /** Why the answer is refused, or empty (call holding the lock). */
  private Optional<String> check(
      final GamePlayer p,
      final Stage stage,
      final Choice choice,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    if (stage == Stage.HOME) {
      return AirHome.problem(p, choice.landings());
    }
    return AirStage.problem(p, stage, choice, othersOf(p, stage), bombers);
  }

  /** Every other nation's choices in the stage: finished ones, or the ones still being made. */
  private Map<GamePlayer, List<TargetAssignment>> othersOf(final GamePlayer p, final Stage stage) {
    final Map<GamePlayer, List<TargetAssignment>> result = new LinkedHashMap<>();
    choices(stage).forEach((q, c) -> result.put(q, c.targets()));
    drafts.forEach((q, list) -> {
      if (choosing.contains(q)) {
        result.put(q, list);
      }
    });
    result.remove(p);
    return result;
  }

  /**
   * Request 38 section 12: the nations this answer locks out of a territory lose their finished
   * choices there and are asked again with the reason (call holding the lock).
   */
  private void reopenLockedOut(
      final GamePlayer p, final Stage stage, final List<GamePlayer> order, final Choice choice) {
    final Set<Territory> mine = new HashSet<>();
    final Set<AirCombat.Target> raids = new HashSet<>();
    choice.targets().forEach(a -> mine.add(a.target().territory()));
    choice.targets().stream()
        .filter(a -> a.target().kind() == AirCombat.TargetKind.STRATEGIC)
        .forEach(a -> raids.add(a.target()));
    for (final Map.Entry<GamePlayer, Choice> e : new ArrayList<>(choices(stage).entrySet())) {
      final GamePlayer q = e.getKey();
      final boolean locked = AirPriority.locks(p, q);
      // request 43 section 1: a raid keeps later allies' bombers off that factory
      final boolean raidLocked = AirPriority.raidLocks(p, q);
      if (q.equals(p) || (!locked && !raidLocked)) {
        continue;
      }
      final List<TargetAssignment> kept = new ArrayList<>();
      final Set<String> why = new java.util.LinkedHashSet<>();
      for (final TargetAssignment a : e.getValue().targets()) {
        final Territory t = a.target().territory();
        if (locked && mine.contains(t)) {
          why.add(AirPriority.lockedText(t, p));
        } else if (raidLocked && raids.contains(a.target())) {
          why.add(AirPriority.raidText(t, p));
        } else {
          kept.add(a);
        }
      }
      if (why.isEmpty()) {
        continue;
      }
      choices(stage).remove(q);
      choosing.add(q);
      startAsk(
          q,
          stage,
          order,
          Choice.of(kept),
          String.join(" ", why) + " Those aircraft are back on their bases: choose again.");
    }
  }

  @Override
  public void draft(final GamePlayer player, final List<TargetAssignment> targets) {
    synchronized (lock) {
      if (open == null || open == Stage.HOME || !choosing.contains(player)) {
        return;
      }
      drafts.put(player, List.copyOf(targets));
    }
    tellAllies();
  }

  @Override
  public Map<GamePlayer, List<TargetAssignment>> getAlliedChoices(final GamePlayer player) {
    synchronized (lock) {
      if (open == null || open == Stage.HOME) {
        return Map.of();
      }
      final Map<GamePlayer, List<TargetAssignment>> result = new LinkedHashMap<>();
      othersOf(player, open)
          .forEach(
              (q, list) -> {
                if (AirCover.sameSide(player, q)) {
                  result.put(q, List.copyOf(list));
                }
              });
      return result;
    }
  }

  /** Every nation hears its allies' choices so far (never an enemy's). */
  private void tellAllies() {
    final Map<GamePlayer, Map<GamePlayer, List<TargetAssignment>>> toSend = new LinkedHashMap<>();
    final Map<GamePlayer, Player> targets;
    synchronized (lock) {
      if (open == null || open == Stage.HOME) {
        return;
      }
      targets = new LinkedHashMap<>(remotes);
      for (final GamePlayer p : targets.keySet()) {
        toSend.put(p, getAlliedChoices(p));
      }
    }
    for (final Map.Entry<GamePlayer, Player> e : targets.entrySet()) {
      final GamePlayer p = e.getKey();
      final Player remote = e.getValue();
      final Thread t =
          new Thread(
              () -> {
                try {
                  remote.airStageAllied(p, toSend.get(p));
                } catch (final RuntimeException ex) {
                  // only a progress note
                }
              },
              "air stage allies");
      t.setDaemon(true);
      t.start();
    }
  }

  /** Every player hears who is still choosing (names only). */
  private void tellWaiting() {
    final List<String> names;
    final Collection<Player> targets;
    synchronized (lock) {
      names = choosing.stream().map(PlayerNames::display).toList();
      targets = new ArrayList<>(new java.util.LinkedHashSet<>(remotes.values()));
    }
    for (final Player remote : targets) {
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

  /** The stage's aircraft take off and its choices go to the air battles. */
  private void reveal(final Stage stage) {
    final Map<GamePlayer, Choice> accepted;
    synchronized (lock) {
      accepted = new LinkedHashMap<>(choices(stage));
    }
    final Territory airborne = AirBases.airborne(getData()).orElse(null);
    final String name = stage == Stage.BOMBERS ? "Bombers" : "Fighters";
    bridge.getHistoryWriter().startEvent(name + " take off; every choice revealed");
    final Map<GamePlayer, List<TargetAssignment>> toBattle = new LinkedHashMap<>();
    for (final Map.Entry<GamePlayer, Choice> e : accepted.entrySet()) {
      final GamePlayer p = e.getKey();
      final List<TargetAssignment> targets = e.getValue().targets();
      if (targets.isEmpty() || airborne == null) {
        continue;
      }
      final List<Flight> flights = AirStage.flights(p, stage, targets);
      final CompositeChange change = new CompositeChange();
      final Map<Territory, List<Unit>> byBase = new LinkedHashMap<>();
      int index = 0;
      for (final TargetAssignment a : targets) {
        final List<Unit> these = new ArrayList<>();
        for (int i = 0; i < a.count() && index < flights.size(); i++, index++) {
          final Flight f = flights.get(index);
          these.add(f.plane());
          change.add(
              ChangeFactory.unitPropertyChange(f.plane(), f.over(), Unit.PropertyName.FLIGHT_OVER));
          change.add(
              ChangeFactory.unitPropertyChange(
                  f.plane(), f.rangeLeft(), Unit.PropertyName.FLIGHT_RANGE_LEFT));
        }
        byBase.computeIfAbsent(a.zone(), k -> new ArrayList<>()).addAll(these);
        bridge
            .getHistoryWriter()
            .addChildToEvent(PlayerNames.display(p) + ": " + AirStage.describe(a), these);
        toBattle
            .computeIfAbsent(p, k -> new ArrayList<>())
            .add(new TargetAssignment(airborne, a.unitType(), these.size(), a.target()));
      }
      byBase.forEach(
          (base, units) -> {
            if (!units.isEmpty()) {
              change.add(ChangeFactory.moveUnits(base, airborne, units));
            }
          });
      bridge.addChange(change);
    }
    final AirCombatDelegate combat = combatDelegate();
    if (stage == Stage.BOMBERS) {
      if (combat != null) {
        combat.setStageBombers(toBattle);
      }
      return;
    }
    if (combat != null) {
      combat.setStageCover(toBattle);
    }
    synchronized (lock) {
      bomberChoices.clear();
      fighterChoices.clear();
    }
  }

  /** Planes go home: every nation's landings (with the defaults for aircraft left in the air). */
  private void landAll() {
    final Map<GamePlayer, Choice> accepted;
    synchronized (lock) {
      accepted = new LinkedHashMap<>(homeChoices);
    }
    if (AirBases.airborne(getData()).isEmpty()) {
      return;
    }
    bridge.getHistoryWriter().startEvent("Planes go home");
    for (final GamePlayer p : turnOrder()) {
      final List<Landing> chosen =
          accepted.getOrDefault(p, Choice.none()).landings();
      AirHome.apply(bridge, p, AirHome.withDefaults(p, chosen));
    }
    synchronized (lock) {
      homeChoices.clear();
    }
  }

  private @Nullable AirCombatDelegate combatDelegate() {
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
      state.home = new LinkedHashMap<>(homeChoices);
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
      homeChoices = s.home == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.home);
    }
  }

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IAirStageDelegate.class;
  }

  private static final class State implements Serializable {
    @Serial private static final long serialVersionUID = 6015853316520941878L;

    private Serializable superState;
    private Map<GamePlayer, Choice> bombers;
    private Map<GamePlayer, Choice> fighters;
    private Map<GamePlayer, Choice> home;
  }
}
