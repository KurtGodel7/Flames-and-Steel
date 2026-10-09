package games.strategy.triplea.delegate;

import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.message.IRemote;
import games.strategy.triplea.delegate.apc.Apcs;
import games.strategy.triplea.delegate.remote.IApcDelegate;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Flames and Steel APC steps. "nationApcProduction" (in the purchase phase): the nation chooses
 * what each of its APCs builds. "nationApcOutput" (near the end of the turn, with the free
 * Propaganda units): the APCs build, and the units appear in their territories.
 */
public class ApcDelegate extends BaseTripleADelegate implements IApcDelegate {

  @Override
  public void start() {
    super.start();
    if (stepName().endsWith("ApcOutput")) {
      Apcs.produce(bridge, player);
    }
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    return player != null
        && stepName().endsWith("ApcProduction")
        && !Apcs.getOwnApcs(player, getData().getMap()).isEmpty();
  }

  @Override
  public @Nullable String setProduction(final Map<Unit, UnitType> choices) {
    if (player == null || !stepName().endsWith("ApcProduction")) {
      return "This is not an APC production step.";
    }
    final Optional<String> error = Apcs.validate(player, choices, getData().getMap());
    if (error.isPresent()) {
      return error.get();
    }
    Apcs.setProduction(bridge, player, choices);
    return null;
  }

  private String stepName() {
    return getData().getSequence().getStep().getName();
  }

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IApcDelegate.class;
  }
}
