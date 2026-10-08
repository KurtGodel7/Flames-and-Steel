package games.strategy.triplea.delegate;

import games.strategy.engine.data.CompositeChange;
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
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.formatter.MyFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel: at the end of a player's turn, allied units standing in that player's land
 * territories are removed and the player receives their value in PUs. Enabled by the
 * playerAttachment option "sellsAlliedUnitsAtEndTurn".
 *
 * <p>A unit's value is the PU cost of the production rule that buys it in its owner's current
 * production frontier, plus, for each other resource in that cost, the amount times the PU value
 * set by "alliedUnitSaleResourceValue" (e.g. Manpower worth 2 PUs).
 */
@UtilityClass
public class AlliedUnitSale {

  /** Sells allied units in the player's territories; returns text for the end turn report. */
  public static String sellAlliedUnits(final IDelegateBridge bridge, final GamePlayer player) {
    final PlayerAttachment playerAttachment = PlayerAttachment.get(player);
    if (playerAttachment == null || !playerAttachment.getSellsAlliedUnitsAtEndTurn()) {
      return "";
    }
    final GameState data = bridge.getData();
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final IntegerMap<Resource> resourceValues = playerAttachment.getAlliedUnitSaleResourceValue();
    final CompositeChange change = new CompositeChange();
    final List<String> details = new ArrayList<>();
    int total = 0;
    for (final Territory territory : data.getMap().getTerritoriesOwnedBy(player)) {
      if (territory.isWater()) {
        continue;
      }
      final Collection<Unit> units =
          territory.getMatches(
              Matches.unitIsOwnedBy(player).negate().and(Matches.alliedUnit(player)));
      if (units.isEmpty()) {
        continue;
      }
      int value = 0;
      for (final Unit unit : units) {
        value += getUnitValue(unit, pus, resourceValues);
      }
      total += value;
      change.add(ChangeFactory.removeUnits(territory, units));
      details.add(
          MyFormatter.unitsToText(units)
              + " in "
              + territory.getName()
              + " for "
              + value
              + MyFormatter.pluralize(" PU", value));
    }
    if (change.isEmpty()) {
      return "";
    }
    change.add(ChangeFactory.changeResourcesChange(player, pus, total));
    final String text =
        player.getName()
            + " receive "
            + total
            + MyFormatter.pluralize(" PU", total)
            + " for allied units on their territory";
    bridge.getHistoryWriter().startEvent(text);
    details.forEach(detail -> bridge.getHistoryWriter().addChildToEvent("Sold " + detail));
    bridge.addChange(change);
    return text + "<br />";
  }

  static int getUnitValue(
      final Unit unit, final Resource pus, final IntegerMap<Resource> resourceValues) {
    final ProductionFrontier frontier = unit.getOwner().getProductionFrontier();
    if (frontier == null) {
      return 0;
    }
    final UnitType unitType = unit.getType();
    return frontier.getRules().stream()
        .filter(rule -> rule.getResults().getInt(unitType) > 0)
        .mapToInt(rule -> getRuleValuePerUnit(rule, unitType, pus, resourceValues))
        .min()
        .orElse(0);
  }

  private static int getRuleValuePerUnit(
      final ProductionRule rule,
      final UnitType unitType,
      final Resource pus,
      final IntegerMap<Resource> resourceValues) {
    int value = 0;
    for (final Resource resource : rule.getCosts().keySet()) {
      final int amount = rule.getCosts().getInt(resource);
      value += resource.equals(pus) ? amount : amount * resourceValues.getInt(resource);
    }
    return value / rule.getResults().getInt(unitType);
  }
}
