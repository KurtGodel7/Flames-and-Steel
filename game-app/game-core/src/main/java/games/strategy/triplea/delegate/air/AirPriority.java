package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 38 section 12): some allies never share an air battle, and one of them
 * always has priority. Game property {@value #PROPERTY}, e.g. {@code
 * Russians|British:AmericansPacific:Americans:Chinese}: once the first nation sends aircraft to a
 * territory or sea zone this round (a bomber target, or fighters escorting or defending there), the
 * listed nations may not send any there. Never the other way round, with one exception (James, 25
 * October): its fighters may not join a battle the listed nations' bombers were already sent to in the
 * bomber stage. Several rules are separated by ";".
 */
@UtilityClass
public class AirPriority {
  public static final String PROPERTY = "FS Air Battle Priority";

  /** One rule: the nation with priority, and the nations it locks out. */
  public record Rule(GamePlayer first, List<GamePlayer> lockedOut) {}

  public static List<Rule> rules(final GameState data) {
    final String text = data.getProperties().get(PROPERTY, "");
    final List<Rule> result = new ArrayList<>();
    for (final String part : text.split(";")) {
      final String[] sides = part.trim().split("\\|");
      if (sides.length != 2) {
        continue;
      }
      final GamePlayer first = data.getPlayerList().getPlayerId(sides[0].trim());
      if (first == null) {
        continue;
      }
      final List<GamePlayer> others = new ArrayList<>();
      for (final String name : sides[1].split(":")) {
        final GamePlayer p = data.getPlayerList().getPlayerId(name.trim());
        if (p != null) {
          others.add(p);
        }
      }
      result.add(new Rule(first, others));
    }
    return result;
  }

  /** The nations whose aircraft lock this nation out of a battle they are in. */
  public static List<GamePlayer> lockersOf(final GamePlayer player) {
    final List<GamePlayer> result = new ArrayList<>();
    for (final Rule rule : rules(player.getData())) {
      if (rule.lockedOut().contains(player) && !result.contains(rule.first())) {
        result.add(rule.first());
      }
    }
    return result;
  }

  /** Whether aircraft of the second nation lock the first out of a battle. */
  public static boolean locks(final GamePlayer locker, final GamePlayer player) {
    return lockersOf(player).contains(locker);
  }

  /**
   * The nation that locks this nation out of the territory, if any: one with priority over it that
   * sends aircraft there this round (in these choices, made or still being made, or already in the
   * air over it).
   */
  public static Optional<GamePlayer> lockedBy(
      final GamePlayer player,
      final Territory t,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> choices) {
    for (final GamePlayer locker : lockersOf(player)) {
      final Collection<TargetAssignment> theirs = choices.get(locker);
      if (theirs != null && theirs.stream().anyMatch(a -> a.target().territory().equals(t))) {
        return Optional.of(locker);
      }
      if (!AirBases.airborneOver(locker, t).isEmpty()) {
        return Optional.of(locker);
      }
    }
    return Optional.empty();
  }

  /**
   * James, 25 October: priority does not reach back into the bomber stage. A nation's fighters may
   * not go where a nation it locks out already sent bombers (revealed before the fighters choose):
   * the bombers' battle stands. Returns that nation, if any.
   */
  public static Optional<GamePlayer> bombersAlreadyThere(
      final GamePlayer player,
      final Territory t,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    for (final Rule rule : rules(player.getData())) {
      if (!rule.first().equals(player)) {
        continue;
      }
      for (final GamePlayer other : rule.lockedOut()) {
        final Collection<TargetAssignment> theirs = bombers.get(other);
        if (theirs != null && theirs.stream().anyMatch(a -> a.target().territory().equals(t))) {
          return Optional.of(other);
        }
      }
    }
    return Optional.empty();
  }

  /** "Hamburg: British bombers were sent there in the bomber stage; ..." */
  public static String bombersThereText(final Territory t, final GamePlayer other) {
    return t.getName()
        + ": "
        + AirCombat.adjective(other)
        + " bombers were sent there in the bomber stage, so it is their battle this round.";
  }

  /** "Hamburg is a Soviet air battle this round." */
  public static String lockedText(final Territory t, final GamePlayer locker) {
    return t.getName() + " is a " + AirCombat.adjective(locker) + " air battle this round.";
  }
}
