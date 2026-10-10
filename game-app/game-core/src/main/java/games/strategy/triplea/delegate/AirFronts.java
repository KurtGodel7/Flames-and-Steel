package games.strategy.triplea.delegate;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.formatter.MyFormatter;
import java.util.Collection;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel: aircraft live only in the air boxes. Aircraft that appear on the map (bought
 * and placed at a factory, or built by an APC) go to the front whose coverage includes the
 * territory or sea zone they appeared in.
 */
@UtilityClass
public class AirFronts {

  /** Moves all of the player's aircraft that are on the map into their fronts. */
  public static void sendAircraftToFronts(final IDelegateBridge bridge, final GamePlayer player) {
    final var map = bridge.getData().getMap();
    if (AirZoneAttachment.getAirZones(map).isEmpty()) {
      return;
    }
    for (final Territory territory : map.getTerritories()) {
      if (AirZoneAttachment.isAirZone(territory)) {
        continue;
      }
      final Collection<Unit> aircraft =
          territory.getMatches(Matches.unitIsAir().and(Matches.unitIsOwnedBy(player)));
      if (aircraft.isEmpty()) {
        continue;
      }
      final Optional<Territory> front = AirZoneAttachment.getFrontOf(map, territory);
      if (front.isEmpty()) {
        bridge
            .getHistoryWriter()
            .startEvent(
                MyFormatter.unitsToTextNoOwner(aircraft)
                    + " in "
                    + territory.getName()
                    + " stay: no air front covers it",
                aircraft);
        continue;
      }
      final CompositeChange change = new CompositeChange();
      for (final Unit unit : aircraft) {
        if (unit.getTransportedBy() != null) {
          // carriers no longer hold aircraft; they only supply capacity to fronts
          change.add(
              ChangeFactory.unitPropertyChange(unit, null, Unit.PropertyName.TRANSPORTED_BY));
        }
      }
      change.add(ChangeFactory.moveUnits(territory, front.get(), aircraft));
      bridge
          .getHistoryWriter()
          .startEvent(
              player.getName()
                  + " send "
                  + MyFormatter.unitsToTextNoOwner(aircraft)
                  + " from "
                  + territory.getName()
                  + " to "
                  + front.get().getName(),
              aircraft);
      bridge.addChange(change);
    }
  }
}
