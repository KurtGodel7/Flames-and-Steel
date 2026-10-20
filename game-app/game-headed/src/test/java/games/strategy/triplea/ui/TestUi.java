package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;

/** Creates a UI context for tests and previews outside this package. */
public final class TestUi {
  private TestUi() {}

  public static UiContext create(final GameData data) {
    return new UiContext(data);
  }
}
