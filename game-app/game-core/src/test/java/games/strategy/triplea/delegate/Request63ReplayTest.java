package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.delegate.data.PlaceableUnits;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Request 63: James's round 6 (his save of 6 Nov, copied to TripleA-fork/test-saves): Germany's
 * real purchase and placement order, undos included. Danzig (factory built during the game) and
 * Berlin offer an SPC throughout; East Prussia (not on that game's SPC list) never does.
 */
class Request63ReplayTest {
  GameData data;
  GamePlayer g;
  PlaceDelegate place;

  List<Unit> take(final String type, final int n) {
    return g.getUnitCollection().getMatches(u -> u.getType().getName().equals(type)).subList(0, n);
  }

  void show(final String label) {
    for (final String t : List.of("Danzig", "East Prussia", "Berlin", "West Prussia", "Hamburg")) {
      final PlaceableUnits p =
          place.getPlaceableUnits(g.getUnits(), data.getMap().getTerritoryOrThrow(t));
      final boolean apc = p.getUnits().stream().anyMatch(u -> u.getType().getName().equals("apc"));
      if (!t.equals("West Prussia")) {
        assertThat(label + " " + t, apc, is(!t.equals("East Prussia")));
      }
    }
  }

  void put(final String type, final int n, final String at) {
    assertThat(
        at,
        place.placeUnits(take(type, n), data.getMap().getTerritoryOrThrow(at)).orElse(""),
        is(""));
  }

  @Test
  void replay() {
    final Path save =
        Path.of(
            System.getProperty("user.home"),
            "Documents/TripleA-fork/test-saves/request63_R06_start.tsvg");
    org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(save), "no save");
    data = GameDataManager.loadGame(save).orElseThrow();
    g = data.getPlayerList().getPlayerId("Germans");
    final var seq = data.getSequence();
    for (int i = 0; i < 500 && !seq.getStep().getName().equals("germanPlace"); i++) {
      seq.next();
    }
    final Map<String, Integer> bought = new java.util.LinkedHashMap<>();
    bought.put("antiTankGun", 18);
    bought.put("apc", 2);
    bought.put("factory", 1);
    bought.put("fieldGun", 3);
    bought.put("fighter", 1);
    bought.put("heavyGun", 2);
    bought.put("infantry", 16);
    bought.put("sturmtruppen", 3);
    bought.put("tank", 4);
    bought.put("trench", 3);
    bought.forEach(
        (t, n) ->
            g.getUnitCollection()
                .addAll(data.getUnitTypeList().getUnitTypeOrThrow(t).create(n, g)));
    place = (PlaceDelegate) data.getDelegate("place");
    place.setDelegateBridgeAndPlayer(newDelegateBridge(g));
    place.start();
    show("start");
    put("trench", 1, "Hamburg");
    put("trench", 1, "Denmark");
    put("trench", 1, "Oslo");
    show("after trenches");
    put("apc", 1, "Vitebsk");
    show("after Vitebsk SPC");
    place.undoMove(place.getMovesMade().size() - 1);
    show("after undo");
    put("factory", 1, "Vitebsk");
    show("after Vitebsk factory");
    put("apc", 1, "West Prussia");
    place.undoMove(place.getMovesMade().size() - 1);
    put("apc", 1, "West Prussia");
    show("after West Prussia SPC");
  }
}
