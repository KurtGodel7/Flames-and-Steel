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
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.Matches;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        final GamePlayer bombed = attachment.getBombedPlayer();
        if (bombed != null && atWar(player, bombed)) {
          for (final Territory t : player.getData().getMap().getTerritoriesOwnedBy(bombed)) {
            // factories and APCs are strategic targets
            if (t.anyUnitsMatch(
                Matches.unitCanProduceUnits().or(u -> ApcAttachment.isApc(u.getType())))) {
              targets.add(new Target(t, TargetKind.STRATEGIC));
            }
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
          } else if (!getLandTargetUnits(player, t).isEmpty()) {
            targets.add(new Target(t, TargetKind.LAND));
          }
        }
      }
    }
    return targets;
  }

  /** Whether this aircraft type may attack that kind of target. */
  public static boolean mayAttack(final UnitType type, final TargetKind kind) {
    final AirUnitAttachment air = AirUnitAttachment.get(type).orElse(null);
    return switch (kind) {
      case STRATEGIC -> air != null && air.getBombingDamage() > 0;
      case SUBS -> air != null && air.isCanAttackSubs();
      default -> true;
    };
  }

  /**
   * Whether this nation's aircraft of this type in the zone may attack the target: the type must
   * suit the target and the target must be in range. In a Bomb zone every aircraft of the attacking
   * side there flies at the shortest range among them (escorts limit the bombers).
   */
  public static boolean mayAttack(
      final GamePlayer player, final Territory zone, final UnitType type, final Target target) {
    if (!mayAttack(type, target.kind())) {
      return false;
    }
    final int range;
    if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.BOMB) {
      final List<Unit> side =
          zone.getUnitCollection()
              .getMatches(u -> Matches.unitIsAir().test(u) && !atWar(player, u.getOwner()));
      range = AirRange.shortestRange(side);
    } else {
      range = AirRange.range(type, player);
    }
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

  /** Enemy ships of the given kind (surface or submarines) in a sea zone. */
  public static List<Unit> getShipTargetUnits(
      final GamePlayer attacker, final Territory seaZone, final boolean subs) {
    return seaZone
        .getUnitCollection()
        .getMatches(
            enemyShip(attacker)
                .and(subs ? Matches.unitCanEvade() : Matches.unitCanEvade().negate()));
  }

  private static java.util.function.Predicate<Unit> enemyShip(final GamePlayer player) {
    return u -> atWar(player, u.getOwner()) && Matches.unitIsSea().test(u);
  }

  public static boolean atWar(final GamePlayer a, final GamePlayer b) {
    return a.getData().getRelationshipTracker().isAtWar(a, b);
  }

  /** Firing power of an aircraft in a dogfight. */
  public static int dogfightPower(final Unit unit) {
    return AirUnitAttachment.get(unit.getType())
        .map(a -> a.getDogfight() * a.getDogfightRolls())
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
    return ua.getDefense(ship.getOwner()) * ua.getDefenseRolls(ship.getOwner());
  }

  /** Firing power of an anti-aircraft gun at aircraft. */
  public static int aaPower(final Unit aaGun) {
    return aaGun.getUnitAttachment().getAttackAa(aaGun.getOwner());
  }

  /** The result of a low luck roll: hits, and the one die rolled for the remainder (if any). */
  public record Roll(int hits, int[] dice, int power) {}

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
   * The owner with the most of the target units chooses where the hits go (sticky hits apply);
   * damage is marked and destroyed units are removed.
   */
  public static Hit takeHits(
      final IDelegateBridge bridge,
      final Territory location,
      final Collection<Unit> targets,
      final Collection<Unit> firing,
      final Roll roll,
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
            new DiceRoll(roll.dice(), roll.hits(), 0, false, chooser.getName()),
            UUID.randomUUID(),
            false,
            0,
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
    return new Hit(killed, damaged);
  }

  /** Twice the territory's value: the most PUs one round of strategic bombing can destroy there. */
  public static int bombingCap(final Territory t) {
    return 2 * TerritoryAttachment.getProduction(t);
  }
}
