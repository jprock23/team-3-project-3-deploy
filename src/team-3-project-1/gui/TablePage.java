/** Builds manager tables with loading, editing, and saving controls. */

import java.sql.SQLException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.Map;

/**
 * Editable table page: title, optional note, table, unsaved counter, Refresh and Save.
 * Subclasses define load() and store() for the edited column.
 *
 * @param <T> row item type
 */
public abstract class TablePage<T> {
  final Component parent;
  final String title, noun;
  final TableEdit<T> model;
  final JTable table;
  final JPanel panel = new JPanel(new BorderLayout(0, 12));
  final JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
  final JLabel unsavedLabel = PandaTheme.label(" ", PandaTheme.SMALL, PandaTheme.MUTED);

  /**
   * Builds the table page UI around the given model.
   *
   * @param parent parent component for dialogs
   * @param title page title
   * @param note optional note shown under the title
   * @param noun item noun used in the unsaved counter
   * @param m table model
   * @param prefix number prefix for the edited column (e.g. "$")
   * @param widths preferred column widths
   */
  TablePage(Component parent, String title, String note, String noun, TableEdit<T> m, String prefix,
    int[] widths) {
    this.parent = parent;
    this.title = title;
    this.noun = noun;
    model = m;
    m.parent = parent;
    table = PandaTheme.styledTable(m);
    table.getColumnModel().getColumn(1).setCellRenderer(PandaTheme.numberRenderer(prefix));
    table.getColumnModel()
      .getColumn(widths.length - 1)
      .setCellRenderer(PandaTheme.unsavedRenderer());
    for (int i = 0; i < widths.length; i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }

    JPanel heading = new JPanel();
    heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
    heading.setOpaque(false);
    heading.add(PandaTheme.label(title, PandaTheme.HEADING, PandaTheme.BLACK));
    if (note != null) {
      heading.add(Box.createVerticalStrut(4));
      heading.add(PandaTheme.label(note, PandaTheme.SMALL, PandaTheme.MUTED));
    }
    topButtons.setOpaque(false);
    JPanel top = new JPanel(new BorderLayout());
    top.setOpaque(false);
    top.add(heading, BorderLayout.WEST);
    top.add(topButtons, BorderLayout.EAST);

    JButton refresh = PandaTheme.secondaryButton("Refresh");
    refresh.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        refresh();
      }
    });
    JButton save = PandaTheme.primaryButton("Save Changes");
    save.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        save();
      }
    });
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setOpaque(false);
    buttons.add(refresh);
    buttons.add(save);
    JPanel bottom = new JPanel(new BorderLayout());
    bottom.setOpaque(false);
    bottom.add(unsavedLabel, BorderLayout.WEST);
    bottom.add(buttons, BorderLayout.EAST);

    model.addTableModelListener(new TableModelListener() {
      /** Updates the unsaved-change indicator after table edits. */
      public void tableChanged(TableModelEvent e) {
        updateUnsavedLabel();
      }
    });

    panel.setBackground(PandaTheme.WHITE);
    panel.setBorder(new EmptyBorder(16, 20, 16, 20));
    panel.add(top, BorderLayout.NORTH);
    panel.add(PandaTheme.scroll(table), BorderLayout.CENTER);
    panel.add(bottom, BorderLayout.SOUTH);
  }

  /**
   * Loads the list of items from the database.
   *
   * @return current items
   * @throws SQLException if the query fails
   */
  abstract List<T> load() throws SQLException;

  /**
   * Stores the changes to the database.
   *
   * @param changes pending edits (id -&gt; new value)
   * @throws SQLException if the update fails
   */
  abstract void store(Map<Integer, Double> changes) throws SQLException;

  /**
   * Override to ask before saving.
   *
   * @param n number of pending changes
   * @return true to proceed with the save
   */
  boolean confirmSave(int n) {
    return true;
  }

  /** Stops any ongoing cell editing in the table. */
  void stopEditing() {
    if (table.isEditing())
      table.getCellEditor().stopCellEditing();
  }

  /**
   * Checks if there are unsaved changes in the table.
   *
   * @return true if there are pending edits
   */
  boolean hasUnsaved() {
    return !model.pending.isEmpty();
  }

  /** Reloads the table from the database. */
  void reload() {
    try {
      model.setItems(load());
    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(
        parent, "Error loading " + title.toLowerCase() + ": " + ex.getMessage());
    }
  }

  /** Refreshes the table, optionally discarding unsaved changes. */
  void refresh() {
    stopEditing();
    if (!hasUnsaved()
      || JOptionPane.showConfirmDialog(
           parent, "Discard unsaved changes?", "Refresh", JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION) {
      reload();
    }
  }

  /** Saves the changes to the database. */
  void save() {
    stopEditing();
    int n = model.pending.size();
    if (n == 0) {
      JOptionPane.showMessageDialog(parent, "No changes to save.");
      return;
    }
    if (!confirmSave(n))
      return;
    try {
      store(model.pending);
      reload();
      JOptionPane.showMessageDialog(parent, "Saved " + n + " change(s).");
    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(parent, "Save failed: " + ex.getMessage());
    }
  }

  /** Updates the label showing the number of unsaved changes. */
  void updateUnsavedLabel() {
    int n = model.pending.size();
    if (n == 0) {
      unsavedLabel.setText(model.getRowCount() + " " + noun);
      unsavedLabel.setForeground(PandaTheme.MUTED);
    } else {
      unsavedLabel.setText(n + " unsaved change" + (n == 1 ? "" : "s"));
      unsavedLabel.setForeground(PandaTheme.RED_DARK);
    }
  }
}
