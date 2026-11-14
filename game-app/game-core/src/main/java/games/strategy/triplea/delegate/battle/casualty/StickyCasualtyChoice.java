package games.strategy.triplea.delegate.battle.casualty;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.UnitNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (request 28 part A): the casualty choice under sticky hits as stock TripleA
 * shows it, one entry per unit type, with the sticky rule applied by itself.
 *
 * <ul>
 *   <li>The player gives hits to a type, not to single units. Within a type, hits go to the units
 *       damaged earlier first (the most damaged first), then to one unit at a time until it is
 *       destroyed, then to the next: the player never manages this.
 *   <li>Hits that sticky hits forces onto units damaged earlier are given at the start and locked.
 *   <li>The choice is legal when every hit is given (or every unit is destroyed) and, within each
 *       kind of unit (land, sea, air), at most one unit is left damaged.
 * </ul>
 */
public class StickyCasualtyChoice {

  /** One entry: a nation's units of one type, in the order they take hits. */
  public static final class Group {
    @Getter private final GamePlayer owner;
    @Getter private final UnitType type;
    @Getter private final List<Unit> units = new ArrayList<>();
    @Getter private int locked;
    @Getter private int hits;

    Group(final GamePlayer owner, final UnitType type) {
      this.owner = owner;
      this.type = type;
    }

    /** Hit points the group's units have left in all: the most hits it can take. */
    public int capacity() {
      return units.stream().mapToInt(StickyHits::hitPointsLeft).sum();
    }

    /** What each unit gets from the group's hits, in order. */
    public List<StickyHits.Allocation> allocations() {
      return StickyHits.allocate(units, hits);
    }

    public int killed() {
      return (int) allocations().stream().filter(StickyHits.Allocation::killed).count();
    }

    /** The unit left damaged (alive) by this group's hits, if any. */
    public Optional<Unit> leftDamaged() {
      return allocations().stream()
          .filter(a -> !a.killed())
          .map(StickyHits.Allocation::unit)
          .findFirst();
    }

    /** Whether every unit of the group has one hit point (it looks like a stock entry). */
    public boolean isSingleHitPoint() {
      return units.stream().allMatch(u -> Unit.maxHitPoints(u) == 1);
    }

    public boolean hasDamagedEarlier() {
      return units.stream().anyMatch(u -> u.getHits() > 0);
    }

    public String name() {
      return displayName(type);
    }
  }

  @Getter private final int hitsToTake;
  @Getter private final List<Group> groups = new ArrayList<>();

  /**
   * @param units the units that can be hit, in the default order of loss
   * @param hitsToTake the hits to give
   */
  public StickyCasualtyChoice(final Collection<Unit> units, final int hitsToTake) {
    this.hitsToTake = hitsToTake;
    final Map<String, Group> byKey = new LinkedHashMap<>();
    // units damaged earlier first, the most damaged first; then the rest in the given order
    for (final Unit unit : StickyHits.damagedFirst(List.copyOf(units))) {
      byKey
          .computeIfAbsent(
              unit.getOwner().getName() + "|" + unit.getType().getName(),
              k -> new Group(unit.getOwner(), unit.getType()))
          .units
          .add(unit);
    }
    groups.addAll(byKey.values());
    // the hits sticky hits forces onto units damaged earlier, locked
    final StickyHits.ForcedHits forced = StickyHits.getForcedHits(units, hitsToTake);
    final IntegerMap<Unit> forcedPerUnit = new IntegerMap<>();
    forced.killed().forEach(u -> forcedPerUnit.add(u, StickyHits.hitPointsLeft(u)));
    forced.damaged().forEach(u -> forcedPerUnit.add(u, 1));
    for (final Group group : groups) {
      group.locked = group.units.stream().mapToInt(forcedPerUnit::getInt).sum();
      group.hits = group.locked;
    }
  }

  /** The hits given so far. */
  public int assigned() {
    return groups.stream().mapToInt(Group::getHits).sum();
  }

  /** The hits that must be given: all of them, or as many as the units can take. */
  public int needed() {
    return Math.min(hitsToTake, groups.stream().mapToInt(Group::capacity).sum());
  }

  /** Gives the group one more hit if it can take one and hits are left; returns whether it did. */
  public boolean add(final Group group) {
    if (assigned() >= needed() || group.hits >= group.capacity()) {
      return false;
    }
    group.hits++;
    return true;
  }

  /** Takes one hit back from the group, never a locked one; returns whether it did. */
  public boolean remove(final Group group) {
    if (group.hits <= group.locked) {
      return false;
    }
    group.hits--;
    return true;
  }

  /** Back to the locked hits only. */
  public void clear() {
    groups.forEach(g -> g.hits = g.locked);
  }

  /**
   * Fills in the engine's suggested casualties (stock: the cheapest first), keeping the locked
   * hits.
   */
  public void suggest(final CasualtyList suggested) {
    final IntegerMap<Unit> perUnit = new IntegerMap<>();
    suggested.getDamaged().forEach(u -> perUnit.add(u, 1));
    for (final Unit unit : suggested.getKilled()) {
      // a destroyed unit is listed as damaged once for each other hit point it had left
      perUnit.put(unit, StickyHits.hitPointsLeft(unit));
    }
    for (final Group group : groups) {
      final int wanted = group.units.stream().mapToInt(perUnit::getInt).sum();
      group.hits = Math.max(group.locked, Math.min(wanted, group.capacity()));
    }
    // the suggestion should be complete; if not, top it up down the list
    for (final Group group : groups) {
      while (add(group)) {
        // keep adding
      }
    }
  }

  /** Why OK can't be pressed yet, or empty when the choice is complete and legal. */
  public Optional<String> problem() {
    final int missing = needed() - assigned();
    if (missing > 0) {
      return Optional.of(
          "Give " + missing + MyFormatter.pluralize(" more hit", missing) + " to units.");
    }
    for (final String kind : List.of("land", "sea", "air")) {
      final List<Group> partial =
          groups.stream()
              .filter(g -> kindOf(g.units.get(0)).equals(kind) && g.leftDamaged().isPresent())
              .toList();
      if (partial.size() > 1) {
        return Optional.of(
            "A hit unit keeps taking hits until it is destroyed, so only one unit may be left"
                + " damaged: finish off the "
                + partial.get(0).name()
                + " or the "
                + partial.get(1).name()
                + ", or move a hit.");
      }
    }
    return Optional.empty();
  }

  /** Every unit hit and what it gets, group by group. */
  public List<StickyHits.Allocation> allocations() {
    final List<StickyHits.Allocation> result = new ArrayList<>();
    groups.forEach(g -> result.addAll(g.allocations()));
    return result;
  }

  /** The casualties as the engine lists them. */
  public CasualtyList casualties() {
    return StickyHits.toCasualtyList(allocations());
  }

  /** "Result: 1 Infantry destroyed; 1 Tank damaged." */
  public String resultText() {
    final List<String> parts = new ArrayList<>();
    for (final Group group : groups) {
      final int killed = group.killed();
      if (killed > 0) {
        parts.add(killed + " " + plural(group.name(), killed) + " " + fate(group.units.get(0)));
      }
    }
    for (final Group group : groups) {
      group.leftDamaged().ifPresent(u -> parts.add("1 " + group.name() + " damaged"));
    }
    return "Result: " + (parts.isEmpty() ? "no units hit" : String.join("; ", parts)) + ".";
  }

  /** "2 Tanks", but "3 Infantry" and "2 Artillery". */
  private static String plural(final String name, final int count) {
    return name.endsWith("ry") ? name : MyFormatter.pluralize(name, count);
  }

  /** "sunk" for ships, "shot down" for aircraft, "destroyed" otherwise. */
  public static String fate(final Unit unit) {
    if (Matches.unitIsAir().test(unit)) {
      return "shot down";
    }
    return Matches.unitIsSea().test(unit) ? "sunk" : "destroyed";
  }

  private static String kindOf(final Unit unit) {
    if (Matches.unitIsAir().test(unit)) {
      return "air";
    }
    return Matches.unitIsSea().test(unit) ? "sea" : "land";
  }

  /** The unit type's name in words: "heavyTank" becomes "Heavy Tank". */
  public static String displayName(final UnitType type) {
    final String raw = UnitNames.of(type);
    if (UnitNames.isRenamed(type.getName())) {
      return raw;
    }
    final StringBuilder out = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      final char c = raw.charAt(i);
      if (i == 0) {
        out.append(Character.toUpperCase(c));
      } else if (Character.isUpperCase(c) && !Character.isUpperCase(raw.charAt(i - 1))) {
        out.append(' ').append(c);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
