package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.NeutralLikePlayers;
import games.strategy.triplea.delegate.move.validation.MoveValidator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 38): aircraft live on the map. Every aircraft that is not airborne is
 * on a <b>base</b>: land its nation owns (or allied land where the relationship lets its aircraft
 * land), or one of its own carriers (fleet carriers only for jets). An aircraft that flies in the
 * air phase is <b>airborne</b> until the end of the round: it is kept in one hidden territory
 * (property {@value #AIRBORNE_TERRITORY}, default "{@value #DEFAULT_AIRBORNE}") that borders
 * nothing and is never drawn, with the territory it is over and the range it has left on the unit.
 *
 * <p>Distances are counted in steps (borders crossed, land or sea), as aircraft move in stock
 * TripleA: a step may pass over a territory only if aircraft may fly over it (not impassable, not a
 * neutral unless neutral flyover is on, not the land of an ally whose relationship forbids flying
 * over it). A flight is legal when the distance out plus the distance from the target to a landing
 * spot is within the aircraft's range; a landing spot is a land base, or a sea zone one of the
 * nation's own carriers could reach this round. Every flying aircraft must be sure of its own spot
 * ({@link #landingProblem}): land has room for all, a carrier only for its capacity, and a carrier
 * ends the round in one sea zone.
 */
@UtilityClass
public class AirBases {
  public static final String AIRBORNE_TERRITORY = "FS Airborne Territory";
  public static final String DEFAULT_AIRBORNE = "Air - Airborne";

  /**
   * How many ways of placing the carriers the landing check tries before trusting a simpler one.
   */
  private static final int SEARCH_LIMIT = 20_000;

  /** The hidden territory airborne aircraft are kept in, if the map has one. */
  public static Optional<Territory> airborne(final GameState data) {
    return Optional.ofNullable(
        data.getMap()
            .getTerritoryOrNull(data.getProperties().get(AIRBORNE_TERRITORY, DEFAULT_AIRBORNE)));
  }

  /** Whether the game puts aircraft on the map (request 38): it has the airborne territory. */
  public static boolean usesBases(final GameState data) {
    return airborne(data).isPresent();
  }

  public static boolean isAirborneTerritory(final Territory t) {
    return t.getName()
        .equals(t.getData().getProperties().get(AIRBORNE_TERRITORY, DEFAULT_AIRBORNE));
  }

  public static boolean isAircraft(final Unit unit) {
    return unit.getUnitAttachment().isAir();
  }

  public static boolean isAircraft(final UnitType type) {
    return type.getUnitAttachment().isAir();
  }

  /** Whether the unit is an aircraft in the air this round. */
  public static boolean isAirborne(final Unit unit) {
    return unit.getFlightOver() != null;
  }

  /** The nation's aircraft airborne over the territory. */
  public static List<Unit> airborneOver(final GamePlayer player, final Territory over) {
    return airborne(over.getData())
        .map(
            a ->
                a.getUnitCollection()
                    .getMatches(u -> u.isOwnedBy(player) && over.equals(u.getFlightOver())))
        .orElse(List.of());
  }

  /** The nation's aircraft on bases (not airborne), by base. */
  public static Map<Territory, List<Unit>> basedAircraft(final GamePlayer player) {
    final Map<Territory, List<Unit>> result = new LinkedHashMap<>();
    for (final Territory t : player.getData().getMap().getTerritories()) {
      if (isAirborneTerritory(t)) {
        continue;
      }
      final List<Unit> here =
          t.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isAircraft(u));
      if (!here.isEmpty()) {
        result.put(t, here);
      }
    }
    return result;
  }

  // ---- bases

  /** Own land, or allied land where the relationship lets the nation's aircraft land. */
  public static boolean isLandBase(final GamePlayer player, final Territory t) {
    return AirRange.isLandLandingSpot(player, t);
  }

  public static boolean isCarrier(final Unit unit) {
    return unit.getUnitAttachment().getCarrierCapacity() > 0;
  }

  /** Whether aircraft of this type may land on carriers at all. */
  public static boolean canUseCarriers(final UnitType type) {
    return type.getUnitAttachment().getCarrierCost() > 0;
  }

  /** Whether an aircraft of this type may land on this carrier (jets on fleet carriers only). */
  public static boolean fits(final UnitType plane, final Unit carrier) {
    return isCarrier(carrier)
        && canUseCarriers(plane)
        && (!AirUnitAttachment.get(plane).map(AirUnitAttachment::isFleetCarrierOnly).orElse(false)
            || AirUnitAttachment.isFleetCarrier(carrier));
  }

  /** The nation's own carriers on the map. */
  public static List<Unit> carriers(final GamePlayer player) {
    final List<Unit> result = new ArrayList<>();
    for (final Territory t : player.getData().getMap().getTerritories()) {
      if (t.isWater()) {
        result.addAll(t.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isCarrier(u)));
      }
    }
    return result;
  }

  /** Room left on the nation's carriers in the sea zone, for aircraft of this type. */
  public static int carrierRoom(
      final GamePlayer player, final Territory seaZone, final UnitType type) {
    if (!seaZone.isWater() || !canUseCarriers(type)) {
      return 0;
    }
    // jets need fleet carriers; other aircraft may take any room left
    int fleetRoom = 0;
    int otherRoom = 0;
    for (final Unit c :
        seaZone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isCarrier(u))) {
      if (AirUnitAttachment.isFleetCarrier(c)) {
        fleetRoom += c.getUnitAttachment().getCarrierCapacity();
      } else {
        otherRoom += c.getUnitAttachment().getCarrierCapacity();
      }
    }
    int jets = 0;
    int others = 0;
    for (final Unit u :
        seaZone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isAircraft(u))) {
      if (AirUnitAttachment.isFleetCarrierOnly(u)) {
        jets += u.getUnitAttachment().getCarrierCost();
      } else {
        others += u.getUnitAttachment().getCarrierCost();
      }
    }
    // others fill ordinary carriers first, then fleet carriers
    final int othersOnFleet = Math.max(0, others - otherRoom);
    final int freeFleet = fleetRoom - jets - othersOnFleet;
    final int freeOther = Math.max(0, otherRoom - others);
    final boolean jet =
        AirUnitAttachment.get(type).map(AirUnitAttachment::isFleetCarrierOnly).orElse(false);
    return Math.max(0, jet ? freeFleet : freeFleet + freeOther);
  }

  /**
   * Whether an aircraft of this type could land in the territory now: a land base or carrier room.
   */
  public static boolean canLandNow(
      final GamePlayer player, final Territory t, final UnitType type) {
    return t.isWater()
        ? carrierRoom(player, t, type) >= type.getUnitAttachment().getCarrierCost()
        : isLandBase(player, t);
  }

  // ---- the stock rules (request 38 sections 1 and 3)

  /** Whether the unit takes part in ordinary land and sea battles: aircraft never do. */
  public static Predicate<Unit> inOrdinaryBattles(final GameState data) {
    final boolean bases = usesBases(data);
    return u -> !bases || !isAircraft(u);
  }

  /**
   * Why these units can't be moved in an ordinary move, or empty: aircraft fly in the air phase.
   */
  public static Optional<String> ordinaryMoveProblem(
      final GameState data, final Collection<Unit> units) {
    if (!usesBases(data) || units.stream().noneMatch(AirBases::isAircraft)) {
      return Optional.empty();
    }
    return Optional.of(
        "Aircraft move only in the air stages and at Planes Go Home; carriers take the aircraft on"
            + " them with them.");
  }

  /**
   * The nation's aircraft in the sea zone that sail with these carriers: the carriers carry as many
   * as they hold, jets on fleet carriers first.
   */
  public static List<Unit> carriedWith(
      final GamePlayer player, final Territory zone, final Collection<Unit> moving) {
    final List<Unit> carriers =
        moving.stream().filter(u -> u.isOwnedBy(player) && isCarrier(u)).toList();
    if (!zone.isWater() || carriers.isEmpty() || !usesBases(zone.getData())) {
      return List.of();
    }
    int fleet = 0;
    int other = 0;
    for (final Unit c : carriers) {
      if (AirUnitAttachment.isFleetCarrier(c)) {
        fleet += c.getUnitAttachment().getCarrierCapacity();
      } else {
        other += c.getUnitAttachment().getCarrierCapacity();
      }
    }
    final List<Unit> planes =
        new ArrayList<>(
            zone.getUnitCollection()
                .getMatches(u -> u.isOwnedBy(player) && isAircraft(u) && !moving.contains(u)));
    planes.sort(
        java.util.Comparator.comparing((Unit u) -> !AirUnitAttachment.isFleetCarrierOnly(u)));
    final List<Unit> result = new ArrayList<>();
    for (final Unit u : planes) {
      final int cost = u.getUnitAttachment().getCarrierCost();
      if (cost <= 0) {
        continue;
      }
      if (AirUnitAttachment.isFleetCarrierOnly(u)) {
        if (fleet >= cost) {
          fleet -= cost;
          result.add(u);
        }
      } else if (other >= cost) {
        other -= cost;
        result.add(u);
      } else if (fleet >= cost) {
        fleet -= cost;
        result.add(u);
      }
    }
    return result;
  }

  /**
   * Request 38 section 6: why these new units can't all be placed in the sea zone, or empty: new
   * aircraft go only on the nation's own carriers there (old or new), within their room, counting
   * the aircraft already on them.
   */
  public static Optional<String> newAircraftProblem(
      final GamePlayer player, final Territory zone, final Collection<Unit> units) {
    final List<Unit> planes = units.stream().filter(AirBases::isAircraft).toList();
    if (planes.isEmpty()) {
      return Optional.empty();
    }
    int fleet = 0;
    int other = 0;
    final List<Unit> carriers =
        new ArrayList<>(units.stream().filter(AirBases::isCarrier).toList());
    carriers.addAll(zone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isCarrier(u)));
    for (final Unit c : carriers) {
      if (AirUnitAttachment.isFleetCarrier(c)) {
        fleet += c.getUnitAttachment().getCarrierCapacity();
      } else {
        other += c.getUnitAttachment().getCarrierCapacity();
      }
    }
    int jets = 0;
    int rest = 0;
    final List<Unit> all = new ArrayList<>(planes);
    all.addAll(zone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isAircraft(u)));
    for (final Unit u : all) {
      if (!canUseCarriers(u.getType())) {
        return Optional.of(
            AirStage.typeName(u.getType()) + " can't be placed at sea: no carrier takes it.");
      }
      if (AirUnitAttachment.isFleetCarrierOnly(u)) {
        jets += u.getUnitAttachment().getCarrierCost();
      } else {
        rest += u.getUnitAttachment().getCarrierCost();
      }
    }
    if (jets > fleet || jets + rest > fleet + other) {
      return Optional.of(
          "Not enough room on your own carriers in " + zone.getName() + " for the new aircraft.");
    }
    return Optional.empty();
  }

  /**
   * Request 38: the tooltip line for an aircraft on the map: "On its airfield in Warsaw: aircraft
   * never defend a territory in a ground battle.", "On its carrier in Sea Zone 12: sails with it.",
   * or for one in the air "In the air over Hamburg: lands at round end, 3 steps of range left.".
   */
  public static String note(final Unit unit) {
    if (!isAircraft(unit) || !usesBases(unit.getData())) {
      return "";
    }
    if (isAirborne(unit)) {
      return "In the air over "
          + unit.getFlightOver().getName()
          + (unit.getFlightRebase() ? " (rebase)" : "")
          + ": lands at round end, "
          + unit.getFlightRangeLeft()
          + steps(unit.getFlightRangeLeft())
          + " of range left.";
    }
    for (final Territory t : unit.getData().getMap().getTerritories()) {
      if (t.getUnitCollection().contains(unit)) {
        return t.isWater()
            ? "On its carrier in " + t.getName() + ": sails with it; never fights in sea battles."
            : "On its airfield in "
                + t.getName()
                + ": aircraft never defend a territory in a ground battle.";
      }
    }
    return "";
  }

  // ---- distances

  /**
   * Request 56: game property listing impassable territories aircraft may fly over (never end in),
   * separated by colons, e.g. "Pripet Marshes - Impassable".
   */
  public static final String IMPASSABLE_AIR_FLYOVER = "FS Impassable Air Flyover";

  /** The impassable territories named in {@value #IMPASSABLE_AIR_FLYOVER}. */
  public static Set<String> impassableFlyover(final GameState data) {
    final Object value = data.getProperties().get(IMPASSABLE_AIR_FLYOVER);
    if (value == null) {
      return Set.of();
    }
    final Set<String> result = new HashSet<>();
    for (final String name : value.toString().split(":")) {
      if (!name.isBlank()) {
        result.add(name.trim());
      }
    }
    return result;
  }

  /**
   * Whether the nation's aircraft may fly over the territory on the way somewhere else. An
   * impassable territory listed in {@value #IMPASSABLE_AIR_FLYOVER} counts as open sky (request
   * 56); it is never an end: no base, no target ({@link #isImpassableLand}).
   */
  public static Predicate<Territory> flyOver(final GamePlayer player) {
    final GameState data = player.getData();
    final boolean neutrals =
        Properties.getNeutralFlyoverAllowed(data.getProperties())
            && !Properties.getNeutralsImpassable(data.getProperties());
    final Predicate<Territory> stock = Matches.airCanFlyOver(player, neutrals);
    final Set<String> overImpassable = impassableFlyover(data);
    return t ->
        !isAirborneTerritory(t)
            && (stock.test(t)
                || (overImpassable.contains(t.getName())
                    && isImpassableLand(t)
                    && mayFlyOverOwner(player, t)));
  }

  /** Impassable land: no unit may end a move, land or fight there. */
  public static boolean isImpassableLand(final Territory t) {
    return Matches.territoryIsImpassable().test(t);
  }

  /** A listed impassable territory with an owner keeps the owner's "may fly over" rule. */
  private static boolean mayFlyOverOwner(final GamePlayer player, final Territory t) {
    return NeutralLikePlayers.ownsAsNeutral(t)
        || player
            .getData()
            .getRelationshipTracker()
            .canMoveAirUnitsOverOwnedLand(player, t.getOwner());
  }

  /**
   * Steps from the territory to every territory within {@code max} steps, for the nation's
   * aircraft: the start and the end may be anything, every territory between must be one it may fly
   * over.
   */
  public static Map<Territory, Integer> distances(
      final GamePlayer player, final Territory from, final int max) {
    final GameMap map = from.getData().getMap();
    final Predicate<Territory> over = flyOver(player);
    final Map<Territory, Integer> result = new LinkedHashMap<>();
    result.put(from, 0);
    final Deque<Territory> queue = new ArrayDeque<>();
    queue.add(from);
    while (!queue.isEmpty()) {
      final Territory t = queue.poll();
      final int d = result.get(t);
      if (d >= max || (!t.equals(from) && !over.test(t))) {
        continue;
      }
      for (final Territory n : map.getNeighbors(t)) {
        if (!result.containsKey(n) && !isAirborneTerritory(n)) {
          result.put(n, d + 1);
          queue.add(n);
        }
      }
    }
    return result;
  }

  /**
   * Request 42: the shortest way the nation's aircraft fly from one territory to another (both
   * included), by the rules of {@link #distances}, if within {@code max} steps; for drawing the
   * stock route arrow.
   */
  public static Optional<List<Territory>> path(
      final GamePlayer player, final Territory from, final Territory to, final int max) {
    final GameMap map = from.getData().getMap();
    final Predicate<Territory> over = flyOver(player);
    final Map<Territory, Territory> parent = new HashMap<>();
    final Map<Territory, Integer> steps = new HashMap<>();
    steps.put(from, 0);
    final Deque<Territory> queue = new ArrayDeque<>();
    queue.add(from);
    while (!queue.isEmpty() && !steps.containsKey(to)) {
      final Territory t = queue.poll();
      final int d = steps.get(t);
      if (d >= max || (!t.equals(from) && !over.test(t))) {
        continue;
      }
      for (final Territory n : map.getNeighbors(t)) {
        if (!steps.containsKey(n) && !isAirborneTerritory(n)) {
          steps.put(n, d + 1);
          parent.put(n, t);
          queue.add(n);
        }
      }
    }
    if (!steps.containsKey(to)) {
      return Optional.empty();
    }
    final List<Territory> result = new ArrayList<>();
    for (Territory t = to; t != null; t = parent.get(t)) {
      result.add(0, t);
    }
    return Optional.of(result);
  }

  /** Steps between two territories for the nation's aircraft, if within {@code max}. */
  public static OptionalInt distance(
      final GamePlayer player, final Territory from, final Territory to, final int max) {
    final Integer d = distances(player, from, max).get(to);
    return d == null ? OptionalInt.empty() : OptionalInt.of(d);
  }

  /**
   * The sea zones this carrier could end the round in: those its movement reaches through sea zones
   * its owner may enter (canals as for ships). Enemy ships are ignored: they may be gone by then.
   */
  public static Set<Territory> carrierReach(final Unit carrier, final Territory at) {
    final GamePlayer owner = carrier.getOwner();
    final int movement = carrier.getUnitAttachment().getMovement(owner);
    final GameMap map = at.getData().getMap();
    final Predicate<Territory> sea = Matches.seaCanMoveOver(owner);
    final MoveValidator validator = new MoveValidator(at.getData(), false);
    final Map<Territory, Integer> steps = new LinkedHashMap<>();
    steps.put(at, 0);
    final Deque<Territory> queue = new ArrayDeque<>();
    queue.add(at);
    while (!queue.isEmpty()) {
      final Territory t = queue.poll();
      final int d = steps.get(t);
      if (d >= movement) {
        continue;
      }
      for (final Territory n : map.getNeighbors(t)) {
        if (steps.containsKey(n) || !sea.test(n)) {
          continue;
        }
        if (validator.validateCanal(new Route(t, n), List.of(carrier), owner) != null) {
          continue;
        }
        steps.put(n, d + 1);
        queue.add(n);
      }
    }
    return steps.keySet();
  }

  /**
   * How far every territory is from the nation's landing spots, worked out once (one search from
   * all land bases, one per carrier from the zones it could reach), for listing targets quickly.
   */
  public static final class Reach {
    private final GamePlayer player;
    private final Map<Territory, Integer> land;
    private final Map<Unit, Map<Territory, Integer>> carriers = new LinkedHashMap<>();

    public Reach(final GamePlayer player) {
      this.player = player;
      final List<Territory> bases = new ArrayList<>();
      for (final Territory t : player.getData().getMap().getTerritories()) {
        if (isLandBase(player, t)) {
          bases.add(t);
        }
      }
      land = fromAll(player, bases, 40);
      carrierReaches(player).forEach((c, zones) -> carriers.put(c, fromAll(player, zones, 40)));
    }

    /** Steps from the territory to the nearest land base, or -1 if none is near. */
    public int landSteps(final Territory t) {
      return land.getOrDefault(t, -1);
    }

    /** Whether an aircraft of this type over the territory with this range left could land. */
    public boolean canLand(final UnitType type, final Territory t, final int left) {
      if (left < 0) {
        return false;
      }
      final int l = landSteps(t);
      if (l >= 0 && l <= left) {
        return true;
      }
      for (final Map.Entry<Unit, Map<Territory, Integer>> e : carriers.entrySet()) {
        final Integer d = e.getValue().get(t);
        if (d != null && d <= left && fits(type, e.getKey())) {
          return true;
        }
      }
      return false;
    }

    public GamePlayer player() {
      return player;
    }
  }

  /** Steps to every territory from the nearest of several starts, within {@code max}. */
  static Map<Territory, Integer> fromAll(
      final GamePlayer player, final Collection<Territory> starts, final int max) {
    final GameMap map = player.getData().getMap();
    final Predicate<Territory> over = flyOver(player);
    final Map<Territory, Integer> result = new HashMap<>();
    final Deque<Territory> queue = new ArrayDeque<>();
    for (final Territory s : starts) {
      result.put(s, 0);
      queue.add(s);
    }
    final Set<Territory> start = new HashSet<>(starts);
    while (!queue.isEmpty()) {
      final Territory t = queue.poll();
      final int d = result.get(t);
      if (d >= max || (!start.contains(t) && !over.test(t))) {
        continue;
      }
      for (final Territory n : map.getNeighbors(t)) {
        if (!result.containsKey(n) && !isAirborneTerritory(n)) {
          result.put(n, d + 1);
          queue.add(n);
        }
      }
    }
    return result;
  }

  // ---- flights

  /** An aircraft in the air (or about to be): over a territory, with range left to land. */
  public record Flight(Unit plane, Territory over, int rangeLeft) {}

  /** Where a flight could land: the nearest land base and the carriers that could take it. */
  public record Landing(
      @Nullable Territory land, int landSteps, Map<Unit, Set<Territory>> carrierZones) {
    public boolean possible() {
      return land != null || !carrierZones.isEmpty();
    }
  }

  /**
   * Where the flight could land, as the stages count it: own (or allowed) land now, and the sea
   * zones each own carrier could reach this round that are within the range left.
   */
  public static Landing landingOptions(
      final Flight flight, final Map<Unit, Set<Territory>> reaches) {
    final GamePlayer player = flight.plane().getOwner();
    final Map<Territory, Integer> around = distances(player, flight.over(), flight.rangeLeft());
    Territory land = null;
    int landSteps = Integer.MAX_VALUE;
    for (final Map.Entry<Territory, Integer> e : around.entrySet()) {
      if (e.getValue() < landSteps && isLandBase(player, e.getKey())) {
        land = e.getKey();
        landSteps = e.getValue();
      }
    }
    final Map<Unit, Set<Territory>> carriers = new LinkedHashMap<>();
    if (canUseCarriers(flight.plane().getType())) {
      for (final Map.Entry<Unit, Set<Territory>> e : reaches.entrySet()) {
        if (!fits(flight.plane().getType(), e.getKey())) {
          continue;
        }
        final Set<Territory> zones = new LinkedHashSet<>();
        for (final Territory z : e.getValue()) {
          if (around.containsKey(z)) {
            zones.add(z);
          }
        }
        if (!zones.isEmpty()) {
          carriers.put(e.getKey(), zones);
        }
      }
    }
    return new Landing(land, land == null ? -1 : landSteps, carriers);
  }

  /**
   * The range an aircraft flying from its base to the target has left there, or empty if the target
   * is beyond its range.
   */
  public static OptionalInt rangeLeftAt(
      final Unit plane, final Territory from, final Territory target) {
    final int range = AirRange.range(plane.getType(), plane.getOwner());
    final OptionalInt out = distance(plane.getOwner(), from, target, range);
    return out.isPresent() ? OptionalInt.of(range - out.getAsInt()) : OptionalInt.empty();
  }

  /**
   * Why the aircraft can't fly from its base to the target this round, on its own (the carrier room
   * shared with other flights is {@link #landingProblem}), or empty: "Hamburg is 5 steps from
   * Warsaw; fighters fly 3", "Nowhere to land within 2 steps of Hamburg (nearest: Kiel, 3 steps)".
   */
  public static Optional<String> flightProblem(
      final Unit plane, final Territory from, final Territory target) {
    final GamePlayer player = plane.getOwner();
    final OptionalInt left = rangeLeftAt(plane, from, target);
    if (left.isEmpty()) {
      return outOfRange(player, plane.getType(), from, target);
    }
    final Landing landing =
        landingOptions(new Flight(plane, target, left.getAsInt()), carrierReaches(player));
    if (landing.possible()) {
      return Optional.empty();
    }
    final StringBuilder text =
        new StringBuilder(
            "Nowhere to land within "
                + left.getAsInt()
                + steps(left.getAsInt())
                + " of "
                + target.getName());
    nearestLand(player, target, 20)
        .ifPresent(
            n ->
                text.append(" (nearest: ")
                    .append(n.getKey().getName())
                    .append(", ")
                    .append(n.getValue())
                    .append(steps(n.getValue()))
                    .append(")"));
    return Optional.of(text.append(".").toString());
  }

  /**
   * Why aircraft of the type can't fly from one territory to another within their full range, or
   * empty: "Hamburg is 5 steps from Warsaw; fighters fly 3".
   */
  public static Optional<String> outOfRange(
      final GamePlayer player, final UnitType type, final Territory from, final Territory to) {
    final int range = AirRange.range(type, player);
    if (distance(player, from, to, range).isPresent()) {
      return Optional.empty();
    }
    final OptionalInt far = distance(player, from, to, 60);
    return Optional.of(
        to.getName()
            + (far.isPresent()
                ? " is " + far.getAsInt() + steps(far.getAsInt()) + " from " + from.getName()
                : " can't be reached from " + from.getName())
            + "; "
            + AirRange.pluralName(type).toLowerCase(java.util.Locale.ROOT)
            + " fly "
            + range
            + ".");
  }

  /** The nearest land base to the territory within {@code max} steps, with its distance. */
  public static Optional<Map.Entry<Territory, Integer>> nearestLand(
      final GamePlayer player, final Territory from, final int max) {
    return distances(player, from, max).entrySet().stream()
        .filter(e -> isLandBase(player, e.getKey()))
        .min(Map.Entry.comparingByValue());
  }

  /**
   * What makes the flight legal, for the mouse-over (request 38 section 8): "lands at Smolensk, 2
   * left", "carrier in Sea Zone 12 can reach".
   */
  public static String landingNote(final Flight flight) {
    final Landing landing = landingOptions(flight, carrierReaches(flight.plane().getOwner()));
    if (landing.land() != null) {
      return "lands at "
          + landing.land().getName()
          + ", "
          + (flight.rangeLeft() - landing.landSteps())
          + " left";
    }
    if (!landing.carrierZones().isEmpty()) {
      final Map.Entry<Unit, Set<Territory>> first =
          landing.carrierZones().entrySet().iterator().next();
      final Territory zone = first.getValue().iterator().next();
      return (AirUnitAttachment.isFleetCarrier(first.getKey()) ? "fleet carrier" : "carrier")
          + " from "
          + locationOf(first.getKey()).getName()
          + " can reach "
          + zone.getName();
    }
    return "nowhere to land";
  }

  /**
   * Request 45: the own carrier that aircraft of this type rebasing to the sea zone fly to: one in
   * the zone (one with room first), else one that could reach it this round; empty if none.
   */
  public static Optional<Unit> rebaseCarrier(
      final GamePlayer player, final UnitType type, final Territory zone) {
    if (!zone.isWater() || !canUseCarriers(type)) {
      return Optional.empty();
    }
    final List<Unit> there =
        zone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && fits(type, u));
    if (!there.isEmpty()) {
      return Optional.of(there.get(0));
    }
    return carrierReaches(player).entrySet().stream()
        .filter(e -> fits(type, e.getKey()) && e.getValue().contains(zone))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  /** Every own carrier of the nation with the sea zones it could reach this round. */
  public static Map<Unit, Set<Territory>> carrierReaches(final GamePlayer player) {
    final Map<Unit, Set<Territory>> result = new LinkedHashMap<>();
    for (final Territory t : player.getData().getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit c :
          t.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isCarrier(u))) {
        result.put(c, carrierReach(c, t));
      }
    }
    return result;
  }

  /**
   * Why these flights of one nation cannot all be sure of a landing spot, or empty (James, 25
   * October): counting no losses, every flying aircraft needs its own spot. Land holds any number.
   * A carrier holds its capacity, less the aircraft that stay on it (they ride along wherever it
   * goes), and ends the round in one sea zone, so every aircraft counting on it must be able to
   * reach that zone.
   */
  public static Optional<String> landingProblem(
      final GamePlayer player, final Collection<Flight> flights) {
    if (flights.isEmpty()) {
      return Optional.empty();
    }
    final Map<Unit, Set<Territory>> reaches = carrierReaches(player);
    final Set<Unit> flying = new HashSet<>();
    flights.forEach(f -> flying.add(f.plane()));
    final List<Flight> needCarrier = new ArrayList<>();
    final Map<Flight, Map<Unit, Set<Territory>>> options = new LinkedHashMap<>();
    for (final Flight f : flights) {
      final Landing landing = landingOptions(f, reaches);
      if (!landing.possible()) {
        return Optional.of(
            AirStage.typeName(f.plane().getType())
                + " over "
                + f.over().getName()
                + ": nowhere to land within "
                + f.rangeLeft()
                + steps(f.rangeLeft())
                + ".");
      }
      if (landing.land() == null) {
        needCarrier.add(f);
        options.put(f, landing.carrierZones());
      }
    }
    if (needCarrier.isEmpty()) {
      return Optional.empty();
    }
    // aircraft staying on carriers, by sea zone: they ride on that zone's carriers
    final Map<Territory, List<Unit>> staying = new LinkedHashMap<>();
    for (final Unit c : reaches.keySet()) {
      final Territory zone = locationOf(c);
      staying.computeIfAbsent(
          zone,
          z ->
              z.getUnitCollection()
                  .getMatches(u -> u.isOwnedBy(player) && isAircraft(u) && !flying.contains(u)));
    }
    final List<Unit> carriers = new ArrayList<>(reaches.keySet());
    // the zones worth choosing for each carrier: those some flight could use, or none of them
    final Map<Unit, List<Territory>> choices = new LinkedHashMap<>();
    for (final Unit c : carriers) {
      final Set<Territory> useful = new LinkedHashSet<>();
      options.values().forEach(o -> useful.addAll(o.getOrDefault(c, Set.of())));
      final List<Territory> list = new ArrayList<>(useful);
      list.add(null);
      choices.put(c, list);
    }
    final Search search = new Search(carriers, choices, needCarrier, options, staying);
    // the relaxed check (each carrier everywhere it could be at once) is quick: if even that fails,
    // nothing will do
    final int need = search.demand();
    if (search.flow(null) < need) {
      return Optional.of(carrierText(needCarrier.size()));
    }
    final boolean found = search.run();
    return found || search.gaveUp ? Optional.empty() : Optional.of(carrierText(needCarrier.size()));
  }

  private static String carrierText(final int count) {
    return count
        + (count == 1 ? " aircraft needs" : " aircraft need")
        + " a carrier spot to land on, and your carriers can't be sure to hold "
        + (count == 1 ? "it" : "them all")
        + " (counting the aircraft already on them, and one sea zone per carrier at round end).";
  }

  /** The territory the unit is in. */
  public static Territory locationOf(final Unit unit) {
    for (final Territory t : unit.getData().getMap().getTerritories()) {
      if (t.getUnitCollection().contains(unit)) {
        return t;
      }
    }
    throw new IllegalStateException(unit + " is not on the map");
  }

  /** Tries every useful end zone for each carrier, checking the room with a small max flow. */
  private static final class Search {
    private final List<Unit> carriers;
    private final Map<Unit, List<Territory>> choices;
    private final List<Flight> flights;
    private final Map<Flight, Map<Unit, Set<Territory>>> options;
    private final Map<Territory, List<Unit>> staying;
    private final Map<Unit, Territory> chosen = new HashMap<>();
    private int tried = 0;
    private boolean gaveUp = false;

    Search(
        final List<Unit> carriers,
        final Map<Unit, List<Territory>> choices,
        final List<Flight> flights,
        final Map<Flight, Map<Unit, Set<Territory>>> options,
        final Map<Territory, List<Unit>> staying) {
      this.carriers = carriers;
      this.choices = choices;
      this.flights = flights;
      this.options = options;
      this.staying = staying;
    }

    int demand() {
      int total = 0;
      for (final Flight f : flights) {
        total += f.plane().getUnitAttachment().getCarrierCost();
      }
      for (final List<Unit> planes : staying.values()) {
        for (final Unit u : planes) {
          total += Math.max(0, u.getUnitAttachment().getCarrierCost());
        }
      }
      return total;
    }

    boolean run() {
      return place(0);
    }

    private boolean place(final int index) {
      if (gaveUp) {
        return false;
      }
      if (index == carriers.size()) {
        if (++tried > SEARCH_LIMIT) {
          gaveUp = true;
          return false;
        }
        return flow(chosen) >= demand();
      }
      final Unit c = carriers.get(index);
      for (final Territory z : choices.get(c)) {
        chosen.put(c, z);
        if (place(index + 1)) {
          return true;
        }
      }
      chosen.remove(c);
      return false;
    }

    /**
     * The most carrier room the aircraft can be given with the carriers in the chosen zones (all
     * useful zones at once when {@code zones} is null).
     */
    int flow(final @Nullable Map<Unit, Territory> zones) {
      // nodes: 0 source, 1 sink, then one per demand, then one per carrier
      final List<int[]> demands = new ArrayList<>(); // {amount}
      final List<Predicate<Unit>> canUse = new ArrayList<>();
      for (final Flight f : flights) {
        demands.add(new int[] {f.plane().getUnitAttachment().getCarrierCost()});
        final Map<Unit, Set<Territory>> o = options.get(f);
        canUse.add(
            c ->
                o.containsKey(c)
                    && (zones == null
                        ? true
                        : zones.get(c) != null && o.get(c).contains(zones.get(c))));
      }
      for (final Map.Entry<Territory, List<Unit>> e : staying.entrySet()) {
        for (final Unit u : e.getValue()) {
          final int cost = u.getUnitAttachment().getCarrierCost();
          if (cost <= 0) {
            continue;
          }
          demands.add(new int[] {cost});
          final Territory zone = e.getKey();
          canUse.add(c -> fits(u.getType(), c) && locationOf(c).equals(zone));
        }
      }
      final int n = 2 + demands.size() + carriers.size();
      final int[][] cap = new int[n][n];
      for (int i = 0; i < demands.size(); i++) {
        cap[0][2 + i] = demands.get(i)[0];
        for (int j = 0; j < carriers.size(); j++) {
          if (canUse.get(i).test(carriers.get(j))) {
            cap[2 + i][2 + demands.size() + j] = Integer.MAX_VALUE / 4;
          }
        }
      }
      for (int j = 0; j < carriers.size(); j++) {
        cap[2 + demands.size() + j][1] = carriers.get(j).getUnitAttachment().getCarrierCapacity();
      }
      return maxFlow(cap);
    }
  }

  /** Edmonds-Karp on a small dense graph; node 0 is the source, node 1 the sink. */
  static int maxFlow(final int[][] cap) {
    final int n = cap.length;
    int total = 0;
    while (true) {
      final int[] parent = new int[n];
      java.util.Arrays.fill(parent, -1);
      parent[0] = 0;
      final Deque<Integer> queue = new ArrayDeque<>();
      queue.add(0);
      while (!queue.isEmpty() && parent[1] < 0) {
        final int u = queue.poll();
        for (int v = 0; v < n; v++) {
          if (parent[v] < 0 && cap[u][v] > 0) {
            parent[v] = u;
            queue.add(v);
          }
        }
      }
      if (parent[1] < 0) {
        return total;
      }
      int push = Integer.MAX_VALUE;
      for (int v = 1; v != 0; v = parent[v]) {
        push = Math.min(push, cap[parent[v]][v]);
      }
      for (int v = 1; v != 0; v = parent[v]) {
        cap[parent[v]][v] -= push;
        cap[v][parent[v]] += push;
      }
      total += push;
    }
  }

  /** " step"/" steps". */
  static String steps(final int n) {
    return n == 1 ? " step" : " steps";
  }
}
