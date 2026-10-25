package games.strategy.engine.framework;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.DelegateExecutionManager;
import games.strategy.triplea.delegate.InitializationDelegate;
import games.strategy.triplea.formatter.PlayerNames;
import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/** Request 36 on the installed map: rolling, crash-safe autosaves. */
class RollingAutoSaveTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  @TempDir Path saves;
  private GameData data;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    ClientSetting.saveGamesFolderPath.setValue(saves);
  }

  @AfterEach
  void tearDown() {
    ClientSetting.resetPreferences();
  }

  private void loadMap() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(
        newDelegateBridge(data.getPlayerList().getPlayerId("Germans")));
    start.start();
    start.end();
  }

  private List<String> files(final Path folder) throws Exception {
    try (Stream<Path> s = Files.list(folder)) {
      return s.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  @Test
  void aWholeRoundSavesAtEverySavePoint() throws Exception {
    loadMap();
    ClientSetting.fsAutoSaveRolling.setValue(1000);
    final DelegateExecutionManager manager = new DelegateExecutionManager();
    GamePlayer lastNation = null;
    final List<String> due = new ArrayList<>();
    final long start = System.nanoTime();
    // one whole round, ending at the start of round 2
    while (data.getSequence().getRound() == 1) {
      final GameStep finished = data.getSequence().getStep();
      final boolean newRound = data.getSequence().next();
      RollingAutoSave.afterHostStep(data, manager, finished, newRound, lastNation);
      if (finished.getPlayerId() != null && !finished.getPlayerId().isNull()) {
        lastNation = finished.getPlayerId();
        due.add(finished.getName());
      }
    }
    RollingAutoSave.awaitWrites();
    final long totalMillis = (System.nanoTime() - start) / 1_000_000;
    final List<String> names = files(RollingAutoSave.hostFolder());
    System.out.println(
        "AUTOSAVE "
            + names.size()
            + " files for one round in "
            + totalMillis
            + " ms; last save: read "
            + RollingAutoSave.lastTimings()[0]
            + " ms, write "
            + RollingAutoSave.lastTimings()[1]
            + " ms, "
            + RollingAutoSave.lastTimings()[2] / 1024
            + " KB");
    assertThat(names.contains("FS_R02_start.tsvg"), is(true));
    // every nation's turn has its own save, by its display name
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (due.stream().anyMatch(n -> data.getSequence().getSteps().stream()
          .anyMatch(s -> s.getName().equals(n) && p.equals(s.getPlayerId())))) {
        final String nation = PlayerNames.display(p).replaceAll("[^A-Za-z0-9]+", "-");
        assertThat(
            p.getName(), names.stream().anyMatch(n -> n.contains("_" + nation + "_")), is(true));
      }
    }
    // research and the air phase are among them
    assertThat(names.stream().anyMatch(n -> n.contains("Research")), is(true));
    assertThat(
        names.stream().anyMatch(n -> n.contains("Air") || n.contains("Bomber")), is(true));
    // at least one save for every step a nation played
    assertThat(names.size() >= due.size(), is(true));
    // the game is read on the game thread; the file is written in the background
    assertThat(RollingAutoSave.lastTimings()[0], lessThan(3000L));
  }

  @Test
  void twentyFiveStepSavesLeaveTwentyAndTheRoundSaves() throws Exception {
    final Path folder = RollingAutoSave.hostFolder();
    Files.createDirectories(folder);
    for (int i = 1; i <= 25; i++) {
      RollingAutoSave.write(
          new byte[] {1}, folder.resolve(String.format("FS_R01_%03d_Germans_Step.tsvg", i)));
    }
    RollingAutoSave.write(new byte[] {1}, folder.resolve("FS_R01_start.tsvg"));
    RollingAutoSave.write(new byte[] {1}, folder.resolve("FS_R02_start.tsvg"));
    RollingAutoSave.prune(folder, 20);
    final List<String> names = files(folder);
    assertThat(names.size(), is(22));
    assertThat(names.contains("FS_R01_001_Germans_Step.tsvg"), is(false));
    assertThat(names.contains("FS_R01_006_Germans_Step.tsvg"), is(true));
    assertThat(names.contains("FS_R01_start.tsvg"), is(true));
  }

  @Test
  void aCrashWhileWritingLeavesThePreviousSaveLoadable() throws Exception {
    loadMap();
    final Path file = RollingAutoSave.hostFolder().resolve("FS_R01_000_All_Test.tsvg");
    final byte[] good = org.triplea.io.IoUtils.writeToMemory(out -> GameDataManager.saveGame(out, data));
    RollingAutoSave.write(good, file);
    try {
      RollingAutoSave.write(new byte[good.length], file, good.length / 2);
    } catch (final java.io.IOException expected) {
      // the crash
    }
    assertThat(GameDataManager.loadGame(file).isPresent(), is(true));
    // no half-written file is left behind
    assertThat(
        files(RollingAutoSave.hostFolder()).stream().noneMatch(n -> n.endsWith(".tmp")), is(true));
  }

  @Test
  void aClientCopyLoadsAndIsMadeOnlyWhereNothingIsSecret() throws Exception {
    loadMap();
    // a client saves at a nation's turn start, never inside the air phase
    final var sequence = data.getSequence();
    GamePlayer last = null;
    for (int i = 0; i < 400 && sequence.getRound() == 1; i++) {
      final GameStep step = sequence.getStep();
      if (RollingAutoSave.isAirPhase(step)) {
        assertThat(step.getName(), RollingAutoSave.isTurnStart(step, last), is(false));
      }
      if (step.getPlayerId() != null && !step.getPlayerId().isNull()) {
        last = step.getPlayerId();
      }
      sequence.next();
    }
    // at round 2's start the client writes its copy, and it loads
    RollingAutoSave.afterClientStep(data, true, last);
    RollingAutoSave.awaitWrites();
    final Path copy = RollingAutoSave.clientFolder().resolve("FS_R02_start.tsvg");
    assertThat(Files.exists(copy), is(true));
    final GameData loaded = GameDataManager.loadGame(copy).orElseThrow();
    assertThat(loaded.getSequence().getRound(), is(2));
  }

  @Test
  void anInterruptedGameIsSeenAndANormalEndIsNot() {
    RollingAutoSave.markRunning();
    assertThat(RollingAutoSave.wasInterrupted(), is(true));
    RollingAutoSave.markStopped();
    assertThat(RollingAutoSave.wasInterrupted(), is(false));
    assertThat(
        RollingAutoSave.describe(Path.of("FS_R04_037_America-Atlantic_Combat-Move.tsvg")),
        is("Round 4, America Atlantic, Combat Move"));
    assertThat(RollingAutoSave.describe(Path.of("FS_R04_start.tsvg")), is("Round 4, start"));
  }
}
