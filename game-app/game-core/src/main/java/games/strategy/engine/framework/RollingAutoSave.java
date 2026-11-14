package games.strategy.engine.framework;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.delegate.DelegateExecutionManager;
import games.strategy.engine.framework.save.game.GameDataWriter;
import games.strategy.triplea.formatter.PlayerNames;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Flames and Steel (request 36): frequent autosaves that never overwrite each other.
 *
 * <ul>
 *   <li>The host saves when a round starts (kept for good: "FS_R04_start.tsvg") and, with per-step
 *       saves on, after every step a nation played, after every air phase step, and whenever a
 *       nation's turn begins. Each file is the game as it stands when the named step begins
 *       ("FS_R04_037_Japanese_Combat-Move.tsvg": round 4, step 37 of the round sequence, resume at
 *       Japan's combat move). The newest {@link ClientSetting#fsAutoSaveRolling} (20) are kept.
 *   <li>Each client keeps its own copy, at round starts and at nation turn starts outside the air
 *       phase, where no choice is secret and no step is half done; in "autoSave/network-copy".
 *   <li>The game is read into memory on the game thread (so the copy is consistent); writing the
 *       file, the rename and the clean-up run on a background thread. A save due while one is being
 *       written replaces any save still waiting (only the newest waits). The file is written to a
 *       temporary file and moved over the target, so a crash leaves the previous file.
 *   <li>Request 45: nothing is offered at start-up; the start screen's "Autosave" button lists the
 *       saves newest first ({@link #all}).
 * </ul>
 */
@Slf4j
public final class RollingAutoSave {
  public static final String PREFIX = "FS_R";
  private static final ExecutorService WRITER =
      Executors.newSingleThreadExecutor(
          r -> {
            final Thread t = new Thread(r, "autosave writer");
            t.setDaemon(true);
            return t;
          });
  // the newest save waiting for the writer; null when none waits
  private static final AtomicReference<Job> waiting = new AtomicReference<>();
  private static volatile Consumer<String> failureListener = message -> {};
  // how long the last save took to read and write, for the report
  private static volatile long lastSnapshotMillis;
  private static volatile long lastWriteMillis;
  private static volatile long lastBytes;

  private record Job(byte[] bytes, Path file, boolean rolling, Path folder) {}

  private RollingAutoSave() {}

  /** Request 36: "Autosave failed: disk full" goes here (the bottom bar). */
  public static void setFailureListener(final Consumer<String> listener) {
    failureListener = listener == null ? message -> {} : listener;
  }

  /** The host's autosave folder. */
  public static Path hostFolder() {
    return ClientSetting.saveGamesFolderPath.getValueOrThrow().resolve("autoSave");
  }

  /** A client's own copies. */
  public static Path clientFolder() {
    return hostFolder().resolve("network-copy");
  }

  // ---- when to save

  /** Whether the step is part of the air phase (its choices are secret until the reveal). */
  public static boolean isAirPhase(final @Nullable GameStep step) {
    if (step == null) {
      return false;
    }
    final String delegate = step.getDelegateName();
    return "airAllocation".equals(delegate)
        || "airCombat".equals(delegate)
        || "airStage".equals(delegate)
        || "airCarrierCheck".equals(delegate);
  }

  /** Whether {@code next} begins a nation's turn: a nation's step after another's. */
  public static boolean isTurnStart(
      final @Nullable GameStep next, final @Nullable GamePlayer previousNation) {
    if (next == null || isAirPhase(next)) {
      return false;
    }
    final GamePlayer p = next.getPlayerId();
    return p != null && !p.isNull() && !p.equals(previousNation);
  }

  /**
   * The host, after a step ended and the sequence moved on: saves as described above.
   *
   * @param finished the step that ended
   * @param newRound whether the sequence started a new round
   * @param previousNation the nation whose turn was running before {@code finished}'s successor
   */
  public static void afterHostStep(
      final GameData data,
      final DelegateExecutionManager executionManager,
      final GameStep finished,
      final boolean newRound,
      final @Nullable GamePlayer previousNation) {
    final GameStep next = data.getSequence().getStep();
    final boolean perStep = ClientSetting.fsAutoSavePerStep.getValue().orElse(true);
    if (newRound) {
      saveHost(data, executionManager, roundFile(data.getSequence().getRound()), false);
    }
    final boolean playedStep = finished.getPlayerId() != null && !finished.getPlayerId().isNull();
    final boolean due =
        isTurnStart(next, previousNation) || (perStep && (playedStep || isAirPhase(finished)));
    if (due) {
      saveHost(data, executionManager, stepFile(data, hostFolder()), true);
    }
  }

  /** A client, after its copy of the game moved to a new step: saves where nothing is secret. */
  public static void afterClientStep(
      final GameData data, final boolean newRound, final @Nullable GamePlayer previousNation) {
    final GameStep next = data.getSequence().getStep();
    if (newRound) {
      saveClient(data, clientFolder().resolve(roundFileName(data.getSequence().getRound())), false);
    } else if (isTurnStart(next, previousNation)) {
      saveClient(data, stepFile(data, clientFolder()), true);
    }
  }

  // ---- names

  static String roundFileName(final int round) {
    return String.format(
        Locale.ROOT, "%s%02d_start%s", PREFIX, round, GameDataFileUtils.getExtension());
  }

  private static Path roundFile(final int round) {
    return hostFolder().resolve(roundFileName(round));
  }

  /** "FS_R04_037_America-Atlantic_Combat-Move.tsvg" for the step the game is at. */
  static Path stepFile(final GameData data, final Path folder) {
    final GameStep step = data.getSequence().getStep();
    final GamePlayer p = step.getPlayerId();
    final String nation = p == null || p.isNull() ? "All" : PlayerNames.display(p);
    final String name =
        String.format(
            Locale.ROOT,
            "%s%02d_%03d_%s_%s%s",
            PREFIX,
            data.getSequence().getRound(),
            data.getSequence().getStepIndex(),
            clean(nation),
            clean(step.getDisplayName()),
            GameDataFileUtils.getExtension());
    return folder.resolve(name);
  }

  private static String clean(final String text) {
    return text.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
  }

  // ---- saving

  private static void saveHost(
      final GameData data,
      final DelegateExecutionManager executionManager,
      final Path file,
      final boolean rolling) {
    final long start = System.nanoTime();
    final byte[] bytes;
    try {
      bytes = GameDataWriter.writeToBytes(data, executionManager);
    } catch (final RuntimeException e) {
      fail(file, e);
      return;
    }
    lastSnapshotMillis = (System.nanoTime() - start) / 1_000_000;
    queue(new Job(bytes, file, rolling, file.getParent()));
  }

  private static void saveClient(final GameData data, final Path file, final boolean rolling) {
    final long start = System.nanoTime();
    final byte[] bytes;
    try {
      // saveGame takes the game's write lock itself; holding the read lock here would deadlock
      bytes = org.triplea.io.IoUtils.writeToMemory(out -> GameDataManager.saveGame(out, data));
    } catch (final IOException | RuntimeException e) {
      fail(file, e);
      return;
    }
    lastSnapshotMillis = (System.nanoTime() - start) / 1_000_000;
    queue(new Job(bytes, file, rolling, file.getParent()));
  }

  /** Never blocks: the newest save waits for the writer, an older waiting one is dropped. */
  private static void queue(final Job job) {
    if (waiting.getAndSet(job) == null) {
      WRITER.execute(RollingAutoSave::writeWaiting);
    }
  }

  private static void writeWaiting() {
    final Job job = waiting.getAndSet(null);
    if (job == null) {
      return;
    }
    final long start = System.nanoTime();
    try {
      write(job.bytes(), job.file());
      if (job.rolling()) {
        prune(job.folder(), ClientSetting.fsAutoSaveRolling.getValue().orElse(20));
      }
      lastWriteMillis = (System.nanoTime() - start) / 1_000_000;
      lastBytes = job.bytes().length;
    } catch (final IOException | RuntimeException e) {
      fail(job.file(), e);
    }
  }

  /** Writes to a temporary file in the same folder and moves it over the target. */
  static void write(final byte[] bytes, final Path file) throws IOException {
    write(bytes, file, Integer.MAX_VALUE);
  }

  /** For tests: as {@link #write(byte[], Path)}, failing after this many bytes (a crash). */
  static void write(final byte[] bytes, final Path file, final int failAfter) throws IOException {
    Files.createDirectories(file.getParent());
    final Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
    try {
      try (OutputStream out = Files.newOutputStream(temp)) {
        if (failAfter < bytes.length) {
          out.write(bytes, 0, failAfter);
          throw new IOException("simulated crash while writing");
        }
        out.write(bytes);
      }
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (final AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** Keeps the newest {@code keep} step saves (by name, which is play order); round saves stay. */
  static void prune(final Path folder, final int keep) throws IOException {
    final List<Path> rolling;
    try (Stream<Path> files = Files.list(folder)) {
      rolling =
          files
              .filter(f -> f.getFileName().toString().startsWith(PREFIX))
              .filter(f -> !f.getFileName().toString().contains("_start"))
              .filter(f -> f.getFileName().toString().endsWith(GameDataFileUtils.getExtension()))
              .sorted(Comparator.comparing(f -> f.getFileName().toString()))
              .toList();
    }
    for (int i = 0; i < rolling.size() - Math.max(1, keep); i++) {
      Files.deleteIfExists(rolling.get(i));
    }
  }

  private static void fail(final Path file, final Exception e) {
    log.warn("Autosave failed: " + file.toAbsolutePath(), e);
    final String reason =
        e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("space")
            ? "disk full"
            : e.getClass().getSimpleName();
    failureListener.accept("Autosave failed: " + reason);
  }

  /** For tests and the report: waits until the writer is idle. */
  public static void awaitWrites() {
    try {
      WRITER.submit(() -> {}).get();
      if (waiting.get() != null) {
        WRITER.submit(() -> {}).get();
      }
    } catch (final Exception e) {
      Thread.currentThread().interrupt();
    }
  }

  /** The last save's timings: milliseconds to read the game, to write the file, and its size. */
  public static long[] lastTimings() {
    return new long[] {lastSnapshotMillis, lastWriteMillis, lastBytes};
  }

  // ---- loading (request 45: only when the player asks; no start-up prompt)

  /** The newest autosave of this fork (host or client copy), if any. */
  public static Optional<Path> latest() {
    return all().stream().findFirst();
  }

  /** Every autosave of this fork (host and client copies), newest first. */
  public static List<Path> all() {
    try (Stream<Path> host = list(hostFolder());
        Stream<Path> client = list(clientFolder())) {
      return Stream.concat(host, client)
          .filter(f -> f.getFileName().toString().startsWith(PREFIX))
          .filter(f -> f.getFileName().toString().endsWith(GameDataFileUtils.getExtension()))
          .sorted(Comparator.comparingLong(RollingAutoSave::modified).reversed())
          .toList();
    } catch (final IOException | RuntimeException e) {
      return List.of();
    }
  }

  private static Stream<Path> list(final Path folder) throws IOException {
    return Files.isDirectory(folder) ? Files.list(folder) : Stream.empty();
  }

  public static long modified(final Path file) {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (final IOException e) {
      return 0;
    }
  }

  /**
   * "Round 4, America Atlantic, Combat Move" from "FS_R04_037_America-Atlantic_Combat-Move.tsvg";
   * "Round 4, start" for a round save.
   */
  public static String describe(final Path file) {
    final String name = file.getFileName().toString().replace(GameDataFileUtils.getExtension(), "");
    final String[] parts = name.split("_");
    if (parts.length < 3 || !parts[0].equals("FS") || !parts[1].startsWith("R")) {
      return file.getFileName().toString();
    }
    final String round = "Round " + Integer.parseInt(parts[1].substring(1));
    if (parts[2].equals("start")) {
      return round + ", start";
    }
    final StringBuilder sb = new StringBuilder(round);
    for (int i = 3; i < parts.length; i++) {
      sb.append(", ").append(parts[i].replace('-', ' '));
    }
    return sb.toString();
  }
}
