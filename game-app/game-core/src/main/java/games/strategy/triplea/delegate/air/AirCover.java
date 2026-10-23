package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 30): air battles are fought over single territories. After the bombers
 * have chosen their targets (blind), each nation places its fighter-types over territories in their
 * front (blind): over its own bombers' targets as escorts, or over the enemy's as defenders. Each
 * territory, sea zone or bombed factory then fights its own air battle.
 *
 * <p>A fighter placement is a {@link TargetAssignment} whose target has the kind {@link
 * TargetKind#COVER}. Caps are per side (allies share one) and per box and territory:
 *
 * <ul>
 *   <li>at most {@value #CAP_BOMBERS} (default 4) bombers and {@value #CAP_FIGHTERS} (default 4)
 *       fighter-types per side over a land territory, tactical and strategic targets there
 *       together; the same for each factory or SPC in a bombing zone;
 *   <li>fighters defending a territory whose factory or SPC is under strategic attack are not
 *       capped unless {@value #CAP_DEFEND_FACTORIES} is true;
 *   <li>sea zones are not capped unless {@value #CAP_SEA_ZONES} is true.
 * </ul>
 *
 * <p>A fighter may cover a territory within half its range of one of its landing spots, the rule
 * bombers already follow. In a bombing zone the defenders' fighters cover their own side's land;
 * the attackers may bring escorts only of the types in {@value #ESCORT_UNITS} (default
 * advancedFighter), with the tech in {@value #ESCORT_TECH} (default P51Mustang), and only over a
 * factory their side bombs.
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

  private static boolean isBombZone(final Territory zone) {
    return AirZoneAttachment.get(zone).map(a -> a.getZoneType() == ZoneType.BOMB).orElse(false);
  }

  /** Whether the nation attacks in this bombing zone (it is at war with the bombed nation). */
  public static boolean attacksIn(final GamePlayer player, final Territory zone) {
    return AirZoneAttachment.get(zone).orElseThrow().isAttacker(player);
  }

  /**
   * Whether the nation may send this fighter type into bombing zones as escorts: the type is listed
   * in {@value #ESCORT_UNITS} and the nation has the tech named in {@value #ESCORT_TECH}.
   */
  public static boolean mayEscortInBombZone(final GamePlayer player, final UnitType type) {
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

  /**
   * The territories in the box this nation's fighters of this type may cover.
   *
   * @param bombers every nation's bomber targets this round, as revealed
   */
  public static List<Territory> places(
      final GamePlayer player,
      final Territory zone,
      final UnitType type,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    final AirZoneAttachment attachment = AirZoneAttachment.get(zone).orElseThrow();
    if (attachment.getZoneType() == ZoneType.RESERVE
        || !AirUnitAttachment.get(type).map(AirUnitAttachment::isFighter).orElse(false)) {
      return List.of();
    }
    final List<Territory> result = new ArrayList<>();
    final int range = AirRange.range(type, player);
    if (attachment.getZoneType() == ZoneType.BOMB) {
      final boolean attacking = attacksIn(player, zone);
      if (attacking && !mayEscortInBombZone(player, type)) {
        return List.of();
      }
      for (final Territory t : attachment.getCoveredTerritories()) {
        if (t.isWater() || !AirRange.canReach(player, zone, type, range, t)) {
          continue;
        }
        if (attacking ? sideBombs(player, zone, t, bombers) : sameSide(player, t.getOwner())) {
          result.add(t);
        }
      }
      return result;
    }
    for (final Territory t : attachment.getCoveredTerritories()) {
      if (AirRange.canReach(player, zone, type, range, t)) {
        result.add(t);
      }
    }
    return result;
  }

  /** Whether the nation's side bombs a factory in this territory from the box. */
  private static boolean sideBombs(
      final GamePlayer player,
      final Territory zone,
      final Territory t,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    return bombers.entrySet().stream()
        .filter(e -> sameSide(player, e.getKey()))
        .flatMap(e -> e.getValue().stream())
        .anyMatch(
            a ->
                a.zone().equals(zone)
                    && a.target().territory().equals(t)
                    && a.target().kind() == TargetKind.STRATEGIC);
  }

  /** Whether an enemy of the nation bombs a factory in this territory from the box. */
  public static boolean underStrategicAttack(
      final GamePlayer player,
      final Territory zone,
      final Territory t,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    return bombers.entrySet().stream()
        .filter(e -> AirCombat.atWar(player, e.getKey()))
        .flatMap(e -> e.getValue().stream())
        .anyMatch(
            a ->
                a.zone().equals(zone)
                    && a.target().territory().equals(t)
                    && a.target().kind() == TargetKind.STRATEGIC);
  }

  /** The most bombers one side may send against this territory from one box. */
  public static int bomberCap(final GameData data, final Territory t) {
    if (t.isWater() && !data.getProperties().get(CAP_SEA_ZONES, false)) {
      return NO_CAP;
    }
    return Math.max(0, data.getProperties().get(CAP_BOMBERS, 4));
  }

  /** The most fighter-types the nation's side may place over this territory in this box. */
  public static int fighterCap(
      final GamePlayer player,
      final Territory zone,
      final Territory t,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    final GameState data = player.getData();
    if (t.isWater() && !data.getProperties().get(CAP_SEA_ZONES, false)) {
      return NO_CAP;
    }
    if (!data.getProperties().get(CAP_DEFEND_FACTORIES, false)
        && underStrategicAttack(player, zone, t, bombers)) {
      return NO_CAP;
    }
    return Math.max(0, data.getProperties().get(CAP_FIGHTERS, 4));
  }

  /**
   * How many aircraft the nation's side has over the territory from the box: bombers (all their
   * targets there) or fighters (cover).
   *
   * @param assignments every nation's assignments the count may use (the nation's own and its
   *     allies'; enemies' are ignored)
   */
  public static int sideCount(
      final GamePlayer player,
      final Territory zone,
      final Territory t,
      final boolean fighters,
      final Map<GamePlayer, List<TargetAssignment>> assignments) {
    int count = 0;
    for (final Map.Entry<GamePlayer, List<TargetAssignment>> e : assignments.entrySet()) {
      if (!sameSide(player, e.getKey())) {
        continue;
      }
      for (final TargetAssignment a : e.getValue()) {
        if (a.zone().equals(zone)
            && a.target().territory().equals(t)
            && isCover(a) == fighters) {
          count += a.count();
        }
      }
    }
    return count;
  }

  /** The cap that applies to these aircraft over the territory (fighters or bombers). */
  public static int cap(
      final GamePlayer player,
      final Territory zone,
      final Territory t,
      final boolean fighters,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    return fighters
        ? fighterCap(player, zone, t, bombers)
        : bomberCap((GameData) player.getData(), t);
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
      final Map<GamePlayer, List<TargetAssignment>> others,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    final Map<GamePlayer, List<TargetAssignment>> all = new java.util.LinkedHashMap<>(others);
    all.put(player, List.copyOf(own));
    for (final TargetAssignment a : own) {
      final boolean fighters = isCover(a);
      final Territory t = a.target().territory();
      final int cap = cap(player, a.zone(), t, fighters, bombers);
      final int count = sideCount(player, a.zone(), t, fighters, all);
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
