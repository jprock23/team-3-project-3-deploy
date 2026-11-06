/** Defines shared colors, fonts, controls, and table renderers. */

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Colors, fonts, buttons and table styling shared by all GUI windows.
 * RED and GREY match CashierGUI.
 */
public class PandaTheme {
  // same as CashierGUI
  static final Color RED = new Color(0xC8, 0x3A, 0x2E);
  static final Color GREY = new Color(0xC7, 0xCE, 0xD6);
  static final Color WHITE = Color.WHITE;
  static final Color BLACK = Color.BLACK;

  // shades of the above
  static final Color RED_DARK = new Color(0xA1, 0x2E, 0x24); // red hover
  static final Color RED_LIGHT = new Color(0xF6, 0xDC, 0xD9); // unsaved rows
  static final Color GREY_DARK = new Color(0xB0, 0xB8, 0xC1); // grey hover, borders
  static final Color GREY_LIGHT = new Color(0xEE, 0xF1, 0xF4); // row stripes
  static final Color GRID = new Color(0xDD, 0xE2, 0xE7);
  static final Color MUTED = new Color(0x5F, 0x67, 0x70);

  // status
  static final Color OK_GREEN = new Color(0x2E, 0x7D, 0x32);

  static final String FONT = "Segoe UI";
  static final Font TITLE = new Font(FONT, Font.BOLD, 22);
  static final Font HEADING = new Font(FONT, Font.BOLD, 18);
  static final Font BODY = new Font(FONT, Font.PLAIN, 14);
  static final Font BOLD = new Font(FONT, Font.BOLD, 14);
  static final Font SMALL = new Font(FONT, Font.PLAIN, 12);

  /** Windows/Mac native look for everything not styled by hand (dialogs, scrollbars). */
  static void useSystemLook() {
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (Exception e) { /* keep the current look */
    }
  }

  /** Java's default look; CashierGUI was designed against this one. */
  static void useJavaLook() {
    try {
      UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
    } catch (Exception e) { /* keep the current look */
    }
  }

  /**
   * Flat colored button that darkens on hover.
   *
   * @param text  button text
   * @param bg    background color
   * @param fg    text color
   * @param hover background color while hovered
   * @return the styled button
   */
  static JButton button(String text, Color bg, Color fg, Color hover) {
    final JButton b = new JButton(text) {
      /** Paints this control using its current state. */
      protected void paintComponent(Graphics g) {
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(g);
      }
    };
    b.setFont(BOLD);
    b.setForeground(fg);
    b.setBackground(bg);
    b.setContentAreaFilled(false);
    b.setFocusPainted(false);
    b.setBorderPainted(false);
    b.setBorder(new EmptyBorder(8, 18, 8, 18));
    b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    b.putClientProperty("baseColor", bg);
    b.putClientProperty("hoverColor", hover);
    b.addMouseListener(new MouseAdapter() {
      /** Applies the hover appearance when the pointer enters. */
      public void mouseEntered(MouseEvent e) {
        b.setBackground((Color) b.getClientProperty("hoverColor"));
      }

      /** Restores the control appearance when the pointer leaves. */
      public void mouseExited(MouseEvent e) {
        b.setBackground((Color) b.getClientProperty("baseColor"));
      }
    });
    return b;
  }

  /**
   * Primary button with red background and white text.
   *
   * @param text button text
   * @return the styled button
   */
  static JButton primaryButton(String text) {
    return button(text, RED, WHITE, RED_DARK);
  }

  /**
   * Secondary button with grey background and black text.
   *
   * @param text button text
   * @return the styled button
   */
  static JButton secondaryButton(String text) {
    return button(text, GREY, BLACK, GREY_DARK);
  }

  /**
   * Sets the base colors for a button (background, foreground, hover).
   *
   * @param b     button created by {@link #button}
   * @param bg    background color
   * @param fg    text color
   * @param hover background color while hovered
   */
  static void setBase(JButton b, Color bg, Color fg, Color hover) {
    b.putClientProperty("baseColor", bg);
    b.putClientProperty("hoverColor", hover);
    b.setBackground(bg);
    b.setForeground(fg);
  }

  /**
   * Creates a label with the specified text, font, and color.
   *
   * @param text  label text
   * @param font  label font
   * @param color text color
   * @return the styled label
   */
  static JLabel label(String text, Font font, Color color) {
    JLabel l = new JLabel(text);
    l.setFont(font);
    l.setForeground(color);
    return l;
  }

  /**
   * Creates a text field with the specified number of columns.
   *
   * @param columns width in columns
   * @return the styled text field
   */
  static JTextField textField(int columns) {
    JTextField f = new JTextField(columns);
    f.setFont(BODY);
    f.setForeground(BLACK);
    f.setBackground(WHITE);
    f.setBorder(new CompoundBorder(new LineBorder(GREY_DARK, 1), new EmptyBorder(5, 8, 5, 8)));
    return f;
  }

  /**
   * Creates a styled JTable with the specified table model.
   *
   * @param m editable table model
   * @return the styled table
   */
  static JTable styledTable(final TableEdit<?> m) {
    return styledTable((javax.swing.table.AbstractTableModel) m);
  }

  /**
   * Creates a styled table for read-only models as well as editable models.
   * Pending rows of a TableEdit model are highlighted; other rows stripe.
   *
   * @param m table model
   * @return the styled table
   */
  static JTable styledTable(final javax.swing.table.AbstractTableModel m) {
    JTable t = new JTable(m) {
      /** Applies the shared table styling to the rendered cell. */
      public Component prepareRenderer(TableCellRenderer r, int row, int col) {
        Component c = super.prepareRenderer(r, row, col);
        if (!isRowSelected(row)) {
          if (m instanceof TableEdit<?>
            && ((TableEdit<?>) m).isPending(convertRowIndexToModel(row)))
            c.setBackground(RED_LIGHT);
          else
            c.setBackground(row % 2 == 0 ? WHITE : GREY_LIGHT);
        }
        return c;
      }
    };
    t.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    t.setFont(BODY);
    t.setRowHeight(28);
    t.setForeground(BLACK);
    t.setGridColor(GRID);
    t.setShowVerticalLines(false);
    t.setSelectionBackground(GREY);
    t.setSelectionForeground(BLACK);
    t.setFillsViewportHeight(true);
    t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    DefaultTableCellRenderer header = new DefaultTableCellRenderer() {
      /** Formats and styles the value shown in this table cell. */
      public Component getTableCellRendererComponent(
        JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
        super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
        setBackground(GREY);
        setForeground(BLACK);
        setFont(BOLD);
        int align = SwingConstants.LEFT;
        TableCellRenderer cr = tb.getColumnModel().getColumn(col).getCellRenderer();
        if (cr instanceof JLabel)
          align = ((JLabel) cr).getHorizontalAlignment();
        setHorizontalAlignment(align);
        setBorder(new EmptyBorder(0, 8, 0, align == SwingConstants.RIGHT ? 12 : 8));
        return this;
      }
    };
    t.getTableHeader().setDefaultRenderer(header);
    t.getTableHeader().setReorderingAllowed(false);
    t.getTableHeader().setPreferredSize(new Dimension(0, 32));

    // left padding on text cells
    DefaultTableCellRenderer text = new DefaultTableCellRenderer() {
      /** Formats and styles the value shown in this table cell. */
      public Component getTableCellRendererComponent(
        JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
        super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
        setBorder(new EmptyBorder(0, 8, 0, 8));
        return this;
      }
    };
    t.setDefaultRenderer(String.class, text);
    return t;
  }

  /**
   * Right-aligned number with two decimals.
   *
   * @param prefix text prepended to the number (e.g. "$")
   * @return the cell renderer
   */
  static DefaultTableCellRenderer numberRenderer(final String prefix) {
    DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
      /** Formats and styles the value shown in this table cell. */
      public Component getTableCellRendererComponent(
        JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
        super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
        if (v instanceof Double)
          setText(prefix + String.format("%.2f", (Double) v));
        setBorder(new EmptyBorder(0, 8, 0, 12));
        return this;
      }
    };
    r.setHorizontalAlignment(SwingConstants.RIGHT);
    return r;
  }

  /**
   * Shows "unsaved" in a row whose value is "*".
   *
   * @return the cell renderer
   */
  static DefaultTableCellRenderer unsavedRenderer() {
    DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
      /** Formats and styles the value shown in this table cell. */
      public Component getTableCellRendererComponent(
        JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
        super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
        setText("*".equals(v) ? "unsaved" : "");
        setForeground(RED_DARK);
        setFont(SMALL);
        return this;
      }
    };
    r.setHorizontalAlignment(SwingConstants.CENTER);
    return r;
  }

  /**
   * Red "LOW" / green "OK" with a dot.
   *
   * @return the cell renderer
   */
  static DefaultTableCellRenderer statusRenderer() {
    return new DefaultTableCellRenderer() {
      /** Formats and styles the value shown in this table cell. */
      public Component getTableCellRendererComponent(
        JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
        super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
        boolean low = "LOW".equals(v);
        setText(low ? "●  LOW" : "●  OK");
        setForeground(low ? RED : OK_GREEN);
        setFont(BOLD);
        setBorder(new EmptyBorder(0, 8, 0, 8));
        return this;
      }
    };
  }

  /**
   * Creates a JScrollPane with the specified JTable and styled borders.
   *
   * @param t the table to wrap
   * @return the styled scroll pane
   */
  static JScrollPane scroll(JTable t) {
    JScrollPane sp = new JScrollPane(t);
    sp.setBorder(new LineBorder(GRID, 1));
    sp.getViewport().setBackground(WHITE);
    sp.getVerticalScrollBar().setUnitIncrement(16);
    return sp;
  }
}
