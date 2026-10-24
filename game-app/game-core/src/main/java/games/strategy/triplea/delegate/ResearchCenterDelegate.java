package games.strategy.triplea.delegate;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.random.IRandomStats.DiceType;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.delegate.data.ResearchResults;
import games.strategy.triplea.delegate.data.ResearchResults.Breakthrough;
import games.strategy.triplea.delegate.remote.IResearchDelegate;
import games.strategy.triplea.delegate.research.ResearchCenters;
import games.strategy.triplea.delegate.research.ResearchCenters.Mode;
import games.strategy.triplea.formatter.TechNames;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel research step. Each working research centre gains one star per turn: in its
 * primary focus 40%, secondary focus 20%, or a random unfinished category 40% (see {@link Mode} for
 * what happens when categories run short). A category gains at most one star per turn from the
 * centres. Bonus research rolls from owned techs (Goettingen) are extra. Techs whose last star
 * arrives are queued for the player's next Tech Activation step.
 */
public class ResearchCenterDelegate extends BaseTripleADelegate implements IResearchDelegate {
  private static final int FOCUS_DIE = 10;
  private static final int PERCENT_DIE = 100;

  private boolean needToInitialize = true;
  private boolean researchDone = false;

  @Override
  public void start() {
    super.start();
    if (needToInitialize) {
      researchDone = false;
      needToInitialize = false;
    }
  }

  @Override
  public void end() {
    if (!researchDone) {
      // nobody chose (AI player, or no centres): research with the stored focus
      final int idleCenter = ResearchCenters.getDefaultIdleCenter(player, getData().getMap());
      doResearch(
          ResearchCenters.getDefaultFocus(player, idleCenter, getData().getMap()), idleCenter);
    }
    super.end();
    needToInitialize = true;
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    return !researchDone
        && ResearchCenters.getWorkingResearchCenters(player, getData().getMap()) > 0;
  }

  @Override
  public ResearchResults research(final List<String> focus, final int idleCenter) {
    if (researchDone) {
      return ResearchResults.error("Research has already been done this turn.");
    }
    final Optional<String> error =
        ResearchCenters.validateFocus(player, focus, idleCenter, getData().getMap());
    if (error.isPresent()) {
      return ResearchResults.error(error.get());
    }
    final Turn turn = doResearch(focus, idleCenter);
    return ResearchResults.success(turn.lines, turn.details);
  }

  private Turn doResearch(final List<String> focus, final int idleCenter) {
    researchDone = true;
    final List<TechAdvance> previouslyUnlocked = getUnlockedButNotQueued();
    final TechAttachment techAttachment = player.getTechAttachment();
    final Turn turn = new Turn(techAttachment.getResearchStars());
    final List<Integer> working =
        ResearchCenters.getWorkingCenterIndexes(player, idleCenter, getData().getMap());
    final List<TechnologyFrontier> unfinished = ResearchCenters.getUnfinishedCategories(player);
    if (!working.isEmpty() && !unfinished.isEmpty()) {
      rollCenters(focus, working, unfinished, turn);
    }
    rollBonusResearch(turn);

    final CompositeChange change = new CompositeChange();
    change.add(ChangeFactory.attachmentPropertyChange(techAttachment, turn.stars, "researchStars"));
    if (!focus.isEmpty()) {
      change.add(
          ChangeFactory.attachmentPropertyChange(
              techAttachment, new ArrayList<>(focus), "researchFocus"));
    }
    bridge.addChange(change);
    turn.unlocked.addAll(previouslyUnlocked);
    if (!turn.unlocked.isEmpty()) {
      final TechnologyDelegate techDelegate = getData().getTechDelegate();
      if (techDelegate == null) {
        throw new IllegalStateException(
            "Research centres need the 'tech' delegate to queue unlocked techs");
      }
      techDelegate.addAdvances(player, turn.unlocked);
    }
    if (!turn.lines.isEmpty()) {
      bridge.getHistoryWriter().startEvent(player.getName() + " research");
      turn.lines.forEach(line -> bridge.getHistoryWriter().addChildToEvent(line));
    }
    return turn;
  }

  /**
   * Techs that already had all their stars before this turn (starting stars, or stars set in edit
   * mode) but were never queued for activation.
   */
  private List<TechAdvance> getUnlockedButNotQueued() {
    final TechnologyDelegate techDelegate = getData().getTechDelegate();
    final Collection<TechAdvance> queued =
        techDelegate == null || techDelegate.getAdvances(player) == null
            ? List.of()
            : techDelegate.getAdvances(player);
    final List<TechAdvance> result = new ArrayList<>();
    for (final TechnologyFrontier category : ResearchCenters.getCategories(player)) {
      for (final TechAdvance tech : category.getTechs()) {
        if (ResearchCenters.isUnlocked(player, tech) && !queued.contains(tech)) {
          result.add(tech);
        }
      }
    }
    return result;
  }

  private void rollCenters(
      final List<String> focus,
      final List<Integer> working,
      final List<TechnologyFrontier> unfinished,
      final Turn turn) {
    final Mode mode = ResearchCenters.getMode(unfinished.size(), working.size());
    if (mode == Mode.AUTOMATIC) {
      for (final TechnologyFrontier category : unfinished) {
        addStar(category, 0, "automatic", turn);
      }
      return;
    }
    final int[] rolls =
        bridge.getRandom(
            FOCUS_DIE,
            working.size(),
            player,
            DiceType.NONCOMBAT,
            player.getName() + " research centre rolls");
    final List<Integer> randomCenters = new ArrayList<>();
    for (int i = 0; i < working.size(); i++) {
      final int center = working.get(i);
      final String entry = focus.get(center);
      final int roll = rolls[i];
      if (roll < (mode == Mode.PRIMARY_AND_SECONDARY ? 4 : 5)) {
        addStar(category(ResearchCenters.getPrimary(entry)), center + 1, "primary", turn);
      } else if (mode == Mode.PRIMARY_AND_SECONDARY && roll < 6) {
        addStar(category(ResearchCenters.getSecondary(entry)), center + 1, "secondary", turn);
      } else {
        randomCenters.add(center + 1);
      }
    }
    // random breakthroughs go to categories that have not gained a star yet this turn
    for (final int center : randomCenters) {
      final List<TechnologyFrontier> candidates = new ArrayList<>(unfinished);
      candidates.removeIf(c -> turn.advancedCategories.contains(c.getName()));
      if (candidates.isEmpty()) {
        turn.lines.add("Research centre " + center + " (random): no category left");
        turn.details.add(new Breakthrough(center, "random", null, null, 0, 0));
        continue;
      }
      final int pick =
          candidates.size() == 1
              ? 0
              : bridge.getRandom(
                  candidates.size(),
                  player,
                  DiceType.NONCOMBAT,
                  player.getName() + " random research breakthrough");
      addStar(candidates.get(pick), center, "random", turn);
    }
  }

  private void rollBonusResearch(final Turn turn) {
    for (final TechAdvance tech :
        TechTracker.getCurrentTechAdvances(player, getData().getTechnologyFrontier())) {
      final TechAbilityAttachment attachment = TechAbilityAttachment.get(tech);
      if (attachment == null || attachment.getBonusResearchCategory().isEmpty()) {
        continue;
      }
      final Optional<TechnologyFrontier> category =
          ResearchCenters.getCategory(player, attachment.getBonusResearchCategory().get());
      if (category.isEmpty() || turn.getNextTech(player, category.get()).isEmpty()) {
        continue;
      }
      final int roll =
          bridge.getRandom(
              PERCENT_DIE,
              player,
              DiceType.NONCOMBAT,
              player.getName() + " " + tech.getName() + " research roll");
      if (roll < attachment.getBonusResearchChance()) {
        addStar(category.get(), 0, TechNames.display(tech, player), turn);
      } else {
        turn.lines.add(TechNames.display(tech, player) + ": no breakthrough");
        turn.details.add(
            new Breakthrough(
                0, TechNames.display(tech, player), category.get().getName(), null, 0, 0));
      }
    }
  }

  private TechnologyFrontier category(final String name) {
    return ResearchCenters.getCategory(player, name)
        .orElseThrow(() -> new IllegalStateException("No research category: " + name));
  }

  private void addStar(
      final TechnologyFrontier category, final int center, final String source, final Turn turn) {
    final Optional<TechAdvance> next = turn.getNextTech(player, category);
    if (next.isEmpty()) {
      return;
    }
    final TechAdvance tech = next.get();
    turn.stars.add(tech.getName(), 1);
    turn.advancedCategories.add(category.getName());
    final int stars = turn.stars.getInt(tech.getName());
    final int required = ResearchCenters.getStarsRequired(player, tech);
    turn.details.add(
        new Breakthrough(center, source, category.getName(), tech.getName(), stars, required));
    final String progress =
        (center > 0 ? "Research centre " + center + " (" + source + "): " : source + ": ")
            + category.getName()
            + " -> "
            // request 37: the name the nation knows it by
            + TechNames.display(tech, player)
            + " "
            + stars
            + "/"
            + required
            + " stars";
    if (stars >= required) {
      turn.unlocked.add(tech);
      turn.lines.add(progress + ", unlocked (takes effect at the end of the turn)");
    } else {
      turn.lines.add(progress);
    }
  }

  /** What this turn's research has done so far. */
  private static final class Turn {
    private final IntegerMap<String> stars;
    private final Set<String> advancedCategories = new HashSet<>();
    private final List<TechAdvance> unlocked = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private final List<Breakthrough> details = new ArrayList<>();

    private Turn(final IntegerMap<String> startingStars) {
      stars = new IntegerMap<>(startingStars);
    }

    private Optional<TechAdvance> getNextTech(
        final GamePlayer player, final TechnologyFrontier category) {
      return category.getTechs().stream()
          .filter(
              tech ->
                  !ResearchCenters.isOwned(player, tech)
                      && stars.getInt(tech.getName())
                          < ResearchCenters.getStarsRequired(player, tech))
          .findFirst();
    }
  }

  @Override
  public Serializable saveState() {
    final ResearchExtendedDelegateState state = new ResearchExtendedDelegateState();
    state.superState = super.saveState();
    state.needToInitialize = needToInitialize;
    state.researchDone = researchDone;
    return state;
  }

  @Override
  public void loadState(final Serializable state) {
    final ResearchExtendedDelegateState s = (ResearchExtendedDelegateState) state;
    super.loadState(s.superState);
    needToInitialize = s.needToInitialize;
    researchDone = s.researchDone;
  }

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IResearchDelegate.class;
  }

  private static final class ResearchExtendedDelegateState implements Serializable {
    @Serial private static final long serialVersionUID = -1418733452935409781L;

    private Serializable superState;
    private boolean needToInitialize;
    private boolean researchDone;
  }
}
