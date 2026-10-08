package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.delegate.IDelegateBridge;
import java.util.Collection;

/** Test access to the package-private air landing check. */
public final class AirThatCantLandUtilAccess {
  private AirThatCantLandUtilAccess() {}

  public static Collection<Territory> territoriesWhereAirCantLand(
      final IDelegateBridge bridge, final GamePlayer player) {
    return new AirThatCantLandUtil(bridge).getTerritoriesWhereAirCantLand(player);
  }
}
