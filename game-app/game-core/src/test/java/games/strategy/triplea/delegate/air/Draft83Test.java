package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Draft 83 (request 39's XML): the request's section 4 table, each nation with every tech it can
 * research. Values are per strike round: dive bomber against land, dive, torpedo and medium bomber
 * against surface ships.
 */
class Draft83Test {
  private GameData data;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    Assumptions.assumeTrue(
        data.getProperties().get("FS Air Battle Priority", "").contains("Russians")
            && data.getUnitTypeList()
                .getUnitType("diveBomber")
                .flatMap(games.strategy.triplea.attachments.AirUnitAttachment::get)
                .map(a -> a.getLandBonus() > 0)
                .orElse(false),
        "the installed draft has no request 39 bonuses");
  }

  @ParameterizedTest
  @CsvSource({
    "Germans, 6, 3, 4, 5",
    // request 43 (draft 84): the two Americas differ
    "AmericansPacific, 4, 4, 6, 4",
    "Americans, 5, 3, 4, 3",
    "Japanese, 4, 3, 6, 4",
    // draft 118: Italy gets Picchiatelli (the Stuka tech, dive bomber land bonus 2)
    "Italians, 5, 3, 5, 3",
    "British, 4, 3, 5, 3",
    "Russians, 3, 2, 4, 3"
  })
  void fullResearchGivesTheRequestsTable(
      final String nation,
      final int diveLand,
      final int diveShips,
      final int torpedoShips,
      final int mediumShips) {
    final GamePlayer p = data.getPlayerList().getPlayerId(nation);
    final List<TechAdvance> all = TechAdvance.getTechAdvances(data.getTechnologyFrontier(), p);
    for (final TechAdvance tech : all) {
      TechTracker.addAdvance(p, newDelegateBridge(p), tech);
    }
    data.getTechTracker().clearCache();
    assertThat(nation + " dive vs land", power(p, "diveBomber", TargetKind.LAND), is(diveLand));
    assertThat(
        nation + " dive vs ships", power(p, "diveBomber", TargetKind.SURFACE), is(diveShips));
    assertThat(
        nation + " torpedo vs ships",
        power(p, "torpedoBomber", TargetKind.SURFACE),
        is(torpedoShips));
    assertThat(
        nation + " medium vs ships", power(p, "mediumBomber", TargetKind.SURFACE), is(mediumShips));
  }

  private int power(final GamePlayer p, final String type, final TargetKind kind) {
    final Unit u = data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, p).get(0);
    return AirCombat.strikePower(u, kind);
  }
}
