package games.strategy.triplea.delegate.remote;

import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.message.RemoteActionCode;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import java.io.Serializable;
import java.util.List;
import javax.annotation.Nullable;

/** Flames and Steel: a nation sends its surviving bombers against targets. */
public interface IAirCombatDelegate extends IRemote, IDelegate {
  /**
   * Submits this nation's bomber targets for this round.
   *
   * @return why the targets are refused, or null if accepted
   */
  @RemoteActionCode(12)
  @Nullable
  String assignTargets(List<TargetAssignment> assignments);

  /** This nation's targets stored so far this step (after a save and load, say). */
  @RemoteActionCode(13)
  List<TargetAssignment> getPendingAssignments();

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
