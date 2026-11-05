package games.strategy.triplea.delegate;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.IAttachment;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionFrontier;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.ApcAttachment;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TriggerAttachment;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 53 item 1): the unit types a nation can ever have, for Edit mode's
 * "Add Units": what its production frontiers offer (its own, and any a trigger switches it to),
 * the purchase rules its techs unlock (trigger "productionRule" entries on those frontiers), units
 * from its SPCs and free-unit techs, and anything it already owns on the map. Never another
 * nation's special units.
 */
@UtilityClass
public class NationUnits {

  /**
   * Each unit type the nation can have, with the purchase rule that gives it (its current
   * frontier's first, else one a trigger adds to one of its frontiers), or empty if it is never
   * bought (free units, SPC output, units it only starts with).
   */
  public static Map<UnitType, Optional<ProductionRule>> of(final GamePlayer player) {
    final GameData data = player.getData();
    final Map<UnitType, Optional<ProductionRule>> result = new LinkedHashMap<>();
    final Set<ProductionFrontier> frontiers = frontiers(player);
    // 1. the frontier it buys from now, first: its prices are the ones in force
    if (player.getProductionFrontier() != null) {
      player.getProductionFrontier().getRules().forEach(r -> addRule(result, r));
    }
    // 2. the other frontiers it can have, and the rules triggers add to any of them
    frontiers.forEach(f -> f.getRules().forEach(r -> addRule(result, r)));
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      for (final IAttachment a : p.getAttachments().values()) {
        if (!(a instanceof TriggerAttachment t)) {
          continue;
        }
        for (final String entry : t.productionRuleChanges()) {
          final String[] parts = entry.split(":", 2);
          if (parts.length < 2 || parts[1].startsWith("-")) {
            continue;
          }
          final ProductionFrontier f =
              data.getProductionFrontierList().getProductionFrontier(parts[0]);
          final ProductionRule r = data.getProductionRuleList().getProductionRule(parts[1]);
          if (f != null && r != null && frontiers.contains(f)) {
            addRule(result, r);
          }
        }
      }
    }
    // 3. free units from its techs, and units its SPCs build with a tech it can research
    final Set<String> techNames = new LinkedHashSet<>();
    for (final TechAdvance t :
        player.isNull() ? java.util.List.<TechAdvance>of() : player.getTechnologyFrontierList().getAdvances()) {
      techNames.add(t.getName());
      final TechAbilityAttachment ability = TechAbilityAttachment.get(t);
      if (ability != null) {
        ability.getApcFreeUnits().keySet().forEach(u -> result.putIfAbsent(u, Optional.empty()));
      }
    }
    for (final UnitType type : data.getUnitTypeList().getAllUnitTypes()) {
      ApcAttachment.get(type)
          .flatMap(ApcAttachment::getApcRequiredTech)
          .filter(techNames::contains)
          .ifPresent(tech -> result.putIfAbsent(type, Optional.empty()));
    }
    // 4. whatever it already owns somewhere (starting units, captured or converted units)
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection()) {
        if (u.isOwnedBy(player)) {
          result.putIfAbsent(u.getType(), Optional.empty());
        }
      }
    }
    return result;
  }

  /** The nation's current frontier and every frontier a trigger may switch it to. */
  private static Set<ProductionFrontier> frontiers(final GamePlayer player) {
    final Set<ProductionFrontier> result = new LinkedHashSet<>();
    if (player.getProductionFrontier() != null) {
      result.add(player.getProductionFrontier());
    }
    for (final GamePlayer p : player.getData().getPlayerList().getPlayers()) {
      for (final IAttachment a : p.getAttachments().values()) {
        if (a instanceof TriggerAttachment t
            && t.frontierChange().isPresent()
            && t.triggerPlayers().contains(player)) {
          result.add(t.frontierChange().get());
        }
      }
    }
    return result;
  }

  private static void addRule(
      final Map<UnitType, Optional<ProductionRule>> result, final ProductionRule rule) {
    for (final NamedAttachable r : rule.getResults().keySet()) {
      if (r instanceof UnitType type
          && rule.getResults().size() == 1
          && result.getOrDefault(type, Optional.empty()).isEmpty()) {
        result.put(type, Optional.of(rule));
      } else if (r instanceof UnitType type) {
        result.putIfAbsent(type, Optional.empty());
      }
    }
  }

  /**
   * Whether a unit of this type may be added to the territory in Edit mode: on land, land units
   * and aircraft; at sea, ships and aircraft that can sit on a carrier.
   */
  public static boolean fits(final UnitType type, final Territory territory) {
    final var ua = type.getUnitAttachment();
    if (territory.isWater()) {
      return ua.isSea() || (ua.isAir() && ua.getCarrierCost() > 0);
    }
    return !ua.isSea();
  }
}
