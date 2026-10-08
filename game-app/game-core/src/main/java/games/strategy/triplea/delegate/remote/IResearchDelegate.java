package games.strategy.triplea.delegate.remote;

import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.message.RemoteActionCode;
import games.strategy.triplea.delegate.data.ResearchResults;
import java.io.Serializable;
import java.util.List;

/** Flames and Steel research centres: set each centre's focus and roll for breakthroughs. */
public interface IResearchDelegate extends IRemote, IDelegate {
  /**
   * Sets the focus of every research centre and rolls this turn's breakthroughs.
   *
   * @param focus one entry per research centre, "primary|secondary" (category names)
   * @param idleCenter index of the centre that sits idle while the capital is lost, otherwise -1
   */
  @RemoteActionCode(12)
  ResearchResults research(List<String> focus, int idleCenter);

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
