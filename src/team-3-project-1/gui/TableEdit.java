/** Tracks pending changes in an editable manager table model. */

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Table model with one editable number in column 1 and "Unsaved" as the last column.
 * Edits wait in pending (id -&gt; new value) until saved.
 *
 * @param <T> row item type
 */
public abstract class TableEdit<T> extends AbstractTableModel {
  final String[] cols;
  List<T> items = new ArrayList<T>();
  LinkedHashMap<Integer, Double> pending = new LinkedHashMap<Integer, Double>();
  Component parent;

  /**
   * Creates the table model with the given column names.
   *
   * @param cols column names
   */
  TableEdit(String[] cols) {
    this.cols = cols;
  }

  /** Returns the item's id. */
  abstract int id(T item);

  /** Returns the item's editable value. */
  abstract double value(T item);

  /**
   * Returns the item's value for a non-editable column.
   *
   * @param item row item
   * @param col column index
   * @return cell value
   */
  abstract Object cell(T item, int col);

  /**
   * Replaces the table's items with a new list.
   *
   * @param newItems new items
   */
  void setItems(List<T> newItems) {
    items = newItems;
    pending.clear();
    fireTableDataChanged();
  }

  /**
   * Checks if the specified row has pending edits.
   *
   * @param r row index
   * @return true if the row has pending edits
   */
  boolean isPending(int r) {
    return pending.containsKey(id(items.get(r)));
  }

  /**
   * Returns the number of rows.
   *
   * @return row count
   */
  public int getRowCount() {
    return items.size();
  }

  /**
   * Returns the number of columns.
   *
   * @return column count
   */
  public int getColumnCount() {
    return cols.length;
  }

  /**
   * Returns the name of the given column.
   *
   * @param c column index
   * @return column name
   */
  public String getColumnName(int c) {
    return cols[c];
  }

  /**
   * Returns the class of the given column (Double for the editable column, String otherwise).
   *
   * @param c column index
   * @return column class
   */
  public Class<?> getColumnClass(int c) {
    return c == 1 ? Double.class : String.class;
  }

  /**
   * Returns whether the given cell is editable (only column 1 is).
   *
   * @param r row index
   * @param c column index
   * @return true if editable
   */
  public boolean isCellEditable(int r, int c) {
    return c == 1;
  }

  /**
   * Returns the value for the specified cell, using pending edits if available.
   *
   * @param r row index
   * @param c column index
   * @return cell value
   */
  public Object getValueAt(int r, int c) {
    T it = items.get(r);
    int id = id(it);
    if (c == 1)
      return pending.containsKey(id) ? pending.get(id) : value(it);
    if (c == cols.length - 1)
      return pending.containsKey(id) ? "*" : "";

    /** Returns the display value for a noneditable table column. */
    return cell(it, c);
  }

  /**
   * Sets the value for the specified cell, storing it in pending edits if changed.
   *
   * @param v new value (Double)
   * @param r row index
   * @param c column index
   */
  public void setValueAt(Object v, int r, int c) {
    if (c != 1 || v == null)
      return;
    double d = (Double) v;
    if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) {
      JOptionPane.showMessageDialog(parent, cols[1] + " must be a non-negative number.");
      return;
    }
    d = Math.round(d * 100) / 100.0;
    T it = items.get(r);
    if (d == value(it))
      pending.remove(id(it));
    else
      pending.put(id(it), d);
    fireTableRowsUpdated(r, r);
  }
}
