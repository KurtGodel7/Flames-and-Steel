package org.triplea.game.server.aitest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.framework.GameRunner;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.net.LocalNoOpMessenger;
import games.strategy.net.Messengers;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.ai.AiTestHooks;
import games.strategy.triplea.delegate.TurnlessPlayers;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.LoggerFactory;
import org.triplea.ai.does.nothing.DoesNothingAi;
import org.triplea.game.server.HeadlessLaunchAction;

/**
 * Request 69: one AI-only game, run without a user interface, in its own Java process. Every nation
 * that takes turns is played by the Hard AI (ProAI); nations that never take a turn (the Dutch,
 * Vichy) are "Does Nothing", as in normal games. Request 68's human-only switch only filters the
 * set-up screens, so it does not apply here: this runner creates its players directly.
 */
final class AiTestGame {
  private static final PlayerTypes.Type DOES_NOTHING =
      new PlayerTypes.Type(PlayerTypes.DOES_NOTHING_PLAYER_LABEL) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new DoesNothingAi(name, getLabel());
        }
      };

  private AiTestGame() {}

  /** Runs the game and exits the process: 0 finished, 2 stuck, 3 crashed, 4 could not start. */
  static void runAndExit(
      final Path xml, final long seed, final int rounds, final int roundMinutes, final Path dir) {
    int code;
    try {
      code = run(xml, seed, rounds, roundMinutes, dir);
    } catch (final Throwable e) {
      e.printStackTrace();
      code = 4;
    }
    System.out.flush();
    System.err.flush();
    // AI helper threads may still be running; this process exists only for this game
    Runtime.getRuntime().halt(code);
  }

  private static int run(
      final Path xml, final long seed, final int rounds, final int roundMinutes, final Path dir)
      throws Exception {
    setUpHeadless();
    final GameData data =
        GameParser.parse(xml, false)
            .orElseThrow(() -> new IllegalStateException("Cannot parse " + xml));
    final Map<String, PlayerTypes.Type> types = new HashMap<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      types.put(p.getName(), TurnlessPlayers.isTurnless(p) ? DOES_NOTHING : PlayerTypes.PRO_AI);
    }
    final Set<Player> players = data.getGameLoader().newPlayers(types);
    final HeadlessLaunchAction launch = new HeadlessLaunchAction(null);
    final ServerGame game =
        new ServerGame(
            data,
            players,
            new HashMap<>(),
            new Messengers(new LocalNoOpMessenger()),
            ClientNetworkBridge.NO_OP_SENDER,
            launch);
    game.setDelegateAutosavesEnabled(false);
    game.setRandomSource(new SeededRandomSource(seed));
    data.getGameLoader().startGame(game, players, launch, null);
    game.setStopGameOnDelegateExecutionStop(true);

    final GameLog log = new GameLog(data, dir);
    AiTestHooks.setRefusedMoveListener(log::refused);
    attachLogCapture(log);

    final ExecutorService worker =
        Executors.newSingleThreadExecutor(
            r -> {
              final Thread t = new Thread(r, "ai-test-steps");
              t.setDaemon(true);
              return t;
            });
    final long roundBudget = TimeUnit.MINUTES.toNanos(roundMinutes);
    int round = data.getSequence().getRound();
    long roundStarted = System.nanoTime();
    String outcome = "finished " + rounds + " rounds";
    int code = 0;
    System.out.println("AI test game: seed " + seed + ", " + rounds + " rounds, " + xml);
    while (!game.isGameOver() && round <= rounds) {
      log.beforeStep();
      final Future<?> step = worker.submit(game::runNextStep);
      try {
        final long left = roundBudget - (System.nanoTime() - roundStarted);
        step.get(Math.max(1, left), TimeUnit.NANOSECONDS);
      } catch (final TimeoutException e) {
        outcome =
            "STUCK at "
                + log.context()
                + ": round "
                + round
                + " took more than "
                + roundMinutes
                + " minutes";
        System.out.println(outcome);
        Files.writeString(dir.resolve("stuck-threads.txt"), threadDump(), StandardCharsets.UTF_8);
        code = 2;
        break;
      } catch (final ExecutionException e) {
        outcome = "CRASHED at " + log.context() + ": " + e.getCause();
        System.out.println(outcome);
        e.getCause().printStackTrace(System.out);
        log.error("ERROR", "aitest", outcome);
        code = 3;
        break;
      }
      log.afterStep();
      final int now = data.getSequence().getRound();
      if (now != round) {
        log.endOfRound(round);
        System.out.println(
            "round "
                + round
                + " done in "
                + (System.nanoTime() - roundStarted) / 1_000_000_000L
                + " s");
        log.write("running", seed, round);
        round = now;
        roundStarted = System.nanoTime();
      }
    }
    if (game.isGameOver() && code == 0) {
      outcome = "game over (victory) in round " + round;
    }
    log.write(outcome, seed, Math.min(round, rounds));
    System.out.println("Outcome: " + outcome);
    return code;
  }

  private static void setUpHeadless() throws IOException {
    HeadlessLaunchAction.setSkipMapResourceLoading(true);
    ClientSetting.setPreferences(new InMemoryPreferences());
    ClientSetting.aiMovePauseDuration.setValue(0);
    ClientSetting.aiCombatStepPauseDuration.setValue(0);
    final Path root = Files.createTempDirectory("fs-ai-test");
    Files.writeString(root.resolve(".triplea-root"), "");
    Files.createDirectories(root.resolve("assets"));
    ClientFileSystemHelper.setCodeSourceFolder(root);
    System.setProperty(GameRunner.TRIPLEA_HEADLESS, "true");
  }

  private static void attachLogCapture(final GameLog log) {
    final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    final AppenderBase<ILoggingEvent> appender =
        new AppenderBase<>() {
          @Override
          protected void append(final ILoggingEvent event) {
            if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
              return;
            }
            String message = event.getFormattedMessage();
            final IThrowableProxy t = event.getThrowableProxy();
            if (t != null) {
              message += " | " + t.getClassName() + ": " + t.getMessage();
              if (t.getStackTraceElementProxyArray().length > 0) {
                message += " at " + t.getStackTraceElementProxyArray()[0].getSTEAsString();
              }
            }
            log.error(event.getLevel().toString(), event.getLoggerName(), message);
          }
        };
    appender.setContext(root.getLoggerContext());
    appender.setName("aiTestCapture");
    appender.start();
    root.addAppender(appender);
  }

  private static String threadDump() {
    final StringBuilder s = new StringBuilder();
    for (final ThreadInfo info :
        ManagementFactory.getThreadMXBean().dumpAllThreads(true, true, 200)) {
      s.append(info.toString());
      final StackTraceElement[] trace = info.getStackTrace();
      if (trace.length > 8) {
        s.append("\t(full stack)\n");
        for (int i = 8; i < trace.length; i++) {
          s.append("\tat ").append(trace[i]).append('\n');
        }
      }
      s.append('\n');
    }
    return s.toString();
  }
}
