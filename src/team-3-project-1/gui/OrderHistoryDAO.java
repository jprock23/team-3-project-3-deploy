/** Queries today's orders and their recorded item selections. */

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only database queries for today's orders and their item quantities.
 */
public class OrderHistoryDAO {
  private final Connection conn;

  /**
   * Uses the manager's existing database connection.
   * @param conn open JDBC connection; ownership stays with the caller
   */
  public OrderHistoryDAO(Connection conn) {
    this.conn = conn;
  }

  /**
   * Loads today's orders, newest first, using the database's checkout date.
   * @return rows of {OrderID, time made, total}
   * @throws SQLException if the query fails
   */
  public List<Object[]> fetchToday() throws SQLException {
    List<Object[]> orders = new ArrayList<Object[]>();
    try (Statement st = conn.createStatement();
      ResultSet rs =
        st.executeQuery("SELECT OrderID, TO_CHAR(OrderTime, 'HH12:MI:SS AM') AS TimeMade, Total "
          + "FROM Orders WHERE OrderDate = CURRENT_DATE ORDER BY OrderTime DESC, OrderID DESC")) {
      while (rs.next()) {
        orders.add(
          new Object[] {rs.getInt("OrderID"), rs.getString("TimeMade"), rs.getDouble("Total")});
      }
    }
    return orders;
  }

  /**
   * Loads item names and quantities, including discontinued menu items.
   * @param orderId OrderID to load items for
   * @return rows of {item name, quantity, per-meal selections}
   * @throws SQLException if the query fails
   */
  public List<Object[]> fetchItems(int orderId) throws SQLException {
    List<Object[]> items = new ArrayList<Object[]>();
    try (
      PreparedStatement ps =
        conn.prepareStatement("SELECT m.MenuItemName, oi.Qty, COALESCE((SELECT STRING_AGG("
          + "s.Qty::text || 'x ' || sm.MenuItemName, CHR(10) ORDER BY sm.Category DESC, "
          + "sm.MenuItemName) "
          + "FROM OrderItemSelection s JOIN MenuItem sm ON sm.MenuItemID = s.MenuItemID "
          + "WHERE s.OrderItemID = oi.OrderItemID), "
          + "CASE WHEN m.EntreeCount > 0 THEN 'Selections not recorded' ELSE '' END) AS Selections "
          + "FROM OrderItem oi JOIN MenuItem m ON m.MenuItemID = oi.MenuItemID "
          + "WHERE oi.OrderID = ? ORDER BY oi.OrderItemID")) {
      ps.setInt(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          items.add(new Object[] {
            rs.getString("MenuItemName"), rs.getLong("Qty"), rs.getString("Selections")});
        }
      }
    }
    return items;
  }
}
