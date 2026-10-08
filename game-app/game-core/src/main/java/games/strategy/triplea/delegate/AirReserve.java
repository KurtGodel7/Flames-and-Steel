package games.strategy.triplea.delegate;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.formatter.MyFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/** Flames and Steel: aircraft outside the air zones (e.g. newly placed) go to the reserve. */
@UtilityClass
public class AirReserve {

  /**
   * Moves all of the player's aircraft that are on the map (not in an air zone) to the aircraft
   * reserve. Does nothing on maps without an air reserve zone.
   */
  public static void sendAircraftToReserve(final IDelegateBridge bridge, final GamePlayer player) {
    final Optional<Territory> reserve = AirZoneAttachment.getReserve(bridge.getData().getMap());
    if (reserve.isEmpty()) {
      return;
    }
    final CompositeChange change = new CompositeChange();
    final List<Unit> moved = new ArrayList<>();
    for (final Territory territory : bridge.getData().getMap().getTerritories()) {
      if (AirZoneAttachment.isAirZone(territory)) {
        continue;
      }
      final Collection<Unit> aircraft =
          territory.getMatches(Matches.unitIsAir().and(Matches.unitIsOwnedBy(player)));
      if (aircraft.isEmpty()) {
        continue;
      }
      for (final Unit unit : aircraft) {
        if (unit.getTransportedBy() != null) {
          // carriers no longer hold aircraft; they only supply capacity to air zones
          change.add(
              ChangeFactory.unitPropertyChange(unit, null, Unit.PropertyName.TRANSPORTED_BY));
        }
      }
      change.add(ChangeFactory.moveUnits(territory, reserve.get(), aircraft));
      moved.addAll(aircraft);
    }
    if (moved.isEmpty()) {
      return;
    }
    bridge
        .getHistoryWriter()
        .startEvent(
            player.getName()
                + " send "
                + MyFormatter.unitsToTextNoOwner(moved)
                + " to "
                + reserve.get().getName(),
            moved);
    bridge.addChange(change);
  }
}
