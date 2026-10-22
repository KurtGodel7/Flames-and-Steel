package games.strategy.triplea.ui;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.attachments.TriggerAttachment;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Flames and Steel: the manpower a territory yields, read from the original owner's per-territory
 * manpower triggers. Only the original owner collects it, whoever holds the territory now.
 */
public final class TerritoryManpower {
  public static final String RESOURCE = "Manpower";

  /** Manpower per turn and who collects it. */
  public record Info(int amount, Map<GamePlayer, Integer> receivers) {
    /** "Russians", or "Germans, Italians" when several players collect. */
    public String receiverNames() {
      return receivers.keySet().stream().map(GamePlayer::getName).collect(Collectors.joining(", "));
    }

    /** "2 MP" */
    public String shortText() {
      return amount + " MP";
    }

    /** "2 Manpower (original owner only: Russians)" */
    public String longText() {
      return amount + " Manpower (original owner only: " + receiverNames() + ")";
    }
  }

  private TerritoryManpower() {}

  /** Empty for water, for maps without manpower and for territories that yield none. */
  public static Optional<Info> of(final Territory territory) {
    if (territory.isWater() || resource(territory).isEmpty()) {
      return Optional.empty();
    }
    final Map<GamePlayer, Integer> receivers =
        new java.util.LinkedHashMap<>(
            TriggerAttachment.getEachOwnershipResource(territory, RESOURCE));
    // request 28: China collects the value of Chinese homeland it owns
    final int chinese =
        games.strategy.triplea.delegate.ChineseLiberation.homelandManpower(
            territory, territory.getOwner());
    if (chinese > 0) {
      receivers.put(territory.getOwner(), chinese);
    }
    final int amount = receivers.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    return amount > 0 ? Optional.of(new Info(amount, receivers)) : Optional.empty();
  }

  public static Optional<Resource> resource(final Territory territory) {
    return territory.getData().getResourceList().getResourceOptional(RESOURCE);
  }
}
