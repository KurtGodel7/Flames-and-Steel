package games.strategy.triplea.delegate.apc;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.ApcAttachment;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.AirReserve;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.formatter.MyFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel advanced production centres. An APC is a unit on the map, like a factory. Each
 * of a nation's own APCs builds one advanced unit type, chosen in the purchase phase, at no PU
 * cost; the units appear in the APC's territory near the end of the turn. A captured APC does
 * nothing for its captor and does not count against the captor's APC cap. A nation may have no more
 * APCs than the lower of its national cap and its Advanced techs allow.
 */
@UtilityClass
public class Apcs {

  public static boolean isApc(final Unit unit) {
    return ApcAttachment.isApc(unit.getType());
  }

  /** An APC built by this nation (not captured from another), now owned by it. */
  public static boolean isOwnApc(final Unit unit, final GamePlayer player) {
    if (!isApc(unit) || !unit.isOwnedBy(player)) {
      return false;
    }
    final GamePlayer original = unit.getOriginalOwner();
    return original == null || original.equals(player);
  }

  /** The nation's own APCs on the map, in territories it holds. */
  public static List<Unit> getOwnApcs(final GamePlayer player, final GameMap map) {
    final List<Unit> apcs = new ArrayList<>();
    for (final Territory t : map.getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(player)) {
        apcs.addAll(t.getUnitCollection().getMatches(u -> isOwnApc(u, player)));
      }
    }
    return apcs;
  }

  /** The territory an APC stands in. */
  public static Optional<Territory> territoryOf(final Unit apc, final GameMap map) {
    return map.getTerritories().stream()
        .filter(t -> t.getUnitCollection().contains(apc))
        .findFirst();
  }

  /** How many APCs the nation may have: its national cap, limited by its Advanced techs. */
  public static int getLimit(final GamePlayer player) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    final int nationalCap = attachment == null ? 0 : attachment.getApcLimit();
    int fromTechs = 0;
    for (final TechAdvance tech :
        TechTracker.getCurrentTechAdvances(player, player.getData().getTechnologyFrontier())) {
      final TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
      if (ability != null) {
        fromTechs += ability.getApcLimitBonus();
      }
    }
    return Math.min(nationalCap, fromTechs);
  }

  /**
   * APCs counting against the nation's cap: every APC it built, whoever holds it now (a captured
   * APC still counts for the nation that built it, never for the captor), plus APCs bought but not
   * yet placed.
   */
  public static int countTowardsLimit(final GamePlayer player, final GameMap map) {
    int count = player.getUnitCollection().countMatches(Apcs::isApc);
    for (final Territory t : map.getTerritories()) {
      count += t.getUnitCollection().countMatches(u -> isApc(u) && builtBy(u, player));
    }
    return count;
  }

  private static boolean builtBy(final Unit apc, final GamePlayer player) {
    final GamePlayer original = apc.getOriginalOwner();
    return original == null ? apc.isOwnedBy(player) : original.equals(player);
  }

  /** Advanced unit types this nation's APCs may build now (it has the needed tech). */
  public static List<UnitType> getBuildableTypes(final GamePlayer player) {
    final List<UnitType> types = new ArrayList<>();
    for (final UnitType type : player.getData().getUnitTypeList().getAllUnitTypes()) {
      final Optional<ApcAttachment> apc = ApcAttachment.get(type);
      if (apc.isEmpty() || apc.get().getApcUnitsPerTurn() <= 0) {
        continue;
      }
      final Optional<String> tech = apc.get().getApcRequiredTech();
      if (tech.isEmpty() || hasTech(player, tech.get())) {
        types.add(type);
      }
    }
    return types;
  }

  private static boolean hasTech(final GamePlayer player, final String techName) {
    return TechTracker.getCurrentTechAdvances(player, player.getData().getTechnologyFrontier())
        .stream()
        .anyMatch(t -> t.getName().equals(techName));
  }

  /** Human-readable output rate, e.g. "2 per turn" or "1 every 2 turns". */
  public static String describeRate(final UnitType type) {
    final ApcAttachment apc = ApcAttachment.get(type).orElseThrow();
    return apc.getApcTurnsPerUnit() == 1
        ? apc.getApcUnitsPerTurn() + " per turn"
        : apc.getApcUnitsPerTurn() + " every " + apc.getApcTurnsPerUnit() + " turns";
  }

  /** What the APC is set to build, if anything. */
  public static Optional<UnitType> getProduction(final Unit apc, final GamePlayer player) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null) {
      return Optional.empty();
    }
    final String typeName = attachment.getApcProduction().get(apc.getId().toString());
    return typeName == null
        ? Optional.empty()
        : player.getData().getUnitTypeList().getUnitType(typeName);
  }

  /** Why this production choice is not allowed, if it is not. */
  public static Optional<String> validate(
      final GamePlayer player, final Map<Unit, UnitType> choices, final GameMap map) {
    final List<UnitType> buildable = getBuildableTypes(player);
    for (final Map.Entry<Unit, UnitType> choice : choices.entrySet()) {
      if (!isOwnApc(choice.getKey(), player)) {
        return Optional.of("That is not one of " + player.getName() + "'s own APCs.");
      }
      if (choice.getValue() != null && !buildable.contains(choice.getValue())) {
        return Optional.of(
            player.getName() + " cannot build " + choice.getValue().getName() + " in an APC.");
      }
    }
    return Optional.empty();
  }

  /** Records the production choices (a null type means the APC builds nothing). */
  public static void setProduction(
      final IDelegateBridge bridge, final GamePlayer player, final Map<Unit, UnitType> choices) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null) {
      return;
    }
    final Map<String, String> production = new HashMap<>(attachment.getApcProduction());
    final IntegerMap<String> progress = new IntegerMap<>(attachment.getApcProgress());
    for (final Map.Entry<Unit, UnitType> choice : choices.entrySet()) {
      final String id = choice.getKey().getId().toString();
      final String before = production.get(id);
      if (choice.getValue() == null) {
        production.remove(id);
      } else {
        production.put(id, choice.getValue().getName());
      }
      if (before == null || !before.equals(production.get(id))) {
        // switching type starts slow builds from scratch
        progress.removeKey(id);
      }
    }
    final CompositeChange change = new CompositeChange();
    change.add(ChangeFactory.attachmentPropertyChange(attachment, production, "apcProduction"));
    change.add(ChangeFactory.attachmentPropertyChange(attachment, progress, "apcProgress"));
    bridge.addChange(change);
  }

  /** Each of the nation's own APCs builds its units, which appear in its territory. */
  public static void produce(final IDelegateBridge bridge, final GamePlayer player) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null) {
      return;
    }
    final GameMap map = bridge.getData().getMap();
    final List<UnitType> buildable = getBuildableTypes(player);
    final IntegerMap<String> progress = new IntegerMap<>(attachment.getApcProgress());
    final CompositeChange change = new CompositeChange();
    final List<String> lines = new ArrayList<>();
    for (final Unit apc : getOwnApcs(player, map)) {
      final Optional<UnitType> type = getProduction(apc, player);
      final Optional<Territory> territory = territoryOf(apc, map);
      if (type.isEmpty() || territory.isEmpty() || !buildable.contains(type.get())) {
        continue;
      }
      final ApcAttachment rate = ApcAttachment.get(type.get()).orElseThrow();
      final String id = apc.getId().toString();
      progress.add(id, 1);
      if (progress.getInt(id) < rate.getApcTurnsPerUnit()) {
        lines.add(
            "APC in "
                + territory.get().getName()
                + ": "
                + type.get().getName()
                + " ready in "
                + (rate.getApcTurnsPerUnit() - progress.getInt(id))
                + " more turn(s)");
        continue;
      }
      progress.removeKey(id);
      final Optional<Territory> placeAt = placeFor(type.get(), territory.get(), player, map);
      if (placeAt.isEmpty()) {
        lines.add(
            "APC in "
                + territory.get().getName()
                + ": no sea zone next to it, so no "
                + type.get().getName()
                + " this turn");
        continue;
      }
      final List<Unit> built = type.get().create(rate.getApcUnitsPerTurn(), player);
      change.add(ChangeFactory.addUnits(placeAt.get(), built));
      lines.add(
          "APC in "
              + territory.get().getName()
              + " builds "
              + MyFormatter.unitsToText(built)
              + (placeAt.get().equals(territory.get()) ? "" : " in " + placeAt.get().getName()));
    }
    change.add(ChangeFactory.attachmentPropertyChange(attachment, progress, "apcProgress"));
    if (!lines.isEmpty()) {
      bridge.getHistoryWriter().startEvent(player.getName() + " APC production");
      lines.forEach(line -> bridge.getHistoryWriter().addChildToEvent(line));
    }
    bridge.addChange(change);
    // aircraft built by APCs go straight to the aircraft reserve
    AirReserve.sendAircraftToReserve(bridge, player);
  }

  /**
   * Where an APC's new units appear: its own territory, or for ships the first adjacent sea zone
   * (enemy ships there do not prevent it).
   */
  private static Optional<Territory> placeFor(
      final UnitType type,
      final Territory apcTerritory,
      final GamePlayer player,
      final GameMap map) {
    if (!type.getUnitAttachment().isSea()) {
      return Optional.of(apcTerritory);
    }
    return map.getNeighbors(apcTerritory).stream()
        .filter(Territory::isWater)
        .filter(t -> !AirZoneAttachment.isAirZone(t))
        .sorted(Comparator.comparing(Territory::getName))
        .findFirst();
  }
}
