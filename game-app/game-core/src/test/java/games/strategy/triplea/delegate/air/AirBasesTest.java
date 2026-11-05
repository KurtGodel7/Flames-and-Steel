package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.air.BasedMap.add;
import static games.strategy.triplea.delegate.air.BasedMap.t;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.air.AirBases.Flight;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 38 section 10: the range rule (out + back to a landing spot within the range) and
 * carriers extending it, with every flying aircraft sure of its own spot (James, 25 October).
 */
class AirBasesTest {
  private GameData data;
  private GamePlayer germans;
  private GamePlayer british;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    germans = BasedMap.p(data, "Germans");
    british = BasedMap.p(data, "British");
    // a clean board: all land British (at war with Germany), no German carriers
    BasedMap.allLandTo(data, british);
    BasedMap.clear(data, germans, "carrier", "fleetCarrier");
  }

  @Test
  void theMapHasTheAirborneTerritoryAndNoBoxes() {
    assertThat(AirBases.usesBases(data), is(true));
    final Territory airborne = AirBases.airborne(data).orElseThrow();
    assertThat(data.getMap().getNeighbors(airborne).isEmpty(), is(true));
    assertThat(
        data.getMap().getTerritories().stream()
            .anyMatch(t -> t.getName().startsWith("Air - ") && !t.equals(airborne)),
        is(false));
  }

  @Test
  void rangeEightTargetFiveLandingThreeIsLegal() {
    // draft 102: Kiev is 5 from Hamburg (Lwow is now 4), Cracow 3
    final Territory kiev = t(data, "Kiev");
    final Territory hamburg = t(data, "Hamburg");
    kiev.setOwner(germans);
    t(data, "Cracow").setOwner(germans);
    final Unit medium = add(data, kiev, germans, "mediumBomber", 1).get(0);
    assertThat(AirRange.range(medium.getType(), germans), is(8));
    assertThat(AirBases.distance(germans, kiev, hamburg, 20), is(OptionalInt.of(5)));
    assertThat(AirBases.distance(germans, hamburg, t(data, "Cracow"), 20), is(OptionalInt.of(3)));
    assertThat(AirBases.flightProblem(medium, kiev, hamburg), is(Optional.empty()));
    assertThat(
        AirBases.landingNote(new Flight(medium, hamburg, 3)), is("lands at Cracow, 0 left"));
  }

  @Test
  void rangeEightTargetFiveLandingFourIsRefusedWithTheReason() {
    final Territory kiev = t(data, "Kiev");
    final Territory hamburg = t(data, "Hamburg");
    kiev.setOwner(germans);
    t(data, "Radom").setOwner(germans);
    final Unit medium = add(data, kiev, germans, "mediumBomber", 1).get(0);
    assertThat(AirBases.distance(germans, hamburg, t(data, "Radom"), 20), is(OptionalInt.of(4)));
    final String problem = AirBases.flightProblem(medium, kiev, hamburg).orElseThrow();
    assertThat(problem, containsString("Nowhere to land within 3 steps of Hamburg"));
    assertThat(problem, containsString("nearest: Radom, 4 steps"));
  }

  @Test
  void targetBeyondTheRangeIsRefused() {
    final Territory kiev = t(data, "Kiev");
    kiev.setOwner(germans);
    final Unit fighter = add(data, kiev, germans, "fighter", 1).get(0);
    final String problem =
        AirBases.flightProblem(fighter, kiev, t(data, "Hamburg")).orElseThrow();
    assertThat(problem, is("Hamburg is 5 steps from Kiev; fighters fly 3."));
  }

  /** Request 38 test 2: range 4, target 3 away, a carrier that can move 2 toward it. */
  @Test
  void carrierExtendsTheRange() {
    final Territory base = t(data, "Central Provinces");
    final Territory target = t(data, "Sea Zone 112 Arabian Sea - Southeast");
    final Territory gulf = t(data, "Sea Zone 104 Persian Gulf");
    base.setOwner(germans);
    final Unit jet = add(data, base, germans, "earlyJet", 1).get(0);
    assertThat(AirRange.range(jet.getType(), germans), is(4));
    assertThat(AirBases.distance(germans, base, target, 20), is(OptionalInt.of(3)));
    // no carrier: only 1 left over the target and no land within 1
    assertThat(AirBases.flightProblem(jet, base, target).isPresent(), is(true));
    final Unit carrier = add(data, gulf, germans, "fleetCarrier", 1).get(0);
    assertThat(
        AirBases.carrierReach(carrier, gulf).contains(t(data, "Sea Zone 117 Arabian Sea - Northeast")),
        is(true));
    assertThat(AirBases.flightProblem(jet, base, target), is(Optional.empty()));
    assertThat(
        AirBases.landingNote(new Flight(jet, target, 1)),
        containsString("fleet carrier from Sea Zone 104 Persian Gulf can reach"));
    assertThat(AirBases.landingProblem(germans, List.of(new Flight(jet, target, 1))), is(Optional.empty()));
  }

  /** James: if the only spot is a carrier for 4, only 4 may go; losses don't count. */
  @Test
  void everyFlyingAircraftNeedsItsOwnCarrierSpot() {
    final Territory base = t(data, "Central Provinces");
    final Territory target = t(data, "Sea Zone 112 Arabian Sea - Southeast");
    final Territory gulf = t(data, "Sea Zone 104 Persian Gulf");
    base.setOwner(germans);
    add(data, gulf, germans, "fleetCarrier", 1);
    final List<Flight> flights = new ArrayList<>();
    for (final Unit jet : add(data, base, germans, "earlyJet", 5)) {
      flights.add(new Flight(jet, target, 1));
    }
    assertThat(AirBases.landingProblem(germans, flights.subList(0, 4)), is(Optional.empty()));
    assertThat(
        AirBases.landingProblem(germans, flights).orElseThrow(),
        containsString("5 aircraft need a carrier spot"));
  }

  /** Aircraft staying on the carrier take its room. */
  @Test
  void aircraftStayingOnTheCarrierTakeItsRoom() {
    final Territory base = t(data, "Central Provinces");
    final Territory target = t(data, "Sea Zone 112 Arabian Sea - Southeast");
    final Territory gulf = t(data, "Sea Zone 104 Persian Gulf");
    base.setOwner(germans);
    add(data, gulf, germans, "fleetCarrier", 1);
    add(data, gulf, germans, "fighter", 3);
    final List<Flight> flights = new ArrayList<>();
    for (final Unit jet : add(data, base, germans, "earlyJet", 2)) {
      flights.add(new Flight(jet, target, 1));
    }
    assertThat(AirBases.landingProblem(germans, flights.subList(0, 1)), is(Optional.empty()));
    assertThat(AirBases.landingProblem(germans, flights).isPresent(), is(true));
  }

  /** A carrier ends the round in one sea zone: two flights wanting different zones need two. */
  @Test
  void oneCarrierCannotBeInTwoPlaces() {
    final Territory base = t(data, "Central Provinces");
    final Territory gulf = t(data, "Sea Zone 104 Persian Gulf");
    base.setOwner(germans);
    final Unit carrier = add(data, gulf, germans, "fleetCarrier", 1).get(0);
    final List<Territory> reach = new ArrayList<>(AirBases.carrierReach(carrier, gulf));
    // two zones the carrier can reach that are 2 apart or more
    Territory a = null;
    Territory b = null;
    for (final Territory x : reach) {
      for (final Territory y : reach) {
        if (AirBases.distance(germans, x, y, 1).isEmpty()) {
          a = x;
          b = y;
        }
      }
    }
    final List<Unit> jets = add(data, base, germans, "earlyJet", 2);
    // range left 0 over each zone: each must land exactly there
    final Flight one = new Flight(jets.get(0), a, 0);
    final Flight two = new Flight(jets.get(1), b, 0);
    assertThat(AirBases.landingProblem(germans, List.of(one)), is(Optional.empty()));
    assertThat(AirBases.landingProblem(germans, List.of(one, two)).isPresent(), is(true));
    add(data, gulf, germans, "fleetCarrier", 1);
    assertThat(AirBases.landingProblem(germans, List.of(one, two)), is(Optional.empty()));
  }

  @Test
  void landingRulesComeFromTheRelationships() {
    final GamePlayer russians = BasedMap.p(data, "Russians");
    final GamePlayer americans = BasedMap.p(data, "Americans");
    final GamePlayer chinese = BasedMap.p(data, "Chinese");
    final Territory moscow = t(data, "Moscow");
    moscow.setOwner(russians);
    final Territory london = t(data, "London");
    assertThat(AirBases.isLandBase(russians, moscow), is(true));
    assertThat(AirBases.isLandBase(british, moscow), is(false)); // Soviet land
    assertThat(AirBases.isLandBase(americans, london), is(true)); // Western Allied land
    assertThat(AirBases.isLandBase(russians, london), is(false));
    assertThat(AirBases.isLandBase(chinese, london), is(false));
    final Territory berlin = t(data, "Berlin");
    berlin.setOwner(germans);
    assertThat(AirBases.isLandBase(BasedMap.p(data, "Japanese"), berlin), is(false));
    assertThat(AirBases.isLandBase(BasedMap.p(data, "Italians"), berlin), is(true));
  }
}
