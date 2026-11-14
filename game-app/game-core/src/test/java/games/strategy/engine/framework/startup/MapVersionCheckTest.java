package games.strategy.engine.framework.startup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Request 68: a client's map copy must match the host's. */
final class MapVersionCheckTest {
  @Test
  void readsTheInfoVersion() {
    assertThat(
        MapVersionCheck.versionIn(
            "<?xml version='1.0'?>\n<game>\n  <info name=\"Flames and Steel\" version=\"1.1.8\"/>"),
        is(Optional.of("1.1.8")));
    assertThat(MapVersionCheck.versionIn("<game><info name='x'/></game>"), is(Optional.empty()));
  }

  @Test
  void sameVersionJoins() {
    assertThat(MapVersionCheck.problem("1.1.8", "1.1.8"), is(Optional.empty()));
  }

  @Test
  void differentVersionSaysWhatTheHostHas() {
    final String problem = MapVersionCheck.problem("1.1.8", "1.1.7").orElseThrow();
    assertThat(problem, containsString("host has map version 1.1.8"));
    assertThat(problem, containsString("this computer has version 1.1.7"));
  }

  @Test
  void missingMapSaysSo() {
    assertThat(
        MapVersionCheck.problem("1.1.8", null).orElseThrow(), containsString("no copy of the map"));
  }
}
