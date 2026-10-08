package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.message.IRemote;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.delegate.remote.IAirAllocationDelegate;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Flames and Steel air allocation. Steps named "nationAirBombers" and "nationAirFighters" collect
 * each nation's moves. They are kept here on the host, out of the game data, so other players
 * cannot see them. A step whose name starts with "airReveal" (no player) then carries out
 * everyone's moves at once.
 */
public class AirAllocationDelegate extends BaseTripleADelegate implements IAirAllocationDelegate {
  private Map<GamePlayer, List<AirMove>> pending = new LinkedHashMap<>();
  private Stage pendingStage = Stage.BOMBERS;

  @Override
  public void start() {
    super.start();
    if (isRevealStep()) {
      reveal();
    }
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    if (player == null || isRevealStep()) {
      return false;
    }
    final Stage stage = currentStage();
    return AirZoneAttachment.getAirZones(getData().getMap()).stream()
        .anyMatch(zone -> !AirAllocation.getAircraft(player, zone, stage).isEmpty());
  }

  @Override
  public @Nullable String allocate(final List<AirMove> moves) {
    if (player == null || isRevealStep()) {
      return "This is not an air allocation step.";
    }
    final Stage stage = currentStage();
    final Optional<String> error = AirAllocation.validate(player, stage, moves, getData().getMap());
    if (error.isPresent()) {
      return error.get();
    }
    if (pendingStage != stage) {
      pending.clear();
    }
    pendingStage = stage;
    pending.put(player, new ArrayList<>(moves));
    return null;
  }

  private void reveal() {
    final String stageName = pendingStage == Stage.BOMBERS ? "Bomber" : "Fighter";
    bridge.getHistoryWriter().startEvent(stageName + " allocation revealed");
    boolean anyMoved = false;
    for (final Map.Entry<GamePlayer, List<AirMove>> entry : pending.entrySet()) {
      final GamePlayer mover = entry.getKey();
      final List<AirMove> moves = entry.getValue();
      if (moves.isEmpty()) {
        continue;
      }
      anyMoved = true;
      // still valid: nothing can move aircraft between the allocation and the reveal
      AirAllocation.describe(moves)
          .forEach(
              line -> bridge.getHistoryWriter().addChildToEvent(mover.getName() + ": " + line));
      bridge.addChange(AirAllocation.toChange(mover, pendingStage, moves));
    }
    if (!anyMoved) {
      bridge.getHistoryWriter().addChildToEvent("No aircraft moved");
    }
    pending.clear();
  }

  private boolean isRevealStep() {
    return getData().getSequence().getStep().getName().startsWith("airReveal");
  }

  private Stage currentStage() {
    return getData().getSequence().getStep().getName().endsWith("AirFighters")
        ? Stage.FIGHTERS
        : Stage.BOMBERS;
  }

  @Override
  public Serializable saveState() {
    final AirAllocationState state = new AirAllocationState();
    state.superState = super.saveState();
    state.pending = new LinkedHashMap<>(pending);
    state.pendingStage = pendingStage;
    return state;
  }

  @Override
  public void loadState(final Serializable state) {
    final AirAllocationState s = (AirAllocationState) state;
    super.loadState(s.superState);
    pending = new LinkedHashMap<>(s.pending);
    pendingStage = s.pendingStage;
  }

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IAirAllocationDelegate.class;
  }

  private static final class AirAllocationState implements Serializable {
    @Serial private static final long serialVersionUID = 7743920168411230592L;

    private Serializable superState;
    private Map<GamePlayer, List<AirMove>> pending;
    private Stage pendingStage;
  }
}
