package games.strategy.triplea.delegate.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.air.AirStage.Stage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 56 on the installed map: impassable territories named in "FS Impassable Air Flyover" (the
 * Pripet Marshes) are open sky for aircraft, but never a base or a target; the Pamirs still block
 * them; adding the Algerian Sahara to the list opens it.
 */
class Request56Test {
  private static final String PRIPET = "Pripet Marshes - Impassable";
  private static final String PAMIRS = "Pamirs - Impassable";
  private static final String SAHARA = "Algerian Sahara";

  private GameData data;
  private GamePlayer germans;
  private GamePlayer italians;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    germans = BasedMap.p(data, "Germans");
    italians = BasedMap.p(data, "Italians");
  }

  private void flyover(final String value) {
    data.getProperties().set(AirBases.IMPASSABLE_AIR_FLYOVER, value);
  }

  private int steps(final GamePlayer player, final String from, final String to) {
    return AirBases.distance(player, BasedMap.t(data, from), BasedMap.t(data, to), 20).orElse(99);
  }

  @Test
  void theMapListsThePripetMarshes() {
    assertThat(AirBases.impassableFlyover(data), is(java.util.Set.of(PRIPET)));
  }

  @Test
  void aircraftCrossThePripetMarshesOnlyWhenListed() {
    flyover(PRIPET);
    assertThat(AirBases.flyOver(germans).test(BasedMap.t(data, PRIPET)), is(true));
    // Bialystok - Pripet Marshes - Gomel: two steps, the marshes count as one
    assertThat(steps(germans, "Bialystok", "Gomel"), is(2));
    assertThat(
        AirBases.path(germans, BasedMap.t(data, "Bialystok"), BasedMap.t(data, "Gomel"), 2)
            .orElseThrow()
            .get(1)
            .getName(),
        is(PRIPET));

    flyover(null);
    assertThat(AirBases.flyOver(germans).test(BasedMap.t(data, PRIPET)), is(false));
    assertThat(steps(germans, "Bialystok", "Gomel"), greaterThan(2));
  }

  @Test
  void aGermanBomberReachesATargetBeyondTheMarshesOnlyWhenListed() {
    final Territory bialystok = BasedMap.t(data, "Bialystok");
    final Territory gomel = BasedMap.t(data, "Gomel");
    bialystok.setOwner(germans);
    // a German landing field beside Gomel: Bialystok - marshes - Gomel - Kiev is 3 steps
    BasedMap.t(data, "Kiev").setOwner(germans);
    BasedMap.add(data, gomel, BasedMap.p(data, "Russians"), "infantry", 1);
    final UnitType bomber = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
    BasedMap.add(data, bialystok, germans, "diveBomber", 1);
    assertThat(AirRange.range(bomber, germans), is(3));

    flyover(PRIPET);
    assertThat(targets(bialystok, bomber), hasItem(gomel));
    // without the property the way round is 3 steps out, too far to come back
    flyover(null);
    assertThat(targets(bialystok, bomber), not(hasItem(gomel)));
  }

  private List<Territory> targets(final Territory base, final UnitType type) {
    return AirStage.candidates(germans, base, type, Stage.BOMBERS, Map.of()).stream()
        .map(AirCombat.Target::territory)
        .toList();
  }

  @Test
  void listedMarshesAreNeverABaseNorATarget() {
    flyover(PRIPET);
    final Territory pripet = BasedMap.t(data, PRIPET);
    // even if someone owned it and had units there
    pripet.setOwner(germans);
    BasedMap.add(data, pripet, BasedMap.p(data, "Russians"), "infantry", 2);
    assertThat(AirBases.isLandBase(germans, pripet), is(false));
    assertThat(AirCombat.targetsIn(germans, pripet), is(empty()));
    final Territory bialystok = BasedMap.t(data, "Bialystok");
    bialystok.setOwner(germans);
    final UnitType bomber = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
    BasedMap.add(data, bialystok, germans, "diveBomber", 1);
    assertThat(targets(bialystok, bomber), not(hasItem(pripet)));
  }

  @Test
  void thePamirsStillBlockAircraft() {
    flyover(PRIPET);
    final GamePlayer british = BasedMap.p(data, "British");
    assertThat(AirBases.flyOver(british).test(BasedMap.t(data, PAMIRS)), is(false));
    assertThat(
        AirBases.path(british, BasedMap.t(data, "Delhi"), BasedMap.t(data, "Kashgar"), 20)
            .map(p -> p.stream().map(Territory::getName).toList())
            .orElse(List.of()),
        not(hasItem(PAMIRS)));
  }

  @Test
  void listingTheSaharaLetsItalianAircraftCrossIt() {
    flyover(PRIPET + ":" + SAHARA);
    assertThat(steps(italians, "Tripoli", "Mauritania"), is(2));

    flyover(PRIPET);
    assertThat(steps(italians, "Tripoli", "Mauritania"), greaterThan(2));
  }
}
