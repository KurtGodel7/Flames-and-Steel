package org.triplea.game.server.aitest;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TurnlessPlayers;
import games.strategy.triplea.delegate.research.ResearchCenters;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.triplea.java.collections.IntegerMap;

/**
 * Request 69: what an AI-only game did, written as CSV files and a one-page summary. It looks at
 * the game data before and after every step (the runner calls it between steps, when nothing else
 * changes the data), plus refused moves and log warnings reported while a step runs.
 */
final class GameLog {
  private final GameData data;
  private final Path dir;
  private final Resource pus;
  private final Resource mp;

  private final List<String> purchases = new ArrayList<>();
  private final List<String> placements = new ArrayList<>();
  private final List<String> research = new ArrayList<>();
  private final List<String> income = new ArrayList<>();
  private final List<String> battles = new ArrayList<>();
  private final List<String> air = new ArrayList<>();
  private final List<String> refused = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private final List<String> steps = new ArrayList<>();

  // summary totals by nation
  private final Map<String, int[]> bought = new TreeMap<>(); // units, PUs, MP, air units, air PUs
  private final Map<String, Map<Integer, Integer>> incomeByRound = new TreeMap<>();
  private final Map<String, int[]> refusedCount = new TreeMap<>(); // all, cooperation
  private final Map<String, Integer> battleCount = new TreeMap<>();
  private final Map<String, int[]> parkedLastRound = new TreeMap<>(); // aircraft, parked 3+

  // aircraft: where each was at the end of each round
  private final Map<UUID, Territory> lastPlace = new HashMap<>();
  private final Map<UUID, Integer> roundsInPlace = new HashMap<>();

  // snapshot before the step now running
  private GameStep step;
  private GamePlayer nation;
  private int round;
  private int pusBefore;
  private int mpBefore;
  private IntegerMap<UnitType> toPlaceBefore = new IntegerMap<>();
  private Set<UUID> unitsBefore = Set.of();
  private int starsBefore;
  private Set<String> techsBefore = Set.of();
  private Map<Territory, Map<String, String>> battleSitesBefore = Map.of();
  private Map<String, Integer> aircraftBefore = Map.of();
  private long stepStarted;

  private volatile String context = "setup";
  private int warnings;

  GameLog(final GameData data, final Path dir) {
    this.data = data;
    this.dir = dir;
    pus = data.getResourceList().getResourceOrThrow("PUs");
    mp = data.getResourceList().getResourceOptional("Manpower").orElse(null);
    purchases.add("round,nation,unit,count,pu_each,mp_each,air");
    placements.add("round,nation,step,territory,unit,count");
    research.add("round,nation,stars_gained,stars_total,techs_gained");
    income.add(
        "round,nation,pu_collected,mp_collected,pu_now,mp_now,unspent_pu_before_income,territories,tuv_on_map");
    battles.add(
        "round,step,territory,attacker,defender_before_owner,owner_after,attacker_units_before,"
            + "attacker_units_after,defender_units_before,defender_units_after,result");
    air.add("round,nation,aircraft,parked_3plus_rounds,where");
    refused.add("round,step,nation,units,from,to,cooperation_territory,reason");
    errors.add("round,step,nation,level,logger,message");
    steps.add("round,step,nation,seconds");
  }

  /** The step and nation running now, for warnings and the stuck message. */
  String context() {
    return context;
  }

  int warnings() {
    return warnings;
  }

  // ---------------------------------------------------------------------------------------------

  void beforeStep() {
    step = data.getSequence().getStep();
    nation = step.getPlayerId();
    round = data.getSequence().getRound();
    context = "round " + round + ", step " + step.getName() + ", nation " + name(nation);
    stepStarted = System.nanoTime();
    if (nation != null && !nation.isNull()) {
      pusBefore = nation.getResources().getQuantity(pus);
      mpBefore = mp == null ? 0 : nation.getResources().getQuantity(mp);
      toPlaceBefore = countByType(nation.getUnitCollection().getUnits());
      starsBefore = stars(nation);
      techsBefore = techs(nation);
    }
    final String n = step.getName();
    if (n.endsWith("Place") || n.endsWith("ApcOutput") || isEndTurn(n)) {
      unitsBefore = unitIds(nation);
    }
    if (isBattle(n)) {
      battleSitesBefore = battleSites();
    }
    if (n.equals("airStrikes") || n.equals("airCombat")) {
      aircraftBefore = aircraftByNation();
    }
  }

  void afterStep() {
    final String n = step.getName();
    steps.add(
        round
            + ","
            + n
            + ","
            + name(nation)
            + ","
            + String.format("%.1f", (System.nanoTime() - stepStarted) / 1e9));
    if (nation != null && !nation.isNull()) {
      if (n.endsWith("Purchase")) {
        logPurchase();
      } else if (n.endsWith("Place") || n.endsWith("ApcOutput")) {
        logPlacement(n);
      } else if (n.endsWith("Research")) {
        logResearch();
      } else if (isEndTurn(n)) {
        logIncome();
      }
    }
    if (isBattle(n)) {
      logBattles(n);
    }
    if (n.equals("airStrikes") || n.equals("airCombat")) {
      logAirBattles(n);
    }
  }

  /** Called when the round number goes up (and at the end): where every nation's aircraft are. */
  void endOfRound(final int finishedRound) {
    final Map<String, List<String>> where = new TreeMap<>();
    final Map<String, int[]> counts = new TreeMap<>();
    final Set<UUID> seen = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection().getMatches(Matches.unitIsAir())) {
        final String owner = u.getOwner().getName();
        seen.add(u.getId());
        final Territory before = lastPlace.get(u.getId());
        final int stayed = t.equals(before) ? roundsInPlace.getOrDefault(u.getId(), 1) + 1 : 1;
        lastPlace.put(u.getId(), t);
        roundsInPlace.put(u.getId(), stayed);
        final int[] c = counts.computeIfAbsent(owner, k -> new int[2]);
        c[0]++;
        if (stayed >= 3) {
          c[1]++;
        }
        where.computeIfAbsent(owner, k -> new ArrayList<>()).add(t.getName());
      }
    }
    lastPlace.keySet().retainAll(seen);
    roundsInPlace.keySet().retainAll(seen);
    for (final Map.Entry<String, int[]> e : counts.entrySet()) {
      final Map<String, Long> byPlace =
          where.get(e.getKey()).stream()
              .collect(Collectors.groupingBy(s -> s, TreeMap::new, Collectors.counting()));
      air.add(
          finishedRound
              + ","
              + e.getKey()
              + ","
              + e.getValue()[0]
              + ","
              + e.getValue()[1]
              + ","
              + q(
                  byPlace.entrySet().stream()
                      .map(p -> p.getValue() + "x " + p.getKey())
                      .collect(Collectors.joining("; "))));
    }
    parkedLastRound.clear();
    parkedLastRound.putAll(counts);
  }

  void refused(final GamePlayer player, final MoveDescription move, final String reason) {
    String coop = "";
    for (final Territory t : move.getRoute().getAllTerritories()) {
      final GamePlayer owner = t.getOwner();
      if (owner != null && !owner.isNull() && !owner.equals(player)) {
        final String rel =
            data.getRelationshipTracker().getRelationshipType(player, owner).getName();
        if (rel.contains("Cooperation")) {
          coop = t.getName() + " (" + owner.getName() + ", " + rel + ")";
          break;
        }
      }
    }
    synchronized (refused) {
      refused.add(
          round
              + ","
              + (step == null ? "" : step.getName())
              + ","
              + player.getName()
              + ","
              + q(summary(move.getUnits()))
              + ","
              + q(move.getRoute().getStart().getName())
              + ","
              + q(move.getRoute().getEnd().getName())
              + ","
              + q(coop)
              + ","
              + q(reason));
    }
    final int[] c = refusedCount.computeIfAbsent(player.getName(), k -> new int[2]);
    c[0]++;
    if (!coop.isEmpty()) {
      c[1]++;
    }
  }

  void error(final String level, final String logger, final String message) {
    synchronized (errors) {
      warnings++;
      errors.add(
          round
              + ","
              + (step == null ? "" : step.getName())
              + ","
              + name(nation)
              + ","
              + level
              + ","
              + logger
              + ","
              + q(message));
    }
  }

  // ---------------------------------------------------------------------------------------------

  private void logPurchase() {
    final IntegerMap<UnitType> after = countByType(nation.getUnitCollection().getUnits());
    final Map<UnitType, IntegerMap<Resource>> costs = costs(nation);
    final int[] tot = bought.computeIfAbsent(nation.getName(), k -> new int[5]);
    for (final UnitType type : after.keySet()) {
      final int count = after.getInt(type) - toPlaceBefore.getInt(type);
      if (count <= 0) {
        continue;
      }
      final IntegerMap<Resource> c = costs.getOrDefault(type, new IntegerMap<>());
      final int pu = c.getInt(pus);
      final int m = mp == null ? 0 : c.getInt(mp);
      final boolean isAir = Matches.unitTypeIsAir().test(type);
      purchases.add(
          round
              + ","
              + nation.getName()
              + ","
              + type.getName()
              + ","
              + count
              + ","
              + pu
              + ","
              + m
              + ","
              + (isAir ? "air" : ""));
      tot[0] += count;
      tot[1] += pu * count;
      tot[2] += m * count;
      if (isAir) {
        tot[3] += count;
        tot[4] += pu * count;
      }
    }
  }

  private void logPlacement(final String n) {
    final Map<Territory, IntegerMap<UnitType>> placed = new LinkedHashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection().getMatches(Matches.unitIsOwnedBy(nation))) {
        if (!unitsBefore.contains(u.getId())) {
          placed.computeIfAbsent(t, k -> new IntegerMap<>()).add(u.getType(), 1);
        }
      }
    }
    placed.forEach(
        (t, units) ->
            units.keySet().stream()
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .forEach(
                    type ->
                        placements.add(
                            round
                                + ","
                                + nation.getName()
                                + ","
                                + n
                                + ","
                                + q(t.getName())
                                + ","
                                + type.getName()
                                + ","
                                + units.getInt(type))));
    if (n.endsWith("Place")) {
      final IntegerMap<UnitType> left = countByType(nation.getUnitCollection().getUnits());
      if (left.totalValues() > 0) {
        placements.add(
            round
                + ","
                + nation.getName()
                + ","
                + n
                + ",(not placed),"
                + q(describe(left))
                + ","
                + left.totalValues());
      }
    }
  }

  private void logResearch() {
    final int after = stars(nation);
    final Set<String> gained = new java.util.TreeSet<>(techs(nation));
    gained.removeAll(techsBefore);
    research.add(
        round
            + ","
            + nation.getName()
            + ","
            + (after - starsBefore)
            + ","
            + after
            + ","
            + q(String.join("; ", gained)));
  }

  private void logIncome() {
    final int pusNow = nation.getResources().getQuantity(pus);
    final int mpNow = mp == null ? 0 : nation.getResources().getQuantity(mp);
    final long territories =
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater() && nation.equals(t.getOwner()))
            .count();
    income.add(
        round
            + ","
            + nation.getName()
            + ","
            + (pusNow - pusBefore)
            + ","
            + (mpNow - mpBefore)
            + ","
            + pusNow
            + ","
            + mpNow
            + ","
            + pusBefore
            + ","
            + territories
            + ","
            + tuv(nation));
    incomeByRound
        .computeIfAbsent(nation.getName(), k -> new TreeMap<>())
        .merge(round, pusNow - pusBefore, Integer::sum);
  }

  private Map<Territory, Map<String, String>> battleSites() {
    final Map<Territory, Map<String, String>> result = new LinkedHashMap<>();
    try {
      final var tracker = data.getBattleDelegate().getBattleTracker();
      final Set<Territory> sites = new java.util.LinkedHashSet<>();
      sites.addAll(tracker.getPendingBattleSitesWithoutBombing());
      sites.addAll(tracker.getPendingBattleSitesWithBombing());
      for (final Territory t : sites) {
        result.put(t, unitsByOwner(t));
      }
    } catch (final RuntimeException e) {
      error("WARN", "aitest", "could not read pending battles: " + e);
    }
    return result;
  }

  private void logBattles(final String n) {
    for (final Map.Entry<Territory, Map<String, String>> e : battleSitesBefore.entrySet()) {
      final Territory t = e.getKey();
      final Map<String, String> before = e.getValue();
      final Map<String, String> after = unitsByOwner(t);
      final String attacker = name(nation);
      final String ownerAfter = name(t.getOwner());
      final String defenderBefore =
          before.keySet().stream()
              .filter(k -> !k.equals(attacker))
              .collect(Collectors.joining("+"));
      final String attackerAfter = after.getOrDefault(attacker, "");
      final String defenderAfterUnits =
          after.entrySet().stream()
              .filter(x -> !x.getKey().equals(attacker))
              .map(x -> x.getKey() + ": " + x.getValue())
              .collect(Collectors.joining(" / "));
      final String result;
      if (ownerAfter.equals(attacker)
          || (attackerAfter.isEmpty() ? false : defenderAfterUnits.isEmpty())) {
        result = "attacker won";
      } else if (attackerAfter.isEmpty()) {
        result = "defender held";
      } else {
        result = "both survive";
      }
      battles.add(
          round
              + ","
              + n
              + ","
              + q(t.getName())
              + ","
              + attacker
              + ","
              + q(defenderBefore)
              + ","
              + ownerAfter
              + ","
              + q(before.getOrDefault(attacker, ""))
              + ","
              + q(attackerAfter)
              + ","
              + q(
                  before.entrySet().stream()
                      .filter(x -> !x.getKey().equals(attacker))
                      .map(x -> x.getKey() + ": " + x.getValue())
                      .collect(Collectors.joining(" / ")))
              + ","
              + q(defenderAfterUnits)
              + ","
              + result);
      battleCount.merge(attacker, 1, Integer::sum);
    }
  }

  private void logAirBattles(final String n) {
    final Map<String, Integer> now = aircraftByNation();
    final List<String> lost = new ArrayList<>();
    for (final Map.Entry<String, Integer> e : aircraftBefore.entrySet()) {
      final int diff = e.getValue() - now.getOrDefault(e.getKey(), 0);
      if (diff > 0) {
        lost.add(e.getKey() + " -" + diff);
      }
    }
    battles.add(
        round
            + ","
            + n
            + ",(air phase),,,,,,,,"
            + q(lost.isEmpty() ? "no aircraft lost" : "aircraft lost: " + String.join(", ", lost)));
  }

  // ---------------------------------------------------------------------------------------------

  /** Writes every file; called at the end, and safe to call more than once. */
  synchronized void write(final String outcome, final long seed, final int roundsPlayed)
      throws IOException {
    Files.createDirectories(dir);
    writeLines("purchases.csv", purchases);
    writeLines("placements.csv", placements);
    writeLines("research.csv", research);
    writeLines("income.csv", income);
    writeLines("battles.csv", battles);
    writeLines("air.csv", air);
    synchronized (refused) {
      writeLines("refused-moves.csv", refused);
    }
    synchronized (errors) {
      writeLines("errors.csv", errors);
    }
    writeLines("steps.csv", steps);
    writeLines("summary.txt", summary(outcome, seed, roundsPlayed));
  }

  private List<String> summary(final String outcome, final long seed, final int roundsPlayed) {
    final List<String> s = new ArrayList<>();
    s.add("Flames and Steel AI test game (request 69)");
    s.add("Seed " + seed + "; rounds played " + roundsPlayed + "; outcome: " + outcome);
    s.add("");
    s.add("At the end:");
    s.add(
        String.format(
            "  %-18s %6s %6s %6s %8s %7s", "nation", "terr", "PUs", "MP", "TUV", "income"));
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (TurnlessPlayers.isTurnless(p)) {
        continue;
      }
      final long terr =
          data.getMap().getTerritories().stream()
              .filter(t -> !t.isWater() && p.equals(t.getOwner()))
              .count();
      final Map<Integer, Integer> inc = incomeByRound.getOrDefault(p.getName(), Map.of());
      final int lastIncome = inc.isEmpty() ? 0 : inc.get(java.util.Collections.max(inc.keySet()));
      s.add(
          String.format(
              "  %-18s %6d %6d %6d %8d %7d",
              p.getName(),
              terr,
              p.getResources().getQuantity(pus),
              mp == null ? 0 : p.getResources().getQuantity(mp),
              tuv(p),
              lastIncome));
    }
    s.add("");
    s.add("PU income by round:");
    final StringBuilder head = new StringBuilder(String.format("  %-18s", "nation"));
    for (int r = 1; r <= roundsPlayed; r++) {
      head.append(String.format("%5d", r));
    }
    s.add(head.toString());
    for (final Map.Entry<String, Map<Integer, Integer>> e : incomeByRound.entrySet()) {
      final StringBuilder line = new StringBuilder(String.format("  %-18s", e.getKey()));
      for (int r = 1; r <= roundsPlayed; r++) {
        final Integer v = e.getValue().get(r);
        line.append(v == null ? "    -" : String.format("%5d", v));
      }
      s.add(line.toString());
    }
    s.add("");
    s.add("Purchases over the game (units, PUs, MP; of which aircraft and their PUs):");
    for (final Map.Entry<String, int[]> e : bought.entrySet()) {
      final int[] b = e.getValue();
      s.add(
          String.format(
              "  %-18s %5d units %6d PU %5d MP   aircraft %4d (%5d PU, %3d%% of PUs)",
              e.getKey(), b[0], b[1], b[2], b[3], b[4], b[1] == 0 ? 0 : 100 * b[4] / b[1]));
    }
    s.add("");
    s.add("Aircraft at the end, and how many had not moved for 3 or more rounds:");
    for (final Map.Entry<String, int[]> e : parkedLastRound.entrySet()) {
      s.add(
          String.format(
              "  %-18s %4d aircraft, %4d parked 3+ rounds",
              e.getKey(), e.getValue()[0], e.getValue()[1]));
    }
    s.add("");
    s.add("Moves the engine refused (of which next to or through a cooperation partner's land):");
    if (refusedCount.isEmpty()) {
      s.add("  none");
    }
    for (final Map.Entry<String, int[]> e : refusedCount.entrySet()) {
      s.add(
          String.format(
              "  %-18s %5d refused, %5d cooperation",
              e.getKey(), e.getValue()[0], e.getValue()[1]));
    }
    s.add("");
    s.add("Land and sea battles fought, by attacker: " + (battleCount.isEmpty() ? "none" : ""));
    battleCount.forEach((k, v) -> s.add(String.format("  %-18s %5d", k, v)));
    s.add("");
    s.add("Warnings and errors logged: " + warnings + " (see errors.csv)");
    return s;
  }

  private void writeLines(final String file, final List<String> lines) throws IOException {
    try (PrintWriter w =
        new PrintWriter(Files.newBufferedWriter(dir.resolve(file), StandardCharsets.UTF_8))) {
      lines.forEach(w::println);
    }
  }

  // ---------------------------------------------------------------------------------------------

  private static boolean isBattle(final String n) {
    return n.endsWith("Battle");
  }

  private static boolean isEndTurn(final String n) {
    return n.endsWith("EndTurn");
  }

  private Set<UUID> unitIds(final GamePlayer p) {
    final Set<UUID> ids = new HashSet<>();
    if (p == null) {
      return ids;
    }
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection().getMatches(Matches.unitIsOwnedBy(p))) {
        ids.add(u.getId());
      }
    }
    return ids;
  }

  private Map<String, Integer> aircraftByNation() {
    final Map<String, Integer> result = new TreeMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection().getMatches(Matches.unitIsAir())) {
        result.merge(u.getOwner().getName(), 1, Integer::sum);
      }
    }
    return result;
  }

  private static Map<String, String> unitsByOwner(final Territory t) {
    final Map<String, List<Unit>> byOwner = new TreeMap<>();
    for (final Unit u : t.getUnitCollection().getUnits()) {
      byOwner.computeIfAbsent(u.getOwner().getName(), k -> new ArrayList<>()).add(u);
    }
    final Map<String, String> result = new TreeMap<>();
    byOwner.forEach((k, v) -> result.put(k, summary(v)));
    return result;
  }

  private static String summary(final Collection<Unit> units) {
    return describe(countByType(units));
  }

  private static String describe(final IntegerMap<UnitType> counts) {
    return counts.keySet().stream()
        .sorted((a, b) -> a.getName().compareTo(b.getName()))
        .map(t -> counts.getInt(t) + " " + t.getName())
        .collect(Collectors.joining(" "));
  }

  private static IntegerMap<UnitType> countByType(final Collection<Unit> units) {
    final IntegerMap<UnitType> result = new IntegerMap<>();
    for (final Unit u : units) {
      result.add(u.getType(), 1);
    }
    return result;
  }

  private static Map<UnitType, IntegerMap<Resource>> costs(final GamePlayer p) {
    final Map<UnitType, IntegerMap<Resource>> result = new HashMap<>();
    if (p.getProductionFrontier() == null) {
      return result;
    }
    for (final ProductionRule rule : p.getProductionFrontier().getRules()) {
      final IntegerMap<NamedAttachable> results = rule.getResults();
      if (results.size() != 1) {
        continue;
      }
      final NamedAttachable r = results.keySet().iterator().next();
      if (r instanceof UnitType type && results.getInt(r) > 0) {
        final IntegerMap<Resource> each = new IntegerMap<>();
        rule.getCosts()
            .entrySet()
            .forEach(c -> each.put(c.getKey(), c.getValue() / results.getInt(r)));
        result.putIfAbsent(type, each);
      }
    }
    return result;
  }

  private int tuv(final GamePlayer p) {
    final IntegerMap<UnitType> costs = new TuvCostsCalculator().getCostsForTuv(p);
    int total = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection().getMatches(Matches.unitIsOwnedBy(p))) {
        total += costs.getInt(u.getType());
      }
    }
    return total;
  }

  private int stars(final GamePlayer p) {
    int total = 0;
    try {
      for (final TechAdvance tech : TechAdvance.getTechAdvances(data.getTechnologyFrontier(), p)) {
        total +=
            ResearchCenters.isFinished(p, tech)
                ? ResearchCenters.getStarsRequired(p, tech)
                : ResearchCenters.getStars(p, tech);
      }
    } catch (final RuntimeException e) {
      return 0;
    }
    return total;
  }

  private Set<String> techs(final GamePlayer p) {
    final Set<String> result = new HashSet<>();
    try {
      for (final TechAdvance tech : TechAdvance.getTechAdvances(data.getTechnologyFrontier(), p)) {
        if (ResearchCenters.isFinished(p, tech)) {
          result.add(tech.getName());
        }
      }
    } catch (final RuntimeException e) {
      // no research for this nation
    }
    return result;
  }

  private static String name(final GamePlayer p) {
    return p == null || p.isNull() ? "" : p.getName();
  }

  /** CSV quoting. */
  private static String q(final String s) {
    if (s == null) {
      return "";
    }
    return s.contains(",") || s.contains("\"") || s.contains("\n")
        ? "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""
        : s;
  }
}
