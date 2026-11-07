package games.strategy.triplea.delegate.apc;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.ApcAttachment;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.UnitNames;
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

  /**
   * Request 57: how many more SPCs the nation may buy now, and if none, why (for the build screen).
   *
   * @param allowed {@code max(0, limit - count)}
   * @param reason empty when some may be bought, else "needs Advanced 1 research", "limit reached
   *     (2 of 2)" or "national limit reached (3)"
   */
  public record Allowance(int allowed, int limit, int count, int nationalCap, String reason) {}

  public static Allowance allowance(final GamePlayer player, final GameMap map) {
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
    final int limit = Math.min(nationalCap, fromTechs);
    final int count = countTowardsLimit(player, map);
    final int allowed = Math.max(0, limit - count);
    final String reason;
    if (allowed > 0) {
      reason = "";
    } else if (fromTechs <= 0) {
      reason = "needs Advanced 1 research";
    } else if (fromTechs > nationalCap) {
      reason = "national limit reached (" + nationalCap + ")";
    } else {
      reason = "limit reached (" + count + " of " + limit + ")";
    }
    return new Allowance(allowed, limit, count, nationalCap, reason);
  }

  /** Request 57: the build screen's tooltip line explaining the SPC rule. */
  public static String allowanceRule(final Allowance allowance) {
    return "You may own as many Special Production Centers as your Advanced techs (Advanced 1\u20134),"
        + " up to your national limit of "
        + allowance.nationalCap()
        + ". Each builds one advanced unit type you have researched.";
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

  // ---- request 11 item 3: warnings for SPCs that can't build anything

  /** "AdvancedArmor" as "Advanced Armor". */
  private static String techName(final String name) {
    return name.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
  }

  /** The techs that unlock an SPC unit type, by readable name, from the XML. */
  public static List<String> unlockingTechs(final GameData data) {
    final java.util.Set<String> techs = new java.util.TreeSet<>();
    for (final UnitType type : data.getUnitTypeList().getAllUnitTypes()) {
      ApcAttachment.get(type)
          .filter(a -> a.getApcUnitsPerTurn() > 0)
          .flatMap(ApcAttachment::getApcRequiredTech)
          .ifPresent(t -> techs.add(techName(t)));
    }
    return List.copyOf(techs);
  }

  /**
   * "Builds nothing yet: needs an Advanced research (Advanced Armor, Advanced Jets, …)" when none
   * of the nation's SPCs could build anything now.
   */
  public static Optional<String> buildsNothingWarning(final GamePlayer player) {
    if (!getBuildableTypes(player).isEmpty()) {
      return Optional.empty();
    }
    final List<String> techs = unlockingTechs(player.getData());
    return Optional.of(
        "Builds nothing yet: needs "
            + (techs.isEmpty()
                ? "a tech that unlocks an SPC unit"
                : "one of " + String.join(", ", techs)));
  }

  /**
   * Why the SPC sits idle this turn, if it does: nothing chosen, no tech, or its unit is barred.
   */
  public static Optional<String> idleReason(final Unit apc, final GamePlayer player) {
    final List<UnitType> buildable = getBuildableTypes(player);
    if (buildable.isEmpty()) {
      return Optional.of("idle, no Advanced tech yet");
    }
    final Optional<UnitType> type = getProduction(apc, player);
    if (type.isEmpty()) {
      return Optional.of("idle, nothing chosen");
    }
    if (!buildable.contains(type.get())) {
      return Optional.of("idle, " + UnitNames.of(type.get()) + " can no longer be built");
    }
    final int max = type.get().getUnitAttachment().getMaxBuiltPerPlayer();
    if (max >= 0) {
      int count = player.getUnitCollection().countMatches(u -> u.getType().equals(type.get()));
      for (final Territory t : player.getData().getMap().getTerritories()) {
        count +=
            t.getUnitCollection()
                .countMatches(u -> u.isOwnedBy(player) && u.getType().equals(type.get()));
      }
      if (count >= max) {
        return Optional.of("idle, " + UnitNames.of(type.get()) + " limit reached (" + max + ")");
      }
    }
    return Optional.empty();
  }

  /** "Essen SPC: idle, no Advanced tech yet" for each of the nation's SPCs that builds nothing. */
  public static List<String> idleLines(final GamePlayer player, final GameMap map) {
    final List<String> lines = new ArrayList<>();
    for (final Unit apc : getOwnApcs(player, map)) {
      final String where = territoryOf(apc, map).map(Territory::getName).orElse("?");
      idleReason(apc, player).ifPresent(r -> lines.add(where + " SPC: " + r));
    }
    return lines;
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
        return Optional.of("That is not one of " + player.getName() + "'s own SPCs.");
      }
      if (choice.getValue() != null && !buildable.contains(choice.getValue())) {
        return Optional.of(
            player.getName() + " cannot build " + choice.getValue().getName() + " in an SPC.");
      }
    }
    return Optional.empty();
  }

  /** Records the production choices (a null type means the APC builds nothing). */
  public static void setProduction(
      final IDelegateBridge bridge, final GamePlayer player, final Map<Unit, UnitType> choices) {
    setProduction(bridge, player, choices, Map.of());
  }

  /**
   * Request 62: records the production choices and, for SPCs building ships, the sea zone the ships
   * go to (an SPC not in {@code seaZones} keeps its stored zone). A land or air unit, or nothing,
   * clears the stored zone.
   */
  public static void setProduction(
      final IDelegateBridge bridge,
      final GamePlayer player,
      final Map<Unit, UnitType> choices,
      final Map<Unit, Territory> seaZones) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null) {
      return;
    }
    final Map<String, String> production = new HashMap<>(attachment.getApcProduction());
    final IntegerMap<String> progress = new IntegerMap<>(attachment.getApcProgress());
    final Map<String, String> zones = new HashMap<>(attachment.getApcSeaZone());
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
      if (choice.getValue() == null || !choice.getValue().getUnitAttachment().isSea()) {
        zones.remove(id);
      } else if (seaZones.containsKey(choice.getKey())) {
        final Territory zone = seaZones.get(choice.getKey());
        if (zone == null) {
          zones.remove(id);
        } else {
          zones.put(id, zone.getName());
        }
      }
    }
    final CompositeChange change = new CompositeChange();
    change.add(ChangeFactory.attachmentPropertyChange(attachment, production, "apcProduction"));
    change.add(ChangeFactory.attachmentPropertyChange(attachment, progress, "apcProgress"));
    if (!zones.equals(attachment.getApcSeaZone())) {
      change.add(ChangeFactory.attachmentPropertyChange(attachment, zones, "apcSeaZone"));
    }
    bridge.addChange(change);
  }

  /**
   * Request 62: the sea zones next to the SPC's territory that its ships may go to, by name (the
   * airborne helper territories left out).
   */
  public static List<Territory> seaZonesNextTo(final Territory apcTerritory, final GameMap map) {
    return map.getNeighbors(apcTerritory).stream()
        .filter(Territory::isWater)
        .filter(t -> !games.strategy.triplea.delegate.air.AirBases.isAirborneTerritory(t))
        .sorted(Comparator.comparing(Territory::getName))
        .toList();
  }

  /** Request 62: the sea zone chosen for the SPC's ships, if any (it may no longer be adjacent). */
  public static Optional<Territory> getSeaZone(final Unit apc, final GamePlayer player) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null) {
      return Optional.empty();
    }
    final String name = attachment.getApcSeaZone().get(apc.getId().toString());
    return name == null
        ? Optional.empty()
        : player.getData().getMap().getTerritories().stream()
            .filter(t -> t.getName().equals(name))
            .findFirst();
  }

  /** Request 62: why a chosen sea zone is refused (not next to that SPC), if it is. */
  public static Optional<String> validateSeaZones(
      final GamePlayer player, final Map<Unit, Territory> seaZones, final GameMap map) {
    for (final Map.Entry<Unit, Territory> e : seaZones.entrySet()) {
      if (e.getValue() == null) {
        continue;
      }
      if (!isOwnApc(e.getKey(), player)) {
        return Optional.of("That is not one of " + player.getName() + "'s own SPCs.");
      }
      final Optional<Territory> at = territoryOf(e.getKey(), map);
      if (at.isEmpty() || !seaZonesNextTo(at.get(), map).contains(e.getValue())) {
        return Optional.of(
            e.getValue().getName()
                + " is not next to the SPC in "
                + at.map(Territory::getName).orElse("?")
                + ".");
      }
    }
    return Optional.empty();
  }

  /**
   * Request 62: where the SPC's ships go now: the chosen sea zone while it is still next to the
   * SPC, else the first adjacent sea zone by name.
   */
  public static Optional<Territory> shipsGoTo(
      final Unit apc, final Territory apcTerritory, final GamePlayer player, final GameMap map) {
    final List<Territory> zones = seaZonesNextTo(apcTerritory, map);
    final Optional<Territory> chosen = getSeaZone(apc, player);
    if (chosen.isPresent() && zones.contains(chosen.get())) {
      return chosen;
    }
    return zones.stream().findFirst();
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
    // request 11 item 3: idle SPCs are listed with the reason
    final List<String> lines = new ArrayList<>(idleLines(player, map));
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
            "SPC in "
                + territory.get().getName()
                + ": "
                + type.get().getName()
                + " ready in "
                + (rate.getApcTurnsPerUnit() - progress.getInt(id))
                + " more turn(s)"
                + (type.get().getUnitAttachment().isSea()
                    ? shipsGoTo(apc, territory.get(), player, map)
                        .map(z -> ", ships to " + z.getName())
                        .orElse("")
                    : ""));
        continue;
      }
      progress.removeKey(id);
      final Optional<Territory> placeAt = placeFor(type.get(), territory.get(), apc, player, map);
      if (placeAt.isEmpty()) {
        lines.add(
            "SPC in "
                + territory.get().getName()
                + ": no sea zone next to it, so no "
                + type.get().getName()
                + " this turn");
        continue;
      }
      final List<Unit> built = type.get().create(rate.getApcUnitsPerTurn(), player);
      change.add(ChangeFactory.addUnits(placeAt.get(), built));
      lines.add(
          "SPC in "
              + territory.get().getName()
              + " builds "
              + MyFormatter.unitsToText(built)
              + (placeAt.get().equals(territory.get()) ? "" : " in " + placeAt.get().getName()));
    }
    change.add(ChangeFactory.attachmentPropertyChange(attachment, progress, "apcProgress"));
    if (!lines.isEmpty()) {
      bridge.getHistoryWriter().startEvent(player.getName() + " SPC production");
      lines.forEach(line -> bridge.getHistoryWriter().addChildToEvent(line));
    }
    bridge.addChange(change);
    produceBonuses(bridge, player);
  }

  /**
   * Bonuses from owned techs for each of the nation's own APCs: income (Increased Production,
   * Industrialization, Accelerated Production) and free units (Speer's Panther, only once the
   * nation can build that unit).
   */
  private static void produceBonuses(final IDelegateBridge bridge, final GamePlayer player) {
    final GameMap map = bridge.getData().getMap();
    final List<Unit> apcs = getOwnApcs(player, map);
    if (apcs.isEmpty()) {
      return;
    }
    int incomePerApc = 0;
    final List<TechAdvance> freeUnitTechs = new ArrayList<>();
    for (final TechAdvance tech :
        TechTracker.getCurrentTechAdvances(player, player.getData().getTechnologyFrontier())) {
      final TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
      if (ability != null) {
        incomePerApc += ability.getApcIncome();
        if (!ability.getApcFreeUnits().isEmpty()) {
          freeUnitTechs.add(tech);
        }
      }
    }
    final CompositeChange change = new CompositeChange();
    final List<String> lines = new ArrayList<>();
    if (incomePerApc > 0) {
      final int full = incomePerApc * apcs.size();
      // request 19: a mobilizing nation gets only this round's share of its PU bonuses too
      final int percent =
          games.strategy.triplea.delegate.Mobilization.percent(
              player, bridge.getData().getSequence().getRound());
      final int income = games.strategy.triplea.delegate.Mobilization.scale(full, percent);
      change.add(
          ChangeFactory.changeResourcesChange(
              player,
              bridge.getData().getResourceList().getResourceOrThrow(Constants.PUS),
              income));
      lines.add(
          player.getName()
              + " receive "
              + income
              + MyFormatter.pluralize(" PU", income)
              + " from "
              + apcs.size()
              + " SPC(s)"
              + (income != full ? " (mobilization " + percent + "% of " + full + ")" : ""));
    }
    // request 50: a tech's free units come once every apcFreeUnitsTurns turns, counted per APC
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    final IntegerMap<String> wait =
        new IntegerMap<>(
            attachment == null ? new IntegerMap<>() : attachment.getApcFreeUnitsWait());
    for (final TechAdvance tech : freeUnitTechs) {
      final IntegerMap<UnitType> freePerApc = unlockedFreeUnits(tech, player);
      if (freePerApc.isEmpty()) {
        continue;
      }
      final int turns = TechAbilityAttachment.get(tech).getApcFreeUnitsTurns();
      for (final Unit apc : apcs) {
        final String key = freeUnitsKey(apc, tech);
        final Optional<Territory> territory = territoryOf(apc, map);
        if (wait.getInt(key) > 0) {
          wait.add(key, -1);
          lines.add(
              "SPC in "
                  + territory.map(Territory::getName).orElse("?")
                  + ": "
                  + freeUnitsStatus(freePerApc, wait.getInt(key) + 1));
          continue;
        }
        if (turns > 1) {
          wait.put(key, turns - 1);
        } else {
          wait.removeKey(key);
        }
        for (final UnitType type : freePerApc.keySet()) {
          final Optional<Territory> placeAt =
              territory.flatMap(t -> placeFor(type, t, player, map));
          if (placeAt.isPresent()) {
            final List<Unit> free = type.create(freePerApc.getInt(type), player);
            change.add(ChangeFactory.addUnits(placeAt.get(), free));
            lines.add("Free " + MyFormatter.unitsToText(free) + " in " + placeAt.get().getName());
          }
        }
      }
    }
    if (attachment != null && !wait.equals(attachment.getApcFreeUnitsWait())) {
      change.add(ChangeFactory.attachmentPropertyChange(attachment, wait, "apcFreeUnitsWait"));
    }
    if (!lines.isEmpty()) {
      bridge.getHistoryWriter().startEvent(player.getName() + " SPC bonuses");
      lines.forEach(line -> bridge.getHistoryWriter().addChildToEvent(line));
    }
    if (!change.isEmpty()) {
      bridge.addChange(change);
    }
  }

  /** The tech's free units this nation can have now (each needs its unit's tech, if any). */
  private static IntegerMap<UnitType> unlockedFreeUnits(
      final TechAdvance tech, final GamePlayer player) {
    final IntegerMap<UnitType> free = new IntegerMap<>();
    for (final UnitType type : TechAbilityAttachment.get(tech).getApcFreeUnits().keySet()) {
      final Optional<String> required =
          ApcAttachment.get(type).flatMap(ApcAttachment::getApcRequiredTech);
      if (required.isEmpty() || hasTech(player, required.get())) {
        free.put(type, TechAbilityAttachment.get(tech).getApcFreeUnits().getInt(type));
      }
    }
    return free;
  }

  private static String freeUnitsKey(final Unit apc, final TechAdvance tech) {
    return apc.getId() + ":" + tech.getName();
  }

  /** "free Panther this turn" or "free Panther in 1 turn". */
  private static String freeUnitsStatus(final IntegerMap<UnitType> free, final int turns) {
    final String units =
        free.keySet().stream()
            .map(t -> (free.getInt(t) == 1 ? "" : free.getInt(t) + " ") + UnitNames.of(t))
            .sorted()
            .collect(java.util.stream.Collectors.joining(", "));
    return "free "
        + units
        + (turns == 0 ? " this turn" : " in " + turns + MyFormatter.pluralize(" turn", turns));
  }

  /**
   * When each tech with free units next gives them at this APC, e.g. "free Panther in 1 turn". Only
   * for techs whose free units come less often than every turn.
   */
  public static List<String> freeUnitsStatus(final Unit apc, final GamePlayer player) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    final List<String> status = new ArrayList<>();
    for (final TechAdvance tech :
        TechTracker.getCurrentTechAdvances(player, player.getData().getTechnologyFrontier())) {
      final TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
      if (ability == null || ability.getApcFreeUnitsTurns() <= 1) {
        continue;
      }
      final IntegerMap<UnitType> free = unlockedFreeUnits(tech, player);
      if (!free.isEmpty()) {
        final int turns =
            attachment == null
                ? 0
                : attachment.getApcFreeUnitsWait().getInt(freeUnitsKey(apc, tech));
        status.add(freeUnitsStatus(free, turns));
      }
    }
    return status;
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
    return seaZonesNextTo(apcTerritory, map).stream().findFirst();
  }

  /** Request 62: as above, but ships go to the sea zone chosen for this SPC (see shipsGoTo). */
  private static Optional<Territory> placeFor(
      final UnitType type,
      final Territory apcTerritory,
      final Unit apc,
      final GamePlayer player,
      final GameMap map) {
    if (!type.getUnitAttachment().isSea()) {
      return Optional.of(apcTerritory);
    }
    return shipsGoTo(apc, apcTerritory, player, map);
  }
}
