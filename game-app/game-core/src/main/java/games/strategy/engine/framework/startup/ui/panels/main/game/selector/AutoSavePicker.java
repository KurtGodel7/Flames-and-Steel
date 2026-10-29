package games.strategy.engine.framework.startup.ui.panels.main.game.selector;

import games.strategy.engine.framework.RollingAutoSave;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

/**
 * Flames and Steel (request 45): the autosaves, newest first with readable names, loaded only when
 * the player asks (the start-up prompt of request 36 is gone). "Browse folder..." opens the normal
 * open window in the autosave folder.
 */
public final class AutoSavePicker {
  private AutoSavePicker() {}

  /** "Round 4, Japanese, Combat Move · 12 minutes ago", "(your network copy)" for a client's. */
  public static String label(final Path save, final long now) {
    final boolean copy = save.getParent() != null && save.getParent().endsWith("network-copy");
    return RollingAutoSave.describe(save)
        + " · "
        + ago(now - RollingAutoSave.modified(save))
        + (copy ? " (your network copy)" : "");
  }

  static String ago(final long millis) {
    final long minutes = Math.max(0, millis / 60_000);
    if (minutes < 1) {
      return "less than a minute ago";
    }
    if (minutes < 120) {
      return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
    }
    final long hours = minutes / 60;
    return hours < 48 ? hours + " hours ago" : hours / 24 + " days ago";
  }

  /** Shows the list; returns the save to load, or nothing if the player cancels. */
  public static Optional<Path> choose(final Component parent) {
    final List<Path> saves = RollingAutoSave.all();
    if (saves.isEmpty()) {
      JOptionPane.showMessageDialog(
          parent, "There are no autosaves yet.", "Open Autosave", JOptionPane.INFORMATION_MESSAGE);
      return Optional.empty();
    }
    final long now = System.currentTimeMillis();
    final JList<Path> list = new JList<>(saves.toArray(Path[]::new));
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setSelectedIndex(0);
    list.setVisibleRowCount(Math.min(15, saves.size()));
    list.setCellRenderer(
        new DefaultListCellRenderer() {
          private static final long serialVersionUID = 1L;

          @Override
          public Component getListCellRendererComponent(
              final JList<?> l,
              final Object value,
              final int index,
              final boolean selected,
              final boolean focus) {
            return super.getListCellRendererComponent(
                l, label((Path) value, now), index, selected, focus);
          }
        });

    final JDialog dialog =
        new JDialog(JOptionPane.getFrameForComponent(parent), "Open Autosave", true);
    final Path[] result = new Path[1];
    final JButton load = new JButton("Load");
    final JButton browse = new JButton("Browse folder...");
    final JButton cancel = new JButton("Cancel");
    load.addActionListener(
        e -> {
          result[0] = list.getSelectedValue();
          dialog.dispose();
        });
    browse.addActionListener(
        e -> {
          dialog.dispose();
          result[0] =
              GameFileSelector.selectAutoSave(JOptionPane.getFrameForComponent(parent))
                  .orElse(null);
        });
    cancel.addActionListener(e -> dialog.dispose());
    list.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
              load.doClick();
            }
          }
        });

    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(browse);
    buttons.add(load);
    buttons.add(cancel);
    final JPanel content = new JPanel(new BorderLayout(0, 6));
    content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    content.add(new JLabel("Autosaves, newest first:"), BorderLayout.NORTH);
    content.add(new JScrollPane(list), BorderLayout.CENTER);
    content.add(buttons, BorderLayout.SOUTH);
    dialog.setContentPane(content);
    dialog.getRootPane().setDefaultButton(load);
    dialog.pack();
    dialog.setLocationRelativeTo(parent);
    dialog.setVisible(true);
    return Optional.ofNullable(result[0]);
  }
}
