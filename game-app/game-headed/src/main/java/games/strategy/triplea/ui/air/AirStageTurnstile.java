package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GamePlayer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Flames and Steel (request 35 part 3): in a simultaneous air stage the host asks every nation at
 * once. A computer playing several nations gets all its questions together and shows them one at a
 * time in turn order: a nation's screen opens when every earlier nation this computer plays has
 * finished, and never while another screen is open.
 */
public final class AirStageTurnstile {
  private final Set<GamePlayer> finished = new HashSet<>();
  private @Nullable String stage;
  private @Nullable GamePlayer active;

  /**
   * Waits for the nation's turn in this stage.
   *
   * @param stageKey identifies the stage (e.g. "BOMBERS round 3"); a new key starts afresh
   * @param order the nations asked in the stage, in turn order
   * @param local whether this computer plays a nation
   */
  public synchronized void await(
      final String stageKey,
      final List<GamePlayer> order,
      final GamePlayer nation,
      final Predicate<GamePlayer> local)
      throws InterruptedException {
    if (!stageKey.equals(stage)) {
      stage = stageKey;
      finished.clear();
      active = null;
    }
    while (active != null || earlierWaiting(order, nation, local)) {
      wait();
    }
    active = nation;
  }

  private boolean earlierWaiting(
      final List<GamePlayer> order, final GamePlayer nation, final Predicate<GamePlayer> local) {
    final int index = order.indexOf(nation);
    for (int i = 0; i < Math.max(0, index); i++) {
      final GamePlayer earlier = order.get(i);
      if (local.test(earlier) && !finished.contains(earlier)) {
        return true;
      }
    }
    return false;
  }

  /** The nation's screen closed (Done). */
  public synchronized void finish(final GamePlayer nation) {
    finished.add(nation);
    if (nation.equals(active)) {
      active = null;
    }
    notifyAll();
  }

  /** Whether a nation's screen is open on this computer. */
  public synchronized boolean isBusy() {
    return active != null;
  }

  /** The nations this computer plays in the stage that have not finished. */
  public synchronized List<GamePlayer> localLeft(
      final List<GamePlayer> order, final Predicate<GamePlayer> local) {
    return order.stream().filter(local).filter(p -> !finished.contains(p)).toList();
  }
}
