package games.strategy.triplea.ui.research;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.research.ResearchCenters;
import games.strategy.triplea.delegate.research.ResearchCenters.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Flames and Steel: a snapshot of one nation's research, read from the game data under its lock so
 * the screens can draw it without touching the data again.
 */
public record ResearchView(
    GamePlayer player,
    int centers,
    int workingCenters,
    boolean capitalLost,
    Mode mode,
    List<String> storedFocus,
    List<Category> categories) {

  /** How far a tech has got. */
  public enum State {
    /** Owned: researched and in effect. */
    OWNED,
    /** All stars gained; takes effect at the end of the turn. */
    UNLOCKED,
    /** Not yet researched (possibly with some stars). */
    OPEN
  }

  /**
   * One tech in a category.
   *
   * @param fixedRound the round the XML grants this tech on (China), or 0
   */
  public record Tech(String name, int stars, int required, State state, int fixedRound) {
    public String displayName() {
      return ResearchView.displayName(name);
    }
  }

  /**
   * A readable tech name from the XML name: "MechanizedInfantry" becomes "Mechanized Infantry",
   * "TypeXXIUboat" "Type XXI Uboat", "Propaganda1" "Propaganda 1".
   */
  public static String displayName(final String name) {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      final char c = name.charAt(i);
      if (i > 0 && sb.charAt(sb.length() - 1) != ' ') {
        final char prev = name.charAt(i - 1);
        final boolean nextLower =
            i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
        if ((Character.isUpperCase(c) && Character.isLowerCase(prev))
            || (Character.isUpperCase(c) && Character.isUpperCase(prev) && nextLower)
            || (Character.isDigit(c) && Character.isLowerCase(prev))) {
          sb.append(' ');
        }
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * One research category, techs in research order.
   *
   * @param nextIndex index of the tech the next star goes to, or -1 if the category is complete
   */
  public record Category(String name, List<Tech> techs, int nextIndex) {
    public boolean isComplete() {
      return nextIndex < 0;
    }
  }

  /** Reads the nation's research. Takes the game data read lock. */
  public static ResearchView read(final GameData data, final GamePlayer player) {
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final Map<TechAdvance, Integer> fixedRounds = ResearchCenters.getFixedTechRounds(player);
      final List<Category> categories = new ArrayList<>();
      for (final TechnologyFrontier frontier : ResearchCenters.getCategories(player)) {
        final List<Tech> techs = new ArrayList<>();
        int next = -1;
        for (final TechAdvance tech : frontier.getTechs()) {
          final State state;
          if (ResearchCenters.isOwned(player, tech)) {
            state = State.OWNED;
          } else if (ResearchCenters.isUnlocked(player, tech)) {
            state = State.UNLOCKED;
          } else {
            state = State.OPEN;
            if (next < 0) {
              next = techs.size();
            }
          }
          final int required = ResearchCenters.getStarsRequired(player, tech);
          techs.add(
              new Tech(
                  tech.getName(),
                  Math.min(required, ResearchCenters.getStars(player, tech)),
                  required,
                  state,
                  fixedRounds.getOrDefault(tech, 0)));
        }
        categories.add(new Category(frontier.getName(), List.copyOf(techs), next));
      }
      final int centers = ResearchCenters.getResearchCenters(player);
      final int working = ResearchCenters.getWorkingResearchCenters(player, data.getMap());
      final int unfinished = (int) categories.stream().filter(c -> !c.isComplete()).count();
      return new ResearchView(
          player,
          centers,
          working,
          ResearchCenters.hasLostCapital(player, data.getMap()),
          ResearchCenters.getMode(unfinished, working),
          List.copyOf(player.getTechAttachment().getResearchFocus()),
          List.copyOf(categories));
    }
  }

  /** Nations that have anything to show: research categories. */
  public static List<GamePlayer> nations(final GameData data) {
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      return data.getPlayerList().getPlayers().stream()
          .filter(p -> !ResearchCenters.getCategories(p).isEmpty())
          .toList();
    }
  }

  /** The nation whose turn it is, if it has research to show, else the first that does. */
  public static @Nullable GamePlayer defaultNation(final GameData data) {
    final List<GamePlayer> nations = nations(data);
    final GamePlayer current;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      current = data.getSequence().getStep().getPlayerId();
    }
    if (current != null && nations.contains(current)) {
      return current;
    }
    return nations.isEmpty() ? null : nations.get(0);
  }

  public boolean hasFixedTechs() {
    return categories.stream().flatMap(c -> c.techs().stream()).anyMatch(t -> t.fixedRound() > 0);
  }

  /** The odds line for this nation's centres this turn. */
  public String oddsText() {
    if (centers == 0) {
      return "";
    }
    return switch (mode) {
      case PRIMARY_AND_SECONDARY -> "Each centre: 40% primary · 20% secondary · 40% random";
      case PRIMARY_ONLY -> "Few categories left: 50% primary · 50% random";
      case AUTOMATIC -> "Every unfinished category gains one star this turn";
    };
  }

  /** "3 research centres, 2 working this turn (capital occupied)". */
  public String centersText() {
    if (centers == 0) {
      return hasFixedTechs() ? "No research centres; fixed techs arrive on set turns" : "";
    }
    return centers
        + " research centre"
        + (centers == 1 ? "" : "s")
        + ", "
        + workingCenters
        + " working this turn"
        + (capitalLost ? " (capital occupied)" : "");
  }
}
