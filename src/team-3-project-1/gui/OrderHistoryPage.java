/** Displays today's orders and their item details. */

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Manager order history: today's order summaries and a popup of item quantities.
 */
public class OrderHistoryPage {
  final JPanel panel = new JPanel(new BorderLayout(0, 12));
  private final JFrame parent;
  private final OrderHistoryDAO dao;
  private final DefaultTableModel model = readOnlyModel(new String[] {"Order ID", "Time", "Total"},
    new Class<?>[] {Integer.class, String.class, Double.class});
  private final JTable table = PandaTheme.styledTable(model);
  private final JLabel status = PandaTheme.label(" ", PandaTheme.SMALL, PandaTheme.MUTED);

  /**
   * Builds the read-only order list with a refresh button.
   * @param parent parent frame used to center dialogs
   * @param conn open JDBC connection used for history queries
   */
  public OrderHistoryPage(JFrame parent, Connection conn) {
    this.parent = parent;
    dao = new OrderHistoryDAO(conn);

    JPanel heading = new JPanel();
    heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
    heading.setOpaque(false);
    heading.add(PandaTheme.label("Order History", PandaTheme.HEADING, PandaTheme.BLACK));
    heading.add(Box.createVerticalStrut(4));
    heading.add(PandaTheme.label(
      "Today's orders. Click an order to view its items.", PandaTheme.SMALL, PandaTheme.MUTED));

    JButton refresh = PandaTheme.secondaryButton("Refresh");
    refresh.addActionListener(e -> reload());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setOpaque(false);
    buttons.add(refresh);
    JPanel top = new JPanel(new BorderLayout());
    top.setOpaque(false);
    top.add(heading, BorderLayout.WEST);
    top.add(buttons, BorderLayout.EAST);

    table.getColumnModel().getColumn(2).setCellRenderer(PandaTheme.numberRenderer("$"));
    table.addMouseListener(new MouseAdapter() {
      // Opens the order under a single left click, ignoring empty table space
      public void mouseClicked(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && row >= 0) {
          showItems((Integer) model.getValueAt(table.convertRowIndexToModel(row), 0));
        }
      }
    });

    panel.setBackground(PandaTheme.WHITE);
    panel.setBorder(new EmptyBorder(16, 20, 16, 20));
    panel.add(top, BorderLayout.NORTH);
    panel.add(PandaTheme.scroll(table), BorderLayout.CENTER);
    panel.add(status, BorderLayout.SOUTH);
  }

  /**
   * Creates a typed table model whose cells cannot be edited.
   * @param columns column headers
   * @param types column classes used by the shared table renderers
   * @return a read-only DefaultTableModel
   */
  private static DefaultTableModel readOnlyModel(String[] columns, final Class<?>[] types) {
    return new DefaultTableModel(columns, 0) {
      // Keeps history cells read-only
      public boolean isCellEditable(int row, int col) {
        return false;
      }

      // Supplies column types for the shared table renderers
      public Class<?> getColumnClass(int col) {
        return types[col];
      }
    };
  }

  /**
   * Refreshes today's orders and shows an explicit empty or error state.
   */
  void reload() {
    model.setRowCount(0);
    try {
      List<Object[]> orders = dao.fetchToday();
      for (Object[] order : orders) {
        model.addRow(order);
      }
      status.setText(orders.isEmpty() ? "No orders today." : orders.size() + " orders today");
    } catch (SQLException ex) {
      status.setText("Unable to load today's orders. Click Refresh to try again.");
      JOptionPane.showMessageDialog(parent, "Error loading order history: " + ex.getMessage(),
        "Database Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  /**
   * Shows a modal popup containing only item names and quantities.
   * @param orderId OrderID whose items are shown
   */
  private void showItems(int orderId) {
    try {
      List<Object[]> items = dao.fetchItems(orderId);
      DefaultTableModel itemModel =
        readOnlyModel(new String[] {"Item", "Quantity", "Selections per meal"},
          new Class<?>[] {String.class, Long.class, String.class});
      for (Object[] item : items) {
        itemModel.addRow(item);
      }
      JTable itemTable = PandaTheme.styledTable(itemModel);
      itemTable.getColumnModel().getColumn(0).setPreferredWidth(180);
      itemTable.getColumnModel().getColumn(1).setPreferredWidth(70);
      itemTable.getColumnModel().getColumn(2).setPreferredWidth(390);
      itemTable.getColumnModel().getColumn(2).setCellRenderer(
        (t, value, selected, focus, row, col) -> {
          JTextArea details = new JTextArea(value == null ? "" : value.toString());
          details.setFont(t.getFont());
          details.setLineWrap(true);
          details.setWrapStyleWord(true);
          details.setBorder(new EmptyBorder(6, 8, 6, 8));
          details.setBackground(selected ? t.getSelectionBackground() : t.getBackground());
          details.setForeground(selected ? t.getSelectionForeground() : t.getForeground());
          details.setSize(t.getColumnModel().getColumn(col).getWidth(), Short.MAX_VALUE);
          int height = Math.max(36, details.getPreferredSize().height);
          if (t.getRowHeight(row) != height)
            t.setRowHeight(row, height);
          return details;
        });

      JDialog dialog = new JDialog(parent, "Order #" + orderId, true);
      dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
      JPanel content = new JPanel(new BorderLayout(0, 12));
      content.setBackground(PandaTheme.WHITE);
      content.setBorder(new EmptyBorder(16, 20, 16, 20));
      content.add(
        PandaTheme.label("Order #" + orderId + " Items", PandaTheme.HEADING, PandaTheme.BLACK),
        BorderLayout.NORTH);
      content.add(PandaTheme.scroll(itemTable), BorderLayout.CENTER);
      JPanel bottom = new JPanel(new BorderLayout());
      bottom.setOpaque(false);
      if (items.isEmpty()) {
        bottom.add(
          PandaTheme.label("No items found for this order.", PandaTheme.SMALL, PandaTheme.MUTED),
          BorderLayout.WEST);
      }
      JButton close = PandaTheme.primaryButton("Close");
      close.addActionListener(e -> dialog.dispose());
      bottom.add(close, BorderLayout.EAST);
      content.add(bottom, BorderLayout.SOUTH);
      dialog.setContentPane(content);
      dialog.getRootPane().setDefaultButton(close);
      dialog.setSize(760, 440);
      dialog.setLocationRelativeTo(parent);
      dialog.setVisible(true);
    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(parent, "Error loading order items: " + ex.getMessage(),
        "Database Error", JOptionPane.ERROR_MESSAGE);
    }
  }
}
