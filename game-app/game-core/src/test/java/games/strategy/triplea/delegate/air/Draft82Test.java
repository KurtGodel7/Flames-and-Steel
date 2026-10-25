package games.strategy.triplea.delegate.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Draft 82, the first draft with aircraft on the map (request 38): its starting position. */
class Draft82Test {
  private GameData data;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    Assumptions.assumeTrue(
        data.getMap().getTerritoryOrNull(AirBases.DEFAULT_AIRBORNE) != null,
        "the installed draft has no Air - Airborne territory");
  }

  @Test
  void theAirborneTerritoryIsAHiddenSeaZoneThatStartsEmpty() {
    final Territory airborne = AirBases.airborne(data).orElseThrow();
    assertThat(airborne.isWater(), is(true));
    assertThat(data.getMap().getNeighbors(airborne).isEmpty(), is(true));
    assertThat(airborne.getUnits().isEmpty(), is(true));
  }

  /** Every starting aircraft is on its own (or allowed allied) land, or on its own carriers' room. */
  @Test
  void everyStartingAircraftIsOnALegalBase() {
    final List<String> wrong = new ArrayList<>();
    int aircraft = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection().getMatches(AirBases::isAircraft)) {
        aircraft++;
        if (!t.isWater() && !AirBases.isLandBase(u.getOwner(), t)) {
          wrong.add(u.getType().getName() + " (" + u.getOwner().getName() + ") on " + t.getName());
        }
      }
    }
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      final Map<GamePlayer, Map<Territory, List<Unit>>> stranded = AirBaseLoss.stranded(data, true);
      stranded
          .getOrDefault(p, Map.of())
          .forEach(
              (t, units) ->
                  wrong.add(units.size() + " " + p.getName() + " aircraft without carrier room in " + t.getName()));
    }
    assertThat(aircraft > 0, is(true));
    assertThat(wrong, is(empty()));
  }
}
