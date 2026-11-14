package org.triplea.game.server.aitest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Flames and Steel request 69: runs AI-only games without a user interface and writes logs.
 *
 * <pre>
 * run-ai-test --map flames_and_steel --rounds 15 --games 5 --seed 1 --out ai-test-results
 *   [--xml path\to\game.xml] [--parallel 1] [--round-minutes 30] [--heap 3g]
 * </pre>
 *
 * Game N uses seed (seed + N - 1). Each game runs in its own Java process, so a game that hangs is
 * reported ("STUCK at round R, step S, nation N") and stopped without stopping the others. Logs:
 * out/game-N/*.csv, out/game-N/summary.txt, out/game-N/console.log, and out/overview.txt.
 */
public final class AiTestRunner {
  private AiTestRunner() {}

  public static void main(final String[] args) throws Exception {
    final Map<String, String> o = parse(args);
    final int rounds = Integer.parseInt(o.getOrDefault("rounds", "15"));
    final int roundMinutes = Integer.parseInt(o.getOrDefault("round-minutes", "30"));
    final long seed = Long.parseLong(o.getOrDefault("seed", "1"));
    final Path out = Path.of(o.getOrDefault("out", "ai-test-results")).toAbsolutePath();
    final Path xml = findXml(o);
    if (o.containsKey("child")) {
      AiTestGame.runAndExit(xml, seed, rounds, roundMinutes, out);
      return;
    }
    final int games = Integer.parseInt(o.getOrDefault("games", "5"));
    final int parallel = Math.max(1, Integer.parseInt(o.getOrDefault("parallel", "1")));
    final String heap = o.getOrDefault("heap", "3g");
    Files.createDirectories(out);
    System.out.println(
        "Running "
            + games
            + " AI-only games of "
            + rounds
            + " rounds ("
            + parallel
            + " at a time) on "
            + xml
            + "\nLogs in "
            + out);
    final ExecutorService pool = Executors.newFixedThreadPool(parallel);
    final List<Future<String>> results = new ArrayList<>();
    for (int g = 1; g <= games; g++) {
      final int n = g;
      final long gameSeed = seed + g - 1;
      results.add(pool.submit(() -> runChild(n, gameSeed, xml, rounds, roundMinutes, out, heap)));
    }
    final List<String> lines = new ArrayList<>();
    lines.add("Flames and Steel AI test run: " + games + " games, " + rounds + " rounds");
    lines.add("Map: " + xml);
    lines.add("");
    for (final Future<String> f : results) {
      lines.add(f.get());
    }
    pool.shutdown();
    lines.add("");
    for (int g = 1; g <= games; g++) {
      final Path summary = out.resolve("game-" + g).resolve("summary.txt");
      lines.add("=== game " + g + " ===");
      if (Files.exists(summary)) {
        lines.addAll(Files.readAllLines(summary, StandardCharsets.UTF_8));
      } else {
        lines.add("(no summary: see game-" + g + "/console.log)");
      }
      lines.add("");
    }
    Files.write(out.resolve("overview.txt"), lines, StandardCharsets.UTF_8);
    System.out.println(String.join(System.lineSeparator(), lines.subList(0, games + 3)));
    System.out.println("Overview: " + out.resolve("overview.txt"));
  }

  private static String runChild(
      final int n,
      final long seed,
      final Path xml,
      final int rounds,
      final int roundMinutes,
      final Path out,
      final String heap)
      throws IOException, InterruptedException {
    final Path dir = out.resolve("game-" + n);
    Files.createDirectories(dir);
    final String java = ProcessHandle.current().info().command().orElse("java");
    final List<String> cmd =
        List.of(
            java,
            "-Xmx" + heap,
            "-cp",
            System.getProperty("java.class.path"),
            AiTestRunner.class.getName(),
            "--child",
            "yes",
            "--xml",
            xml.toString(),
            "--seed",
            Long.toString(seed),
            "--rounds",
            Integer.toString(rounds),
            "--round-minutes",
            Integer.toString(roundMinutes),
            "--out",
            dir.toString());
    final long started = System.nanoTime();
    System.out.println("game " + n + " (seed " + seed + ") started");
    final Process p =
        new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("console.log").toFile())
            .start();
    // a child stuck beyond its own watchdog (e.g. out of memory) is stopped after rounds x limit
    if (!p.waitFor((long) rounds * roundMinutes + 10, TimeUnit.MINUTES)) {
      p.destroyForcibly();
    }
    final long minutes = (System.nanoTime() - started) / 60_000_000_000L;
    final String status =
        switch (p.exitValue()) {
          case 0 -> "finished";
          case 2 -> "STUCK (see summary and stuck-threads.txt)";
          case 3 -> "CRASHED (see summary and console.log)";
          default -> "failed, exit " + p.exitValue() + " (see console.log)";
        };
    final String line = "game " + n + " (seed " + seed + "): " + status + ", " + minutes + " min";
    System.out.println(line);
    return line;
  }

  private static Path findXml(final Map<String, String> o) throws IOException {
    if (o.containsKey("xml")) {
      return Path.of(o.get("xml")).toAbsolutePath();
    }
    final String map = o.getOrDefault("map", "flames_and_steel");
    final String home = System.getProperty("user.home");
    for (final Path root :
        List.of(Path.of(home, "Documents", "triplea"), Path.of(home, "triplea"))) {
      final Path games =
          root.resolve("downloadedMaps").resolve(map).resolve("map").resolve("games");
      if (Files.isDirectory(games)) {
        try (var files = Files.list(games)) {
          final var xml = files.filter(f -> f.toString().endsWith(".xml")).sorted().findFirst();
          if (xml.isPresent()) {
            return xml.get();
          }
        }
      }
    }
    throw new IllegalArgumentException(
        "Map '" + map + "' not found in Documents/triplea or ~/triplea; use --xml <file>");
  }

  private static Map<String, String> parse(final String[] args) {
    final Map<String, String> o = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("Unexpected argument: " + args[i]);
      }
      final String key = args[i].substring(2);
      final String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "yes";
      o.put(key, value);
    }
    return o;
  }
}
