package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ui.panels.map.MapPanel;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Flames and Steel (request 16): what the Actions tab shows during a step that has no panel of its
 * own: steps no nation plays (dogfights, strikes), steps played in their own window (research, APC
 * production) and other players' steps. It replaces the last step's panel, whose buttons must not
 * stay on screen. It has no buttons.
 */
public class StepInfoPanel extends ActionPanel {
  private static final long serialVersionUID = 1L;
  private final JLabel detail = createIndentedLabel();

  StepInfoPanel(final GameData data, final MapPanel map) {
    super(data, map);
  }

  /** "Germans · Research" with a line below, e.g. "Choose in the research window." */
  void setStep(final @Nullable GamePlayer player, final String step, final String detailText) {
    SwingUtilities.invokeLater(
        () -> {
          removeAll();
          actionLabel.setText(
              (player == null || player.isNull() ? "" : player.getName() + " · ") + step);
          detail.setText(detailText);
          add(actionLabel);
          add(detail);
          refresh.run();
        });
  }

  /** The text shown, for tests. */
  String getText() {
    return actionLabel.getText() + " / " + detail.getText();
  }

  @Override
  public void performDone() {
    // nothing to finish: the step ends by itself
  }

  @Override
  public String toString() {
    return "StepInfoPanel";
  }
}
