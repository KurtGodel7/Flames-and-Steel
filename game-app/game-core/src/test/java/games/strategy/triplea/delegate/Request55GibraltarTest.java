package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.battleDelegate;
import static games.strategy.triplea.delegate.GameDataTestUtil.move;
import static games.strategy.triplea.delegate.GameDataTestUtil.moveDelegate;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static games.strategy.triplea.delegate.MockDelegateBridge.whenGetRandom;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.history.IDelegateHistoryWriter;
import games.strategy.triplea.delegate.air.BasedMap;
import games.strategy.triplea.delegate.battle.AmphibiousLanding;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Request 55 on the installed map (draft 103 start): Italy's I1 landing at Gibraltar. The Italian
 * ships bombard in round 1, the landed units hold fire in round 1 and can be taken as casualties,
 * the British fire in round 1, and the landed units fire from round 2. Writes the battle's history
 * lines to build/request55-gibraltar-log.txt. Skipped when the map has no {@value
 * AmphibiousLanding#EXEMPT_PROPERTY} property.
 */
class Request55GibraltarTest extends games.strategy.triplea.settings.AbstractClientSettingTestCase {
  private final List<String> log = new ArrayList<>();
  private final List<String> rolls = new ArrayList<>();

  @Test
  void italianLandingAtGibraltar() throws Exception {
    final GameData data = BasedMap.load();
    Assumptions.assumeTrue(
        AmphibiousLanding.applies(data.getProperties()), "map predates request 55's property");
    final GamePlayer italians = BasedMap.p(data, "Italians");
    final Territory naples = BasedMap.t(data, "Naples");
    final Territory tyrrhenian = BasedMap.t(data, "Sea Zone 79 Tyrrhenian Sea");
    final Territory westernMed = BasedMap.t(data, "Sea Zone 70 Western Mediterranean");
    final Territory alboran = BasedMap.t(data, "Sea Zone 57 Alboran Sea");
    final Territory gibraltar = BasedMap.t(data, "Gibraltar");

    final IDelegateBridge bridge = newDelegateBridge(italians);
    when(bridge.getHistoryWriter()).thenReturn(recordingWriter());
    advanceToStep(bridge, "italianCombatMove");
    moveDelegate(data).setDelegateBridgeAndPlayer(bridge);
    moveDelegate(data).start();

    // load Naples' infantry, sail with the battle fleet, and land at Gibraltar
    final List<Unit> landing =
        naples
            .getUnitCollection()
            .getMatches(Matches.unitIsOwnedBy(italians).and(isType("infantry")));
    final Route load = new Route(naples, tyrrhenian);
    moveDelegate(data)
        .performMove(
            new games.strategy.engine.data.MoveDescription(
                landing,
                load,
                games.strategy.triplea.util.TransportUtils.mapTransports(
                    load,
                    landing,
                    tyrrhenian.getUnitCollection().getMatches(Matches.unitIsSeaTransport()))))
        .ifPresent(
            error -> {
              throw new IllegalStateException("Illegal move: " + error);
            });
    final List<Unit> fleet =
        tyrrhenian.getUnitCollection().getMatches(Matches.unitIsOwnedBy(italians));
    fleet.removeIf(isType("submarine"));
    move(fleet, new Route(tyrrhenian, westernMed, alboran));
    move(landing, new Route(alboran, gibraltar));
    moveDelegate(data).end();
    assertThat(landing.stream().allMatch(Unit::getWasAmphibious), is(true));

    advanceToStep(bridge, "italianBattle");
    when(bridge.getRemotePlayer().selectBombardingTerritory(any(), any(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              final Collection<Territory> offered = invocation.getArgument(2);
              return offered.contains(gibraltar) ? gibraltar : null;
            });
    when(bridge.getRemotePlayer().selectShoreBombard(any())).thenReturn(true);
    givenFirstUnitsChosenAsCasualties(bridge);
    // first round ("round 2" in the dice text): the British hit with their first die, the
    // bombardment's low-luck die misses; second round: the Italians hit with everything
    whenGetRandom(bridge)
        .thenAnswer(
            invocation -> {
              final int count = invocation.getArgument(1);
              final GamePlayer player = invocation.getArgument(2);
              final String annotation = invocation.getArgument(4);
              rolls.add(player.getName() + " " + count + ": " + annotation);
              final int[] values = new int[count];
              Arrays.fill(values, 5);
              if (annotation.contains(", round 3") && player == italians) {
                Arrays.fill(values, 0);
              } else if (annotation.contains(", round 2")
                  && !annotation.contains("Bombardment")
                  && count > 0) {
                values[0] = 0;
              }
              return values;
            });
    battleDelegate(data).setDelegateBridgeAndPlayer(bridge);
    // the only battle, so the battle delegate fights it at once
    battleDelegate(data).start();

    Files.writeString(
        Path.of("build", "request55-gibraltar-log.txt"),
        String.join(System.lineSeparator(), log)
            + System.lineSeparator()
            + System.lineSeparator()
            + "Dice requests:"
            + System.lineSeparator()
            + String.join(System.lineSeparator(), rolls));

    // The dice lines say "round 2" for the first combat round (stock TripleA's numbering).
    final String first = ", round 2";
    final String second = ", round 3";
    // round 1: the ships bombard, the British fire, the landed infantry hold fire
    assertThat(log, hasItem(startsWith("    " + AmphibiousLanding.logLine(landing.size()))));
    assertThat(anyLine("Italians roll dice for", "battleship", first), is(true));
    assertThat(anyLine("British roll dice for", "", first), is(true));
    assertThat(anyLine("Italians roll dice for", "infantry", first), is(false));
    // a landed infantry was lost in round 1
    assertThat(anyLine("1 infantry owned by the Italians lost", "", ""), is(true));
    // round 2: the landed infantry fire
    assertThat(anyLine("Italians roll dice for", "infantry", second), is(true));
    assertThat(log.stream().filter(l -> l.contains("hold fire")).count(), is(1L));
  }

  private boolean anyLine(final String start, final String contains, final String round) {
    return log.stream()
        .map(String::trim)
        .anyMatch(l -> l.startsWith(start) && l.contains(contains) && l.contains(round));
  }

  private static java.util.function.Predicate<Unit> isType(final String type) {
    return u -> u.getType().getName().equals(type);
  }

  private IDelegateHistoryWriter recordingWriter() {
    return new IDelegateHistoryWriter() {
      @Override
      public void startEvent(final String eventName) {
        log.add(eventName);
      }

      @Override
      public void startEvent(final String eventName, final Object renderingData) {
        log.add(eventName);
      }

      @Override
      public void addChildToEvent(final String child) {
        log.add("    " + child);
      }

      @Override
      public void addChildToEvent(final String child, final Object renderingData) {
        log.add("    " + child + describe(renderingData));
      }
    };
  }

  private static String describe(final Object renderingData) {
    if (renderingData instanceof Collection<?> units
        && !units.isEmpty()
        && units.iterator().next() instanceof Unit) {
      return "  ["
          + units.stream()
              .map(u -> ((Unit) u).getType().getName())
              .collect(Collectors.groupingBy(n -> n, java.util.TreeMap::new, Collectors.counting()))
              .entrySet()
              .stream()
              .map(e -> e.getValue() + " " + e.getKey())
              .collect(Collectors.joining(", "))
          + "]";
    }
    return "";
  }

  private static void givenFirstUnitsChosenAsCasualties(final IDelegateBridge bridge) {
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
            invocation -> {
              final Collection<Unit> selectFrom = invocation.getArgument(0);
              final int count = invocation.getArgument(2);
              final games.strategy.triplea.delegate.data.CasualtyList defaults =
                  invocation.getArgument(10);
              if (defaults != null
                  && defaults.getKilled().size() + defaults.getDamaged().size() > 0) {
                return new CasualtyDetails(defaults, true);
              }
              return new CasualtyDetails(
                  new ArrayList<>(selectFrom).subList(0, Math.min(count, selectFrom.size())),
                  List.of(),
                  false);
            });
  }
}
