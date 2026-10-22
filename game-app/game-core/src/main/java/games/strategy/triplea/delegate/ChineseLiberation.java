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
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.MunitionsAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.formatter.MyFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 28 part C): Chinese liberation and the sale of Western units.
 *
 * <ul>
 *   <li>A land territory with the territory option "chineseHomeland" that one of the liberators
 *       (game property {@value #LIBERATORS}, default British and Americans) takes from the Axis
 *       passes to China, whoever its original owner and whoever holds China's capital. Taken by
 *       the USSR it stays Soviet (the normal rule).
 *   <li>At the end of China's turn every liberator unit in a Chinese-owned land territory is sold
 *       to China: each unit's PU cost, plus {@value #MP_BONUS} PUs (default 2) for a unit whose cost
 *       includes Manpower or any other non-PU resource, all added up, times {@value #PERCENT}
 *       percent (default 50), rounded down once. China gets PUs only. Damaged units sell at full
 *       value.
 * </ul>
 */
@UtilityClass
public class ChineseLiberation {
  public static final String CHINA = "Chinese";
  public static final String LIBERATORS = "FS Chinese Liberators";
  public static final String PERCENT = "FS Chinese Sale Percent";
  public static final String MP_BONUS = "FS Chinese Sale MP Bonus";
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
    final String list = data.getProperties().get(LIBERATORS, "British:Americans");
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

  /** The liberator units standing in Chinese-owned land, which China sells at its turn's end. */
  public static boolean willBeSold(final Unit unit, final Territory territory) {
    return !territory.isWater()
        && CHINA.equals(territory.getOwner().getName())
        && isLiberator(unit.getOwner())
        && !MunitionsAttachment.isMunitions(unit);
  }

  /** For a unit's tooltip: a line saying it will be sold, or "". */
  public static String saleNote(final Unit unit, final GameData data) {
    if (!isLiberator(unit.getOwner())) {
      return "";
    }
    for (final Territory territory : data.getMap().getTerritories()) {
      if (territory.getUnitCollection().contains(unit)) {
        return willBeSold(unit, territory)
            ? "Will be sold to China at the end of China's turn."
            : "";
      }
    }
    return "";
  }

  /**
   * The value of units before the sale percentage: each unit's PU cost, plus the MP bonus if its
   * cost includes a resource other than PUs.
   */
  public static int fullValue(final Collection<Unit> units, final GameState data) {
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int bonus = data.getProperties().get(MP_BONUS, 2);
    int value = 0;
    for (final Unit unit : units) {
      final Optional<ProductionRule> rule = cheapestRule(unit, pus);
      if (rule.isEmpty()) {
        continue;
      }
      final int perUnit = rule.get().getResults().getInt(unit.getType());
      value += rule.get().getCosts().getInt(pus) / Math.max(1, perUnit);
      if (rule.get().getCosts().keySet().stream()
          .anyMatch(r -> !r.equals(pus) && rule.get().getCosts().getInt(r) > 0)) {
        value += bonus;
      }
    }
    return value;
  }

  /** What China gets for these units: their full value times the percentage, rounded down. */
  public static int saleValue(final Collection<Unit> units, final GameState data) {
    final int percent = data.getProperties().get(PERCENT, 50);
    return fullValue(units, data) * percent / 100;
  }

  private static Optional<ProductionRule> cheapestRule(final Unit unit, final Resource pus) {
    final ProductionFrontier frontier = unit.getOwner().getProductionFrontier();
    if (frontier == null) {
      return Optional.empty();
    }
    return frontier.getRules().stream()
        .filter(rule -> rule.getResults().getInt(unit.getType()) > 0)
        .min(
            java.util.Comparator.comparingInt(
                rule -> rule.getCosts().getInt(pus) / rule.getResults().getInt(unit.getType())));
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

  /** At the end of China's turn: removes and sells the liberators' units on Chinese land. */
  public static String sellWesternUnits(final IDelegateBridge bridge, final GamePlayer player) {
    if (!CHINA.equals(player.getName())) {
      return "";
    }
    final GameState data = bridge.getData();
    final CompositeChange change = new CompositeChange();
    final List<Unit> sold = new ArrayList<>();
    final List<String> details = new ArrayList<>();
    final List<String> places = new ArrayList<>();
    for (final Territory territory : data.getMap().getTerritoriesOwnedBy(player)) {
      if (territory.isWater()) {
        continue;
      }
      final List<Unit> units = territory.getMatches(u -> willBeSold(u, territory));
      if (units.isEmpty()) {
        continue;
      }
      sold.addAll(units);
      places.add(territory.getName());
      change.add(ChangeFactory.removeUnits(territory, units));
      details.add(
          "Sold "
              + MyFormatter.unitsToText(units)
              + " in "
              + territory.getName()
              + " (full value "
              + fullValue(units, data)
              + MyFormatter.pluralize(" PU", fullValue(units, data))
              + ")");
    }
    if (sold.isEmpty()) {
      return "";
    }
    final int total = saleValue(sold, data);
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    change.add(ChangeFactory.changeResourcesChange(player, pus, total));
    final String owners =
        String.join(
            " and ",
            sold.stream().map(u -> u.getOwner().getName()).distinct().map(n -> n.replaceAll("s$", "")).toList());
    final String text =
        "China receives "
            + total
            + MyFormatter.pluralize(" PU", total)
            + " from the sale of "
            + sold.size()
            + " "
            + owners
            + (sold.size() == 1 ? " unit" : " units")
            + " in "
            + String.join(", ", places);
    bridge.getHistoryWriter().startEvent(text, sold);
    details.forEach(detail -> bridge.getHistoryWriter().addChildToEvent(detail));
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            "Full value "
                + fullValue(sold, data)
                + " × "
                + data.getProperties().get(PERCENT, 50)
                + "%, rounded down once: "
                + total
                + MyFormatter.pluralize(" PU", total));
    bridge.addChange(change);
    return text + "<br />";
  }
}
