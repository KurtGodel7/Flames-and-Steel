package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (requests 30 and 38): air battles are fought over single territories. After the
 * bombers have chosen their targets (blind), each nation sends its fighter-types over territories
 * (blind): over its own side's targets as escorts, or over the enemy's as defenders. Each territory
 * or sea zone then fights one air battle.
 *
 * <p>A fighter placement is a {@link TargetAssignment} whose target has the kind {@link
 * TargetKind#COVER}. Caps are per side (allies share one) and per territory (request 38 section
 * 11):
 *
 * <ul>
 *   <li>at most {@value #CAP_BOMBERS} (default 4) bombers per side over a land territory in total,
 *       tactical strikes and strategic bombing together, and at most {@value #CAP_FIGHTERS}
 *       (default 4) fighter-types per side;
 *   <li>defenders are not capped while an enemy bomber's chosen mission is strategic bombing of
 *       that territory's factory or SPC (unless {@value #CAP_DEFEND_FACTORIES} is true);
 *   <li>sea zones are not capped unless {@value #CAP_SEA_ZONES} is true.
 * </ul>
 *
 * <p>Over a territory whose factory its own side bombs, a nation's fighters escort only if they
 * are of a type in {@value #ESCORT_UNITS} (default advancedFighter) and it has the tech in {@value
 * #ESCORT_TECH} (default P51Mustang).
 */
@UtilityClass
public class AirCover {
  public static final String CAP_BOMBERS = "FS Air Cap Bombers";
  public static final String CAP_FIGHTERS = "FS Air Cap Fighters";
  public static final String CAP_DEFEND_FACTORIES = "FS Air Cap Defend Factories";
  public static final String CAP_SEA_ZONES = "FS Air Cap Sea Zones";
  public static final String ESCORT_UNITS = "FS Bomb Zone Escort Units";
  public static final String ESCORT_TECH = "FS Bomb Zone Escort Tech";
  public static final int NO_CAP = Integer.MAX_VALUE;

  /** The cover "target" for a territory. */
  public static Target over(final Territory territory) {
    return new Target(territory, TargetKind.COVER);
  }

  public static boolean isCover(final TargetAssignment a) {
    return a.target().kind() == TargetKind.COVER;
  }

  /** Whether the two nations fight on the same side (themselves or allies). */
  public static boolean sameSide(final GamePlayer a, final GamePlayer b) {
    return a.equals(b) || a.getData().getRelationshipTracker().isAllied(a, b);
  }

  /**
   * Whether the nation's fighters of this type may escort strategic bombing: the type is listed in
   * {@value #ESCORT_UNITS} and the nation has the tech named in {@value #ESCORT_TECH}.
   */
  public static boolean mayEscortBombing(final GamePlayer player, final UnitType type) {
    final GameState data = player.getData();
    final String units = data.getProperties().get(ESCORT_UNITS, "advancedFighter");
    if (units.isBlank() || !List.of(units.split(":")).contains(type.getName())) {
      return false;
    }
    final String tech = data.getProperties().get(ESCORT_TECH, "P51Mustang");
    if (tech.isBlank()) {
      return true;
    }
    for (final TechAdvance advance :
        TechTracker.getCurrentTechAdvances(player, data.getTechnologyFrontier())) {
      if (tech.equals(advance.getName()) || tech.equals(advance.getProperty())) {
        return true;
      }
    }
    return false;
  }

  /** Whether the nation's side has a strategic bombing mission against the territory. */
  public static boolean sideBombs(
      final GamePlayer player,
      final Territory t,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    return bombers.entrySet().stream()
        .filter(e -> sameSide(player, e.getKey()))
        .flatMap(e -> e.getValue().stream())
        .anyMatch(
            a -> a.target().territory().equals(t) && a.target().kind() == TargetKind.STRATEGIC);
  }

  /** Whether an enemy of the nation has a strategic bombing mission against the territory. */
  public static boolean underStrategicAttack(
      final GamePlayer player,
      final Territory t,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    return bombers.entrySet().stream()
        .filter(e -> AirCombat.atWar(player, e.getKey()))
        .flatMap(e -> e.getValue().stream())
        .anyMatch(
            a -> a.target().territory().equals(t) && a.target().kind() == TargetKind.STRATEGIC);
  }

  /**
   * Why the nation's fighters of this type may not cover the territory, given the bombers' choices,
   * or empty. Only the type and the escort rule; range, caps and priority are checked elsewhere.
   */
  public static Optional<String> coverRule(
      final GamePlayer player,
      final UnitType type,
      final Territory t,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    if (!AirCombat.mayAttack(type, TargetKind.COVER)) {
      return Optional.of(AirStage.typeName(type) + " can't escort or defend.");
    }
    final Optional<GamePlayer> first = AirPriority.bombersAlreadyThere(player, t, bombers);
    if (first.isPresent()) {
      return Optional.of(AirPriority.bombersThereText(t, first.get()));
    }
    if (sideBombs(player, t, bombers) && !mayEscortBombing(player, type)) {
      return Optional.of(
          "Only fighters with the escort tech may escort the bombing of " + t.getName() + ".");
    }
    return Optional.empty();
  }

  /** The most bombers one side may send against this territory. */
  public static int bomberCap(final GameData data, final Territory t) {
    if (t.isWater() && !data.getProperties().get(CAP_SEA_ZONES, false)) {
      return NO_CAP;
    }
    return Math.max(0, data.getProperties().get(CAP_BOMBERS, 4));
  }

  /** The most fighter-types the nation's side may send over this territory. */
  public static int fighterCap(
      final GamePlayer player,
      final Territory t,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    final GameState data = player.getData();
    if (t.isWater() && !data.getProperties().get(CAP_SEA_ZONES, false)) {
      return NO_CAP;
    }
    if (!data.getProperties().get(CAP_DEFEND_FACTORIES, false)
        && underStrategicAttack(player, t, bombers)) {
      return NO_CAP;
    }
    return Math.max(0, data.getProperties().get(CAP_FIGHTERS, 4));
  }

  /**
   * How many aircraft the nation's side sends over the territory: bombers (all their targets
   * there) or fighters (cover).
   *
   * @param assignments every nation's assignments the count may use (the nation's own and its
   *     allies'; enemies' are ignored)
   */
  public static int sideCount(
      final GamePlayer player,
      final Territory t,
      final boolean fighters,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> assignments) {
    int count = 0;
    for (final Map.Entry<GamePlayer, ? extends Collection<TargetAssignment>> e :
        assignments.entrySet()) {
      if (!sameSide(player, e.getKey())) {
        continue;
      }
      for (final TargetAssignment a : e.getValue()) {
        // request 45: rebasing aircraft join no battle and count against no cap
        if (a.target().territory().equals(t) && isCover(a) == fighters && !AirStage.isRebase(a)) {
          count += a.count();
        }
      }
    }
    return count;
  }

  /** The cap that applies to these aircraft over the territory (fighters or bombers). */
  public static int cap(
      final GamePlayer player,
      final Territory t,
      final boolean fighters,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    return fighters ? fighterCap(player, t, bombers) : bomberCap((GameData) player.getData(), t);
  }

  /**
   * Why this nation's assignments break a cap, or empty.
   *
   * @param own the nation's assignments
   * @param others the other nations' assignments (allies count against the cap)
   * @param bombers every nation's bomber targets (for the factory exception)
   */
  public static Optional<String> capProblem(
      final GamePlayer player,
      final Collection<TargetAssignment> own,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> others,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    final Map<GamePlayer, Collection<TargetAssignment>> all = new LinkedHashMap<>(others);
    all.put(player, List.copyOf(own));
    for (final TargetAssignment a : own) {
      if (AirStage.isRebase(a)) {
        continue;
      }
      final boolean fighters = isCover(a);
      final Territory t = a.target().territory();
      final int cap = cap(player, t, fighters, bombers);
      final int count = sideCount(player, t, fighters, all);
      if (count > cap) {
        return Optional.of(
            t.getName()
                + ": at most "
                + cap
                + (fighters ? " fighters" : " bombers")
                + " per side ("
                + count
                + " with your allies').");
      }
    }
    return Optional.empty();
  }
}
