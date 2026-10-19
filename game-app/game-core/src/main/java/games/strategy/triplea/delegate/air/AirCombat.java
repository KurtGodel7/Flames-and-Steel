package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.random.IRandomStats.DiceType;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.attachments.ApcAttachment;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.casualty.CasualtySelector;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import games.strategy.triplea.formatter.MyFormatter;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/** Flames and Steel air combat rules shared by the air combat steps and the UI. */
@UtilityClass
public class AirCombat {

  /** What a group of bombers attacks. */
  public enum TargetKind {
    /** Land units in a territory (tactical). */
    LAND,
    /** Surface ships in a sea zone. */
    SURFACE,
    /** Submarines in a sea zone (torpedo and medium bombers only). */
    SUBS,
    /** A factory territory of the bombed nation (strategic bombing). */
    STRATEGIC
  }

  /** A target a nation's bombers in an air zone may attack. */
  public record Target(Territory territory, TargetKind kind) implements Serializable {
    @Serial private static final long serialVersionUID = -6260739381104472151L;

    public String describe() {
      return switch (kind) {
        case LAND -> territory.getName();
        case SURFACE -> territory.getName() + ": surface ships";
        case SUBS -> territory.getName() + ": submarines";
        case STRATEGIC -> territory.getName() + ": strategic bombing";
      };
    }
  }

  /** Send {@code count} bombers of one type in an air zone against a target. */
  public record TargetAssignment(Territory zone, UnitType unitType, int count, Target target)
      implements Serializable {
    @Serial private static final long serialVersionUID = 5017718430628117731L;
  }

  public static boolean isBomber(final Unit unit) {
    return Matches.unitIsAir().test(unit) && !AirUnitAttachment.isFighter(unit);
  }

  /** The player's bombers in a zone that may still strike (not in the reserve). */
  public static List<Unit> getBombers(final GamePlayer player, final Territory zone) {
    if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.RESERVE) {
      return List.of();
    }
    return zone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isBomber(u));
  }

  /** Targets the player's bombers in this zone may attack. */
  public static List<Target> getTargets(final GamePlayer player, final Territory zone) {
    final AirZoneAttachment attachment = AirZoneAttachment.get(zone).orElseThrow();
    final List<Target> targets = new ArrayList<>();
    switch (attachment.getZoneType()) {
      case RESERVE -> {
        // reserve aircraft do not fight
      }
      case BOMB -> {
        // enemy factories and APCs in the zone's home region
        for (final Territory t : attachment.getCoveredTerritories()) {
          if (isStrategicTarget(player, t)) {
            targets.add(new Target(t, TargetKind.STRATEGIC));
          }
        }
      }
      default -> {
        for (final Territory t : attachment.getCoveredTerritories()) {
          if (t.isWater()) {
            if (t.anyUnitsMatch(enemyShip(player).and(Matches.unitCanEvade().negate()))) {
              targets.add(new Target(t, TargetKind.SURFACE));
            }
            if (t.anyUnitsMatch(enemyShip(player).and(Matches.unitCanEvade()))) {
              targets.add(new Target(t, TargetKind.SUBS));
            }
          } else {
            if (!getLandTargetUnits(player, t).isEmpty()) {
              targets.add(new Target(t, TargetKind.LAND));
            }
            // factories outside every home region are bombed from their front
            if (isStrategicTarget(player, t)
                && !AirZoneAttachment.isInBombingZone(player.getData().getMap(), t)) {
              targets.add(new Target(t, TargetKind.STRATEGIC));
            }
          }
        }
      }
    }
    return targets;
  }

  /** An enemy-owned land territory with a factory or APC. */
  private static boolean isStrategicTarget(final GamePlayer player, final Territory t) {
    return !t.isWater()
        && !t.getOwner().isNull()
        && atWar(player, t.getOwner())
        && t.anyUnitsMatch(Matches.unitCanProduceUnits().or(u -> ApcAttachment.isApc(u.getType())));
  }

  /**
   * The targets this nation's aircraft of this type in the zone may legally attack. The allocation
   * grid's counts, the target picker and the "still without a target" warning all use this.
   */
  public static List<Target> getTargets(
      final GamePlayer player, final Territory zone, final UnitType type) {
    return getTargets(player, zone).stream()
        .filter(target -> mayAttack(player, zone, type, target))
        .toList();
  }

  /** How many targets in the zone this nation's aircraft of this type could attack. */
  public static int countTargets(
      final GamePlayer player, final Territory zone, final UnitType type) {
    return getTargets(player, zone, type).size();
  }

  /** {@link #countTargets} split by kind of target (kinds with none are left out). */
  public static Map<TargetKind, Integer> countTargetsByKind(
      final GamePlayer player, final Territory zone, final UnitType type) {
    final Map<TargetKind, Integer> result = new EnumMap<>(TargetKind.class);
    for (final Target target : getTargets(player, zone)) {
      if (mayAttack(player, zone, type, target)) {
        result.merge(target.kind(), 1, Integer::sum);
      }
    }
    return result;
  }

  /** Game property listing aircraft types that attack only land targets, e.g. "a:b:c". */
  public static final String LAND_TARGETS_ONLY = "FS Air Land Targets Only";

  /**
   * Whether this aircraft type may attack that kind of target. Types named in the game property
   * {@value #LAND_TARGETS_ONLY} attack only land units and factories, never ships or submarines.
   */
  public static boolean mayAttack(final UnitType type, final TargetKind kind) {
    final AirUnitAttachment air = AirUnitAttachment.get(type).orElse(null);
    if ((kind == TargetKind.SURFACE || kind == TargetKind.SUBS) && isLandTargetsOnly(type)) {
      return false;
    }
    return switch (kind) {
      case STRATEGIC -> air != null && air.getBombingDamage() > 0;
      case SUBS -> air != null && air.isCanAttackSubs();
      default -> true;
    };
  }

  /** Whether the type is listed in the game property {@value #LAND_TARGETS_ONLY}. */
  public static boolean isLandTargetsOnly(final UnitType type) {
    final String list = type.getData().getProperties().get(LAND_TARGETS_ONLY, "");
    return !list.isBlank() && List.of(list.split(":")).contains(type.getName());
  }

  /**
   * How many of {@link #countTargets} are land targets in neutral territory (garrisons with no
   * owner, which every nation is at war with).
   */
  public static int countNeutralTargets(
      final GamePlayer player, final Territory zone, final UnitType type) {
    return (int)
        getTargets(player, zone).stream()
            .filter(t -> t.kind() == TargetKind.LAND && t.territory().getOwner().isNull())
            .filter(t -> mayAttack(player, zone, type, t))
            .count();
  }

  /**
   * Whether this nation's aircraft of this type in the zone may attack the target: the type must
   * suit the target, and the target must be within half the aircraft's own range of a landing spot.
   */
  public static boolean mayAttack(
      final GamePlayer player, final Territory zone, final UnitType type, final Target target) {
    if (!mayAttack(type, target.kind())) {
      return false;
    }
    final int range = AirRange.range(type, player);
    return AirRange.canReach(player, zone, type, range, target.territory());
  }

  /** Enemy units an air attack on this land territory may hit. */
  public static List<Unit> getLandTargetUnits(final GamePlayer attacker, final Territory t) {
    return t.getUnitCollection()
        .getMatches(
            u ->
                atWar(attacker, u.getOwner())
                    && Matches.unitIsLand().test(u)
                    && Matches.unitIsNotInfrastructure().test(u));
  }

  /**
   * Enemy ships of the given kind (surface or submarines) in a sea zone that can take hits.
   * Transports are never casualties; see {@link #getUndefendedTransports}.
   */
  public static List<Unit> getShipTargetUnits(
      final GamePlayer attacker, final Territory seaZone, final boolean subs) {
    return seaZone
        .getUnitCollection()
        .getMatches(
            enemyShip(attacker)
                .and(subs ? Matches.unitCanEvade() : Matches.unitCanEvade().negate())
                .and(Matches.unitIsSeaTransportButNotCombatSeaTransport().negate()));
  }

  /**
   * Enemy transports in the sea zone once no other enemy ship is left to protect them; an attack
   * with surviving aircraft destroys them.
   */
  public static List<Unit> getUndefendedTransports(
      final GamePlayer attacker, final Territory seaZone) {
    final boolean defended =
        seaZone.anyUnitsMatch(
            enemyShip(attacker).and(Matches.unitIsSeaTransportButNotCombatSeaTransport().negate()));
    return defended
        ? List.of()
        : seaZone
            .getUnitCollection()
            .getMatches(
                enemyShip(attacker).and(Matches.unitIsSeaTransportButNotCombatSeaTransport()));
  }

  private static java.util.function.Predicate<Unit> enemyShip(final GamePlayer player) {
    return u -> atWar(player, u.getOwner()) && Matches.unitIsSea().test(u);
  }

  public static boolean atWar(final GamePlayer a, final GamePlayer b) {
    return a.getData().getRelationshipTracker().isAtWar(a, b);
  }

  /**
   * Firing power of an aircraft in a dogfight: its dogfight value (the type's own, plus the owner's
   * dogfightBonus and its techs', never below 1) times its dogfight rolls.
   */
  public static int dogfightPower(final Unit unit) {
    final var techs = unit.getData().getTechTracker();
    final int extraRolls = techs.getDogfightRollsBonus(unit.getOwner(), unit.getType());
    return AirUnitAttachment.get(unit.getType())
        .map(a -> dogfightValue(unit) * (a.getDogfightRolls() + extraRolls))
        .orElse(0);
  }

  /**
   * The value one of an aircraft's dogfight dice hits at: the type's dogfight value plus the
   * owner's dogfightBonus and its techs', never below 1. Never doubled: doubling against land or
   * ships belongs to tactical strikes only.
   */
  public static int dogfightValue(final Unit unit) {
    final int bonus =
        unit.getData().getTechTracker().getDogfightBonus(unit.getOwner(), unit.getType());
    return AirUnitAttachment.get(unit.getType())
        .map(a -> Math.max(1, a.getDogfight() + bonus))
        .orElse(0);
  }

  /** Firing power of an aircraft attacking this kind of target. */
  public static int strikePower(final Unit unit, final TargetKind kind) {
    final var ua = unit.getUnitAttachment();
    final int base = ua.getAttack(unit.getOwner()) * ua.getAttackRolls(unit.getOwner());
    final AirUnitAttachment air = AirUnitAttachment.get(unit.getType()).orElse(null);
    if (air != null
        && ((kind == TargetKind.LAND && air.isDoubleVsLand())
            || (kind == TargetKind.SURFACE && air.isDoubleVsSurface()))) {
      return 2 * base;
    }
    return base;
  }

  /** Firing power of a ship firing back at aircraft (its defense). */
  public static int shipFireBackPower(final Unit ship) {
    final var ua = ship.getUnitAttachment();
    int multiplier = 1;
    for (final TechAdvance tech :
        TechTracker.getCurrentTechAdvances(
            ship.getOwner(), ship.getData().getTechnologyFrontier())) {
      final TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
      if (ability != null) {
        // Naval Wasserfall: German ships fire back at double their defense
        multiplier = Math.max(multiplier, ability.getAirFireBackMultiplier());
      }
    }
    return multiplier * ua.getDefense(ship.getOwner()) * ua.getDefenseRolls(ship.getOwner());
  }

  /** Firing power of an anti-aircraft gun at aircraft. */
  public static int aaPower(final Unit aaGun) {
    return aaGun.getUnitAttachment().getAttackAa(aaGun.getOwner());
  }

  /** The result of a low luck roll: hits, and the one die rolled for the remainder (if any). */
  public record Roll(int hits, int[] dice, int power) {}

  /**
   * A low luck roll in plain words (request 15): "AA guns fire at 2: rolled 4, miss", "Germans fire
   * at 12: 2 sure hits", "Germans fire at 9: 1 sure hit, then rolled 2 at 3, hit: 2 hits". Dice
   * read 1 to 6 as on screen; the die hits when it is at or below the remainder.
   */
  public static String describeRoll(final String who, final Roll roll, final int sides) {
    final int sure = roll.power() / sides;
    final int remainder = roll.power() % sides;
    final String start = who + " fire at " + roll.power() + ": ";
    final int die = roll.dice().length == 0 ? -1 : roll.dice()[0] + 1;
    if (sure == 0) {
      return die < 0
          ? start + "no dice, no hits"
          : start + "rolled " + die + ", " + (roll.hits() > 0 ? "hit" : "miss");
    }
    final StringBuilder text =
        new StringBuilder(start + sure + MyFormatter.pluralize(" sure hit", sure));
    if (die >= 0) {
      text.append(", then rolled ")
          .append(die)
          .append(" at ")
          .append(remainder)
          .append(", ")
          .append(die <= remainder ? "hit" : "miss")
          .append(": ")
          .append(roll.hits())
          .append(MyFormatter.pluralize(" hit", roll.hits()));
    }
    return text.toString();
  }

  /** Low luck: every full die's worth of power is a hit; the remainder is rolled with one die. */
  public static Roll lowLuck(
      final IDelegateBridge bridge,
      final GamePlayer roller,
      final int power,
      final String annotation) {
    final int sides = bridge.getData().getDiceSides();
    final int sure = power / sides;
    final int remainder = power % sides;
    if (remainder == 0) {
      return new Roll(sure, new int[0], power);
    }
    final int die = bridge.getRandom(sides, roller, DiceType.COMBAT, annotation);
    return new Roll(sure + (die < remainder ? 1 : 0), new int[] {die}, power);
  }

  /** The nation with the most of these units, which decides where hits on them go. */
  public static GamePlayer chooser(final Collection<Unit> units) {
    final Map<GamePlayer, Long> counts =
        units.stream().collect(Collectors.groupingBy(Unit::getOwner, Collectors.counting()));
    final List<GamePlayer> order = units.iterator().next().getData().getPlayerList().getPlayers();
    return counts.entrySet().stream()
        .max(
            Comparator.<Map.Entry<GamePlayer, Long>>comparingLong(Map.Entry::getValue)
                .thenComparing(e -> -order.indexOf(e.getKey())))
        .map(Map.Entry::getKey)
        .orElseThrow();
  }

  /** Units hit (killed or damaged) by a call to {@link #takeHits}. */
  public record Hit(List<Unit> killed, List<Unit> damaged) {
    public List<Unit> all() {
      final List<Unit> all = new ArrayList<>(killed);
      damaged.stream().filter(u -> !all.contains(u)).forEach(all::add);
      return all;
    }
  }

  /**
   * The owner with the most of the target units chooses where the hits go (sticky hits apply), in
   * the battle window; damage is marked and destroyed units are removed, and the window shows the
   * casualties.
   *
   * @param step the battle window step the casualties belong to
   * @param dice the roll as the battle window shows it
   */
  public static Hit takeHits(
      final IDelegateBridge bridge,
      final AirBattleView view,
      final String step,
      final Territory location,
      final Collection<Unit> targets,
      final Collection<Unit> firing,
      final Roll roll,
      final DiceRoll dice,
      final String text) {
    if (roll.hits() <= 0 || targets.isEmpty()) {
      return new Hit(List.of(), List.of());
    }
    final GameData data = bridge.getData();
    final GamePlayer chooser = chooser(targets);
    final List<Unit> targetList = new ArrayList<>(targets);
    final CasualtyDetails details =
        CasualtySelector.selectCasualties(
            chooser,
            targetList,
            CombatValueBuilder.mainCombatValue()
                .enemyUnits(new ArrayList<>(firing))
                .friendlyUnits(targetList)
                .side(BattleState.Side.DEFENSE)
                .gameSequence(data.getSequence())
                .supportAttachments(data.getUnitTypeList().getSupportRules())
                .lhtrHeavyBombers(false)
                .gameDiceSides(data.getDiceSides())
                .territoryEffects(List.of())
                .build(),
            location,
            bridge,
            text,
            dice,
            view.getId(),
            false,
            // with "Transport Casualties Restricted" the selector counts these hits, not the dice;
            // transports are never among air targets
            roll.hits(),
            true);
    final List<Unit> killed = new ArrayList<>(details.getKilled());
    final List<Unit> damaged = new ArrayList<>(details.getDamaged());
    damaged.removeAll(killed);
    BattleDelegate.markDamaged(details.getDamaged(), bridge, location);
    if (!killed.isEmpty()) {
      bridge
          .getHistoryWriter()
          .addChildToEvent(
              MyFormatter.unitsToText(killed) + " destroyed in " + location.getName(), killed);
      bridge.addChange(ChangeFactory.removeUnits(location, killed));
    }
    if (!damaged.isEmpty()) {
      bridge
          .getHistoryWriter()
          .addChildToEvent(
              MyFormatter.unitsToText(damaged) + " damaged in " + location.getName(), damaged);
    }
    view.showCasualties(
        step, dice, chooser, chooser(firing), killed, damaged, details.getAutoCalculated());
    return new Hit(killed, damaged);
  }

  /**
   * Whether a battle window shows an air phase battle: some of its units are in an air zone box
   * (the aircraft; in a strike the targets are on the map).
   */
  public static boolean isAirZoneBattle(
      final Collection<Unit> side, final Collection<Unit> enemies) {
    final List<Unit> all = new ArrayList<>(side);
    all.addAll(enemies);
    if (all.isEmpty()) {
      return false;
    }
    final GameData data = all.get(0).getData();
    return AirZoneAttachment.getAirZones(data.getMap()).stream()
        .anyMatch(zone -> all.stream().anyMatch(u -> zone.getUnitCollection().contains(u)));
  }

  /**
   * Whether the air phase battle shown at this place is a dogfight. Dogfights are shown at their
   * air front, strikes at their target territory or sea zone.
   */
  public static boolean isDogfight(final Territory location) {
    return location != null && AirZoneAttachment.isAirZone(location);
  }

  /**
   * The battle window column for a unit in an air phase battle: in a dogfight every aircraft's
   * dogfight value; in a strike the bombers' strike power against the target, AA for anti-aircraft
   * guns, and fire-back for ships; 0 for units that do not fire.
   *
   * @param enemies the other side as the battle began: losses must not change how a side fires
   */
  public static int displayStrength(
      final Unit unit, final boolean dogfight, final Collection<Unit> enemies) {
    final boolean air = Matches.unitIsAir().test(unit);
    if (air && dogfight) {
      return dogfightValue(unit);
    }
    if (air) {
      final boolean sea = enemies.stream().anyMatch(Matches.unitIsSea());
      final boolean subs =
          sea && enemies.stream().filter(Matches.unitIsSea()).allMatch(Matches.unitCanEvade());
      return strikePower(
          unit, sea ? (subs ? TargetKind.SUBS : TargetKind.SURFACE) : TargetKind.LAND);
    }
    if (Matches.unitIsAaForAnything().test(unit)) {
      return aaPower(unit);
    }
    if (Matches.unitIsSea().test(unit)) {
      return shipFireBackPower(unit);
    }
    return 0;
  }

  /**
   * PUs one aircraft's strategic bombing destroys: its type's bombingDamage plus the owner's tech
   * bombingBonus for the type.
   */
  public static int bombingDamage(final Unit unit) {
    final int base =
        AirUnitAttachment.get(unit.getType()).map(AirUnitAttachment::getBombingDamage).orElse(0);
    if (base <= 0) {
      return 0;
    }
    return Math.max(
        0, base + unit.getData().getTechTracker().getBombingBonus(unit.getOwner(), unit.getType()));
  }

  /**
   * A unit type in running text: "strategicBomber" becomes "strategic bomber"; renamed types keep
   * their names ("Special Production Center").
   */
  public static String unitName(final UnitType type) {
    if (games.strategy.triplea.formatter.UnitNames.isRenamed(type.getName())) {
      return games.strategy.triplea.formatter.UnitNames.of(type);
    }
    return type.getName().replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").toLowerCase(Locale.ROOT);
  }

  /** "German" for the Germans, "British" for the British. */
  public static String adjective(final GamePlayer player) {
    final String name = player.getName();
    return name.endsWith("ns") ? name.substring(0, name.length() - 1) : name;
  }

  /** Twice the territory's value: the most PUs one round of strategic bombing can destroy there. */
  public static int bombingCap(final Territory t) {
    return 2 * TerritoryAttachment.getProduction(t);
  }
}
