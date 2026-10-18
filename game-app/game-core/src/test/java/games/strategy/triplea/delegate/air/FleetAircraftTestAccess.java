package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.UnitType;
import org.triplea.java.collections.IntegerMap;

/** Lets tests in other packages reach {@link FleetAircraft}'s package-private suggestion. */
public final class FleetAircraftTestAccess {
  private FleetAircraftTestAccess() {}

  public static IntegerMap<UnitType> suggest(
      final IntegerMap<UnitType> available, final int capacity, final int fleetCapacity) {
    return FleetAircraft.suggest(available, capacity, fleetCapacity);
  }
}
