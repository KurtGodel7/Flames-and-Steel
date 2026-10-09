package games.strategy.triplea.delegate.remote;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.message.RemoteActionCode;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Flames and Steel: hidden allocation of aircraft between air zones. */
public interface IAirAllocationDelegate extends IRemote, IDelegate {
  /**
   * Submits this nation's aircraft moves for the current stage. They stay hidden until the reveal
   * step.
   *
   * @return why the moves are refused, or null if accepted
   */
  @RemoteActionCode(12)
  @Nullable
  String allocate(List<AirMove> moves);

  /** This nation's moves stored so far in the current stage (after a save and load, say). */
  @RemoteActionCode(13)
  List<AirMove> getPendingMoves();

  /** Moves stored so far in the current stage by this nation's allies (they see each other). */
  @RemoteActionCode(14)
  Map<GamePlayer, List<AirMove>> getAlliedPendingMoves();

  /** Every nation's moves at the last reveal, which are public. */
  @RemoteActionCode(15)
  Map<GamePlayer, List<AirMove>> getLastReveal();

  /** The stage of {@link #getLastReveal()}. */
  @RemoteActionCode(16)
  Stage getLastRevealStage();

  @RemoteActionCode(6)
  @Override
  void initialize(String name, String displayName);

  @RemoteActionCode(10)
  @Override
  void setDelegateBridgeAndPlayer(IDelegateBridge delegateBridge);

  @RemoteActionCode(11)
  @Override
  void start();

  @RemoteActionCode(1)
  @Override
  void end();

  @RemoteActionCode(4)
  @Override
  String getName();

  @RemoteActionCode(3)
  @Override
  String getDisplayName();

  @RemoteActionCode(2)
  @Override
  IDelegateBridge getBridge();

  @RemoteActionCode(9)
  @Override
  Serializable saveState();

  @RemoteActionCode(7)
  @Override
  void loadState(Serializable state);

  @RemoteActionCode(5)
  @Override
  Class<? extends IRemote> getRemoteType();

  @RemoteActionCode(0)
  @Override
  boolean delegateCurrentlyRequiresUserInput();
}
