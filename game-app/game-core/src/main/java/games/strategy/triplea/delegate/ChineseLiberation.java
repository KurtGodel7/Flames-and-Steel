package games.strategy.triplea.delegate;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.MunitionsAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.formatter.MyFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel: Chinese liberation (request 28 part C) and the Chinese garrison (request 29
 * part B).
 *
 * <ul>
 *   <li>A land territory with the territory option "chineseHomeland" that one of the liberators
 *       (game property {@value #LIBERATORS}, default British and Americans) takes from the Axis
 *       passes to China, whoever its original owner and whoever holds China's capital. Taken by
 *       the USSR it stays Soviet (the normal rule).
 *   <li>At the end of China's turn, separately in each Chinese-owned land territory holding
 *       liberator units: the first {@value #CONSCRIPTS} infantry-type units (any unit whose cost
 *       includes Manpower) each become one Chinese conscript. A pool adds the PU cost of every
 *       liberator unit there, plus {@value #MP_VALUE} PUs per MP of the infantry-type units beyond
 *       those. The pool buys the garrison {@value #GARRISON} in order at normal prices, stopping at
 *       the first unit it cannot afford. Only after a complete garrison does China get the rest
 *       times {@value #PERCENT} percent, rounded down once. The liberator units are removed. China
 *       gets no MP, and damaged units count at full value.
 * </ul>
 */
@UtilityClass
public class ChineseLiberation {
  public static final String CHINA = "Chinese";
  public static final String LIBERATORS = "FS Chinese Liberators";
  public static final String CONSCRIPTS = "FS Chinese Garrison Conscripts";
  public static final String GARRISON = "FS Chinese Garrison Units";
  public static final String MP_VALUE = "FS Chinese Sale MP Value";
  public static final String PERCENT = "FS Chinese Sale Percent";
  private static final String DEFAULT_GARRISON =
      "antiTankGun:antiTankGun:antiTankGun:heavyGun:trench:trench";
  private static final String CONSCRIPT = "conscript";
  private static final String MANPOWER = "Manpower";

  /**
   * Whether the territory is historically Chinese: tagged "chineseHomeland" in the XML. Until a
   * map tags any territory at all, the land whose original owner is China counts (draft 77, which
   * already gives Manchuria China as original owner and Taiwan Japan).
   */
  public static boolean isChineseHomeland(final Territory territory) {
    if (territory.isWater()) {
      return false;
    }
    final Optional<TerritoryAttachment> attachment = TerritoryAttachment.get(territory);
    if (attachment.isEmpty()) {
      return false;
    }
    if (attachment.get().getChineseHomeland()) {
      return true;
    }
    final GameState data = territory.getData();
    final boolean mapTagsHomeland =
        data.getMap().getTerritories().stream()
            .anyMatch(
                t ->
                    TerritoryAttachment.get(t)
                        .map(TerritoryAttachment::getChineseHomeland)
                        .orElse(false));
    return !mapTagsHomeland
        && attachment
            .flatMap(TerritoryAttachment::getOriginalOwner)
            .map(o -> CHINA.equals(o.getName()))
            .orElse(false);
  }

  public static Optional<GamePlayer> china(final GameState data) {
    return Optional.ofNullable(data.getPlayerList().getPlayerId(CHINA));
  }

  /** The nations whose captures of Chinese land go to China ("British:Americans"). */
  public static List<String> liberators(final GameState data) {
    final String list = data.getProperties().get(LIBERATORS, "British:AmericansPacific:Americans");
    return list.isBlank() ? List.of() : List.of(list.split(":"));
  }

  public static boolean isLiberator(final GamePlayer player) {
    return liberators(player.getData()).contains(player.getName());
  }

  /** China, when this capture of Chinese land by an ally of China must pass to it. */
  public static Optional<GamePlayer> newOwnerOnCapture(
      final Territory territory, final GamePlayer captor) {
    if (!isChineseHomeland(territory) || !isLiberator(captor)) {
      return Optional.empty();
    }
    return china(captor.getData())
        .filter(c -> !c.equals(captor))
        .filter(c -> captor.getData().getRelationshipTracker().isAllied(c, captor));
  }

  /** "Hunan liberated: control passes to China." */
  public static String liberatedText(final Territory territory, final GamePlayer china) {
    return territory.getName() + " liberated: control passes to " + countryName(china) + ".";
  }

  private static String countryName(final GamePlayer player) {
    return CHINA.equals(player.getName()) ? "China" : player.getName();
  }

  /** The liberator units standing in Chinese-owned land, which become a Chinese garrison. */
  public static boolean willBeGarrison(final Unit unit, final Territory territory) {
    return !territory.isWater()
        && CHINA.equals(territory.getOwner().getName())
        && isLiberator(unit.getOwner())
        && !MunitionsAttachment.isMunitions(unit);
  }

  /** For a unit's tooltip: a line saying it will become a garrison, or "". */
  public static String garrisonNote(final Unit unit, final GameData data) {
    if (!isLiberator(unit.getOwner())) {
      return "";
    }
    for (final Territory territory : data.getMap().getTerritories()) {
      if (territory.getUnitCollection().contains(unit)) {
        return willBeGarrison(unit, territory)
            ? "Will become a Chinese garrison at the end of China's turn."
            : "";
      }
    }
    return "";
  }

  /** The rule that sets this unit's price: its owner's cheapest, else the map's "buy_" rule. */
  private static Optional<ProductionRule> priceRule(final Unit unit, final GameState data) {
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final ProductionFrontier frontier = unit.getOwner().getProductionFrontier();
    if (frontier != null) {
      final Optional<ProductionRule> own =
          frontier.getRules().stream()
              .filter(rule -> rule.getResults().getInt(unit.getType()) > 0)
              .min(Comparator.comparingInt(rule -> perUnit(rule, unit.getType(), pus)));
      if (own.isPresent()) {
        return own;
      }
    }
    return normalRule(unit.getType(), data);
  }

  /** The map's standard rule for a unit type ("buy_tank"), else the dearest rule that makes it. */
  private static Optional<ProductionRule> normalRule(final UnitType type, final GameState data) {
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final ProductionRule named = data.getProductionRuleList().getProductionRule("buy_" + type.getName());
    if (named != null && named.getResults().getInt(type) > 0) {
      return Optional.of(named);
    }
    return data.getProductionRuleList().getProductionRules().stream()
        .filter(rule -> rule.getResults().getInt(type) > 0)
        .max(Comparator.comparingInt(rule -> perUnit(rule, type, pus)));
  }

  private static int perUnit(final ProductionRule rule, final UnitType type, final Resource r) {
    return rule.getCosts().getInt(r) / Math.max(1, rule.getResults().getInt(type));
  }

  /** The unit's MP cost, 0 if it is not an infantry-type unit. */
  static int manpower(final Unit unit, final GameState data) {
    final Optional<Resource> mp = data.getResourceList().getResourceOptional(MANPOWER);
    if (mp.isEmpty()) {
      return 0;
    }
    return priceRule(unit, data).map(rule -> perUnit(rule, unit.getType(), mp.get())).orElse(0);
  }

  /** The unit's PU cost (full value, damaged or not). */
  static int puCost(final Unit unit, final GameState data) {
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    return priceRule(unit, data).map(rule -> perUnit(rule, unit.getType(), pus)).orElse(0);
  }

  /** The garrison list, in buying order ("FS Chinese Garrison Units", colon separated). */
  static List<UnitType> garrisonList(final GameState data) {
    final String list = data.getProperties().get(GARRISON, DEFAULT_GARRISON);
    final List<UnitType> result = new ArrayList<>();
    for (final String name : list.split("[:,]")) {
      final UnitType type = data.getUnitTypeList().getUnitType(name.trim()).orElse(null);
      if (type != null) {
        result.add(type);
      }
    }
    return result;
  }

  /** What one territory's liberator units turn into. */
  public record Garrison(
      int conscripts, IntegerMap<UnitType> units, int pool, int surplus, int cash, boolean full) {}

  /** Works out the garrison for the liberator units in one territory (request 29 part B). */
  public static Garrison garrisonFor(final Collection<Unit> units, final GameState data) {
    final int conscriptLimit = data.getProperties().get(CONSCRIPTS, 4);
    final int mpValue = data.getProperties().get(MP_VALUE, 2);
    final int percent = data.getProperties().get(PERCENT, 50);
    int conscripts = 0;
    int pool = 0;
    for (final Unit unit : units) {
      pool += puCost(unit, data);
      final int mp = manpower(unit, data);
      if (mp > 0) {
        if (conscripts < conscriptLimit) {
          conscripts++;
        } else {
          pool += mp * mpValue;
        }
      }
    }
    final IntegerMap<UnitType> bought = new IntegerMap<>();
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    int left = pool;
    boolean full = true;
    for (final UnitType type : garrisonList(data)) {
      final int price = normalRule(type, data).map(rule -> perUnit(rule, type, pus)).orElse(0);
      if (price > left) {
        full = false;
        break;
      }
      left -= price;
      bought.add(type, 1);
    }
    final int cash = full ? left * percent / 100 : 0;
    return new Garrison(conscripts, bought, pool, full ? left : 0, cash, full);
  }

  /**
   * The manpower China collects from a Chinese homeland territory it owns, beyond its own
   * triggers: the territory's map value (its per-territory manpower triggers) when those triggers
   * pay another nation, e.g. Japan for Manchuria. 0 when China's own triggers pay it already, the
   * territory has no manpower, or China does not own it.
   */
  public static int homelandManpower(final Territory territory, final GamePlayer china) {
    if (!CHINA.equals(china.getName())
        || !territory.isOwnedBy(china)
        || !isChineseHomeland(territory)) {
      return 0;
    }
    final java.util.Map<GamePlayer, Integer> receivers =
        games.strategy.triplea.attachments.TriggerAttachment.getEachOwnershipResource(
            territory, MANPOWER);
    if (receivers.isEmpty() || receivers.containsKey(china)) {
      return 0;
    }
    return receivers.values().stream().mapToInt(Integer::intValue).max().orElse(0);
  }

  /**
   * At the end of China's turn: China collects the map manpower of the Chinese homeland
   * territories it owns that its own triggers don't pay (request 28). A Soviet-held one pays
   * nobody: only China gets this exception.
   */
  public static String collectHomelandManpower(
      final IDelegateBridge bridge, final GamePlayer player) {
    if (!CHINA.equals(player.getName())) {
      return "";
    }
    final GameState data = bridge.getData();
    final Optional<Resource> manpower = data.getResourceList().getResourceOptional(MANPOWER);
    if (manpower.isEmpty()) {
      return "";
    }
    int total = 0;
    final List<String> parts = new ArrayList<>();
    for (final Territory territory : data.getMap().getTerritoriesOwnedBy(player)) {
      final int mp = homelandManpower(territory, player);
      if (mp > 0) {
        total += mp;
        parts.add(territory.getName() + " " + mp);
      }
    }
    if (total == 0) {
      return "";
    }
    final String text =
        "China collects "
            + total
            + " Manpower from liberated Chinese land ("
            + String.join(", ", parts)
            + ")";
    bridge.getHistoryWriter().startEvent(text);
    bridge.addChange(ChangeFactory.changeResourcesChange(player, manpower.get(), total));
    return text + "<br />";
  }

  /**
   * At the end of China's turn: in each Chinese-owned land territory, the liberators' units become
   * a Chinese garrison (and PUs, after a complete garrison).
   */
  public static String garrisonWesternUnits(final IDelegateBridge bridge, final GamePlayer player) {
    if (!CHINA.equals(player.getName())) {
      return "";
    }
    final GameState data = bridge.getData();
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final Optional<UnitType> conscriptType = data.getUnitTypeList().getUnitType(CONSCRIPT);
    final StringBuilder report = new StringBuilder();
    for (final Territory territory : data.getMap().getTerritoriesOwnedBy(player)) {
      if (territory.isWater()) {
        continue;
      }
      final List<Unit> units = territory.getMatches(u -> willBeGarrison(u, territory));
      if (units.isEmpty()) {
        continue;
      }
      final Garrison garrison = garrisonFor(units, data);
      final List<Unit> created = new ArrayList<>();
      if (garrison.conscripts() > 0 && conscriptType.isPresent()) {
        created.addAll(conscriptType.get().create(garrison.conscripts(), player));
      }
      for (final UnitType type : garrison.units().keySet()) {
        created.addAll(type.create(garrison.units().getInt(type), player));
      }
      final CompositeChange change = new CompositeChange();
      change.add(ChangeFactory.removeUnits(territory, units));
      if (!created.isEmpty()) {
        change.add(ChangeFactory.addUnits(territory, created));
      }
      if (garrison.cash() > 0) {
        change.add(ChangeFactory.changeResourcesChange(player, pus, garrison.cash()));
      }
      final String text = garrisonText(territory, created, garrison.cash(), units);
      bridge.getHistoryWriter().startEvent(text, created.isEmpty() ? units : created);
      bridge
          .getHistoryWriter()
          .addChildToEvent(poolText(units, garrison, data), new ArrayList<>(units));
      bridge.addChange(change);
      report.append(text).append("<br />");
    }
    return report.toString();
  }

  /**
   * "Hunan: 4 conscripts, 3 anti-tank guns, 1 heavy gun, 2 trenches and 6 PUs pass to China from 9
   * American units."
   */
  static String garrisonText(
      final Territory territory,
      final Collection<Unit> created,
      final int cash,
      final Collection<Unit> removed) {
    final List<String> parts = new ArrayList<>();
    if (!created.isEmpty()) {
      parts.add(plainUnits(created));
    }
    if (cash > 0) {
      parts.add(cash + MyFormatter.pluralize(" PU", cash));
    }
    final String what = parts.isEmpty() ? "nothing" : String.join(" and ", parts);
    final String owners =
        String.join(
            " and ",
            removed.stream().map(u -> OwnTransports.adjective(u.getOwner())).distinct().toList());
    return territory.getName()
        + ": "
        + what
        + (parts.isEmpty() ? " passes" : " pass")
        + " to China from "
        + removed.size()
        + " "
        + owners
        + (removed.size() == 1 ? " unit." : " units.");
  }

  /** "4 conscripts, 3 anti-tank guns, 1 heavy gun, 2 trenches", in the order created. */
  static String plainUnits(final Collection<Unit> units) {
    final java.util.Map<UnitType, Integer> counts = new java.util.LinkedHashMap<>();
    units.forEach(u -> counts.merge(u.getType(), 1, Integer::sum));
    final List<String> parts = new ArrayList<>();
    counts.forEach(
        (type, n) -> {
          final String name = games.strategy.triplea.delegate.air.AirCombat.unitName(type);
          final String plural =
              name.endsWith("ch") || name.endsWith("s") || name.endsWith("x")
                  ? name + "es"
                  : name + "s";
          parts.add(n + " " + (n == 1 ? name : plural));
        });
    return String.join(", ", parts);
  }

  /** "Pool 34 PUs (units 30, 2 MP beyond the conscripts × 2); garrison 22; surplus 12 × 50% = 6." */
  private static String poolText(
      final Collection<Unit> units, final Garrison garrison, final GameState data) {
    final int unitValue = units.stream().mapToInt(u -> puCost(u, data)).sum();
    final int extraMp = garrison.pool() - unitValue;
    final StringBuilder sb =
        new StringBuilder("Pool " + garrison.pool() + MyFormatter.pluralize(" PU", garrison.pool()))
            .append(" (units ")
            .append(unitValue);
    if (extraMp > 0) {
      sb.append(", MP beyond the conscripts ").append(extraMp);
    }
    sb.append(")");
    if (garrison.full()) {
      sb.append("; full garrison ")
          .append(garrison.pool() - garrison.surplus())
          .append("; surplus ")
          .append(garrison.surplus())
          .append(" × ")
          .append(data.getProperties().get(PERCENT, 50))
          .append("%, rounded down: ")
          .append(garrison.cash())
          .append(MyFormatter.pluralize(" PU", garrison.cash()));
    } else {
      sb.append("; garrison not complete, so no PUs");
    }
    return sb.toString();
  }
}
