package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 16 on the installed map: the round 1 air phase runs step by step to
 * Germany's research, and the Italian strikes on Tobruk chosen in Italy's target step are fought in
 * the strike step before Germany's turn begins. Skipped when the map is not installed.
 */
class AirPhaseRoundOneTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
  }

  @Test
  void italianStrikesOnTobrukAreFoughtBeforeGermanResearch() {
    final GamePlayer italians = data.getPlayerList().getPlayerId("Italians");
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    final Territory med = data.getMap().getTerritoryOrThrow("Air - Mediterranean");
    final Territory tobruk = data.getMap().getTerritoryOrThrow("Tobruk");
    final int britishInTobruk =
        tobruk.getUnitCollection().countMatches(Matches.unitIsOwnedBy(british));

    String reached = null;
    for (int i = 0; i < 200; i++) {
      final GameStep step = data.getSequence().getStep();
      if (step.getName().equals("germanResearch")) {
        reached = step.getName();
        break;
      }
      final GamePlayer player =
          step.getPlayerId() == null
              ? data.getPlayerList().getPlayerId("Germans")
              : step.getPlayerId();
      final IDelegate delegate = step.getDelegate();
      delegate.setDelegateBridgeAndPlayer(bridge(player));
      delegate.start();
      if (step.getName().equals("italianAirTargets")) {
        // the strikes James chose: a torpedo bomber and a dive bomber from the Mediterranean
        for (final String type : List.of("torpedoBomber", "diveBomber")) {
          if (AirCombat.getBombers(italians, med).stream()
              .noneMatch(u -> u.getType().getName().equals(type))) {
            addTo(med, unitType(type).create(1, italians));
          }
        }
        final Target target = new Target(tobruk, TargetKind.LAND);
        final String error =
            ((AirCombatDelegate) delegate)
                .assignTargets(
                    List.of(
                        new TargetAssignment(med, unitType("torpedoBomber"), 1, target),
                        new TargetAssignment(med, unitType("diveBomber"), 1, target)));
        assertThat(error, is(nullValue()));
      }
      delegate.end();
      data.getSequence().next();
    }

    assertThat(reached, is("germanResearch"));
    assertThat(
        tobruk.getUnitCollection().countMatches(Matches.unitIsOwnedBy(british)),
        is(lessThan(britishInTobruk)));
  }

  private UnitType unitType(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  /** Remainder dice always hit; players take the offered casualties. */
  private static IDelegateBridge bridge(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(0);
    when(bridge.getRandom(anyInt(), anyInt(), any(), any(), anyString()))
        .thenAnswer(i -> new int[i.<Integer>getArgument(1)]);
    when(bridge
            .getRemotePlayer()
            .selectCasualties(
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean()))
        .thenAnswer(
            invocation -> new CasualtyDetails(invocation.<CasualtyList>getArgument(10), true));
    return bridge;
  }
}
