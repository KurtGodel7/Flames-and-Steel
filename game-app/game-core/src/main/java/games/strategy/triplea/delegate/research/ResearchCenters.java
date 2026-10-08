package games.strategy.triplea.delegate.research;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel research centres: read-only helpers shared by the research step and the UI.
 *
 * <p>A player's research categories are its tech categories (playerTech in the XML), each
 * researched strictly in order. A tech is finished when the player owns it or has all its stars
 * (unlocked, waiting for Tech Activation). A category is finished when all its techs are.
 */
@UtilityClass
public class ResearchCenters {
  /** Separates primary and secondary focus in a stored focus entry. */
  public static final String FOCUS_SEPARATOR = "|";

  /** How research centres work this turn, depending on how many categories are left. */
  public enum Mode {
    /** At least two unfinished categories per working centre: 40% / 20% / 40%. */
    PRIMARY_AND_SECONDARY,
    /** At least one per working centre: 50% primary / 50% random. */
    PRIMARY_ONLY,
    /** No more than one per working centre: one star in every unfinished category. */
    AUTOMATIC
  }

  public static int getResearchCenters(final GamePlayer player) {
    return player.getTechAttachment().getResearchCenters();
  }

  /** Centres working this turn: one fewer while the player does not hold any of its capitals. */
  public static int getWorkingResearchCenters(final GamePlayer player, final GameMap map) {
    final int centers = getResearchCenters(player);
    return centers > 0 && hasLostCapital(player, map) ? centers - 1 : centers;
  }

  public static boolean hasLostCapital(final GamePlayer player, final GameMap map) {
    return !TerritoryAttachment.getAllCapitals(player, map).isEmpty()
        && TerritoryAttachment.getAllCurrentlyOwnedCapitals(player, map).isEmpty();
  }

  public static List<TechnologyFrontier> getCategories(final GamePlayer player) {
    return player.getTechnologyFrontierList().getFrontiers();
  }

  public static Optional<TechnologyFrontier> getCategory(
      final GamePlayer player, final String name) {
    return getCategories(player).stream().filter(c -> c.getName().equals(name)).findAny();
  }

  public static List<TechnologyFrontier> getUnfinishedCategories(final GamePlayer player) {
    final List<TechnologyFrontier> unfinished = new ArrayList<>();
    for (final TechnologyFrontier category : getCategories(player)) {
      if (getNextTech(player, category).isPresent()) {
        unfinished.add(category);
      }
    }
    return unfinished;
  }

  /** The first unfinished tech in the category, which is where the next star goes. */
  public static Optional<TechAdvance> getNextTech(
      final GamePlayer player, final TechnologyFrontier category) {
    return category.getTechs().stream().filter(tech -> !isFinished(player, tech)).findFirst();
  }

  public static boolean isFinished(final GamePlayer player, final TechAdvance tech) {
    return isOwned(player, tech) || isUnlocked(player, tech);
  }

  public static boolean isOwned(final GamePlayer player, final TechAdvance tech) {
    return tech.hasTech(player.getTechAttachment());
  }

  /** All stars gained but not yet activated. */
  public static boolean isUnlocked(final GamePlayer player, final TechAdvance tech) {
    return !isOwned(player, tech) && getStars(player, tech) >= getStarsRequired(player, tech);
  }

  public static int getStars(final GamePlayer player, final TechAdvance tech) {
    final TechAttachment techAttachment = player.getTechAttachment();
    return techAttachment.getResearchStars().getInt(tech.getName());
  }

  /** Stars this player needs for the tech: the nation's own count if set, else the tech's. */
  public static int getStarsRequired(final GamePlayer player, final TechAdvance tech) {
    final int override =
        player.getTechAttachment().getResearchStarsRequired().getInt(tech.getName());
    return override > 0 ? override : getStarsRequired(tech);
  }

  public static int getStarsRequired(final TechAdvance tech) {
    final TechAbilityAttachment attachment = TechAbilityAttachment.get(tech);
    return attachment == null ? 1 : attachment.getResearchStarsRequired();
  }

  public static Mode getMode(final int unfinishedCategories, final int workingCenters) {
    if (unfinishedCategories <= workingCenters) {
      return Mode.AUTOMATIC;
    }
    if (unfinishedCategories >= 2 * workingCenters) {
      return Mode.PRIMARY_AND_SECONDARY;
    }
    return Mode.PRIMARY_ONLY;
  }

  /** Indexes of the centres working this turn, given the centre chosen to sit idle. */
  public static List<Integer> getWorkingCenterIndexes(
      final GamePlayer player, final int idleCenter, final GameMap map) {
    final int centers = getResearchCenters(player);
    final List<Integer> working = new ArrayList<>();
    for (int i = 0; i < centers; i++) {
      working.add(i);
    }
    if (centers > 0 && hasLostCapital(player, map)) {
      working.remove(
          Integer.valueOf(idleCenter >= 0 && idleCenter < centers ? idleCenter : centers - 1));
    }
    return working;
  }

  /** The centre that sits idle if nobody chooses: the last one, or -1 if none must. */
  public static int getDefaultIdleCenter(final GamePlayer player, final GameMap map) {
    final int centers = getResearchCenters(player);
    return centers > 0 && hasLostCapital(player, map) ? centers - 1 : -1;
  }

  /**
   * The focus to offer or use: last turn's choices, with any finished, unknown or repeated category
   * replaced by the first unfinished category not already in use.
   */
  public static List<String> getDefaultFocus(
      final GamePlayer player, final int idleCenter, final GameMap map) {
    final int centers = getResearchCenters(player);
    final List<String> stored = player.getTechAttachment().getResearchFocus();
    final List<String> primaries = new ArrayList<>();
    final List<String> secondaries = new ArrayList<>();
    for (int i = 0; i < centers; i++) {
      final String entry = i < stored.size() ? stored.get(i) : "";
      primaries.add(getPrimary(entry));
      secondaries.add(getSecondary(entry));
    }
    final List<Integer> working = getWorkingCenterIndexes(player, idleCenter, map);
    final List<String> unfinished =
        getUnfinishedCategories(player).stream().map(TechnologyFrontier::getName).toList();
    final Mode mode = getMode(unfinished.size(), working.size());
    if (mode != Mode.AUTOMATIC) {
      final Set<String> used = new HashSet<>();
      keepValid(primaries, working, unfinished, used);
      if (mode == Mode.PRIMARY_AND_SECONDARY) {
        keepValid(secondaries, working, unfinished, used);
      }
      fillMissing(primaries, working, unfinished, used);
      if (mode == Mode.PRIMARY_AND_SECONDARY) {
        fillMissing(secondaries, working, unfinished, used);
      }
    }
    final List<String> focus = new ArrayList<>();
    for (int i = 0; i < centers; i++) {
      focus.add(toFocusEntry(primaries.get(i), secondaries.get(i)));
    }
    return focus;
  }

  private static void keepValid(
      final List<String> choices,
      final List<Integer> working,
      final List<String> unfinished,
      final Set<String> used) {
    for (final int i : working) {
      final String choice = choices.get(i);
      if (unfinished.contains(choice) && used.add(choice)) {
        continue;
      }
      choices.set(i, "");
    }
  }

  private static void fillMissing(
      final List<String> choices,
      final List<Integer> working,
      final List<String> unfinished,
      final Set<String> used) {
    for (final int i : working) {
      if (choices.get(i).isEmpty()) {
        unfinished.stream()
            .filter(c -> !used.contains(c))
            .findFirst()
            .ifPresent(
                c -> {
                  choices.set(i, c);
                  used.add(c);
                });
      }
    }
  }

  /** Why the chosen focus is not allowed this turn, if it is not. */
  public static Optional<String> validateFocus(
      final GamePlayer player, final List<String> focus, final int idleCenter, final GameMap map) {
    final int centers = getResearchCenters(player);
    if (focus.size() != centers) {
      return Optional.of("Choose a focus for each of your " + centers + " research centres.");
    }
    if (centers > 0 && hasLostCapital(player, map) && (idleCenter < 0 || idleCenter >= centers)) {
      return Optional.of("Your capital is occupied: choose which research centre sits idle.");
    }
    final List<Integer> working = getWorkingCenterIndexes(player, idleCenter, map);
    final List<String> unfinished =
        getUnfinishedCategories(player).stream().map(TechnologyFrontier::getName).toList();
    final Mode mode = getMode(unfinished.size(), working.size());
    if (mode == Mode.AUTOMATIC) {
      return Optional.empty();
    }
    final Set<String> used = new HashSet<>();
    for (final int i : working) {
      final List<String> choices = new ArrayList<>();
      choices.add(getPrimary(focus.get(i)));
      if (mode == Mode.PRIMARY_AND_SECONDARY) {
        choices.add(getSecondary(focus.get(i)));
      }
      for (final String choice : choices) {
        if (!unfinished.contains(choice)) {
          return Optional.of(
              "Research centre "
                  + (i + 1)
                  + ": '"
                  + choice
                  + "' is not an unfinished research category.");
        }
        if (!used.add(choice)) {
          return Optional.of(
              "'"
                  + choice
                  + "' is chosen more than once; each focus must be a different category.");
        }
      }
    }
    return Optional.empty();
  }

  public static String getPrimary(final String focusEntry) {
    final int i = focusEntry.indexOf(FOCUS_SEPARATOR);
    return i < 0 ? focusEntry : focusEntry.substring(0, i);
  }

  public static String getSecondary(final String focusEntry) {
    final int i = focusEntry.indexOf(FOCUS_SEPARATOR);
    return i < 0 ? "" : focusEntry.substring(i + 1);
  }

  public static String toFocusEntry(final String primary, final String secondary) {
    return primary + FOCUS_SEPARATOR + secondary;
  }
}
