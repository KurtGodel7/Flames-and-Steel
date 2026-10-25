package games.strategy.triplea.delegate.remote;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.message.RemoteActionCode;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Flames and Steel (request 38 section 12): while a nation's screen is open in a simultaneous air
 * stage, it sends its choices as they change. The host passes them to that nation's allies only,
 * who see them on the map; enemies see nothing until the reveal.
 */
public interface IAirStageDelegate extends IRemote, IDelegate {
  /** The nation's choices so far in the open stage (not final: Done sends the final ones). */
  @RemoteActionCode(12)
  void draft(GamePlayer player, List<TargetAssignment> targets);

  /** The choices (final or still being made) of the nation's allies in the open stage. */
  @RemoteActionCode(13)
  Map<GamePlayer, List<TargetAssignment>> getAlliedChoices(GamePlayer player);

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
