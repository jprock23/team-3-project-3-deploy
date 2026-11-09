/** Builds sales, ingredient usage, restock, and hourly sales reports. */

import java.awt.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Reports page split into quadrants.
 * Displays sales, daily profit margin, ingredient usage, restock needs, and hourly sales.
 */

public class ReportWithFourQuads {
  /** Reports page containing the heading and report grid. */
  public final JPanel panel = new JPanel(new BorderLayout(0, 12));
  /** Sales, profit margin, usage, restock, and X-report panels in display order. */
  public final JPanel[] quadrants = new JPanel[5];

  private final Connection conn;

  /**
   * Builds the reports page: heading on top and a 2x2 grid of reports.
   *
   * @param conn database connection used by the report queries
   */
  public ReportWithFourQuads(Connection conn) {
    this.conn = conn;
    JPanel heading = new JPanel();
    heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
    heading.setOpaque(false);
    heading.add(PandaTheme.label("Reports", PandaTheme.HEADING, PandaTheme.BLACK));

    JPanel grid = new JPanel(new GridLayout(2, 3, 12, 12));
    grid.setOpaque(false);

    quadrants[0] = makeSalesReport("Sales Report");
    quadrants[1] = makeProfitMarginReport("Daily Sales & Profit Margin");
    quadrants[2] = makeUsageReport("Usage Report");
    quadrants[3] = makeRestockReport("Restock Report");
    quadrants[4] = makeXReport("X-Report (Today)");

    grid.add(quadrants[0]);
    grid.add(quadrants[1]);
    grid.add(quadrants[2]);
    grid.add(quadrants[3]);
    grid.add(quadrants[4]);
    grid.add(makeNotesPanel());

    panel.setBackground(PandaTheme.WHITE);
    panel.setBorder(new EmptyBorder(16, 20, 16, 20));
    panel.add(heading, BorderLayout.NORTH);
    panel.add(grid, BorderLayout.CENTER);
  }

  /**
   * Fills the quadrant for the sales report: quantity sold, revenue, estimated
   * profit, and profit margin per menu item for a date and time window (within
   * one day or across several days). Profit and margin use the estimated
   * Inventory.UnitCost values from migrations/001_inventory_unit_costs.sql;
   * the Daily Sales & Profit Margin quadrant gives the same per day.
   *
   * @param title quadrant title
   * @return the quadrant panel
   */
  private JPanel makeSalesReport(String title) {
    JPanel q = new JPanel(new BorderLayout(0, 8));
    q.setBackground(PandaTheme.WHITE);
    q.setBorder(
      new CompoundBorder(new LineBorder(PandaTheme.GRID, 1), new EmptyBorder(12, 12, 12, 12)));

    // default window 6 days ago 00:00 through today 23:59
    LocalDate today = LocalDate.now();
    JSpinner startSpinner = new JSpinner(new SpinnerDateModel(
      java.util.Date.from(today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant()),
      null, null, java.util.Calendar.MINUTE));
    JSpinner endSpinner = new JSpinner(new SpinnerDateModel(
      java.util.Date.from(today.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant()), null,
      null, java.util.Calendar.MINUTE));
    startSpinner.setEditor(new JSpinner.DateEditor(startSpinner, "yyyy-MM-dd HH:mm"));
    endSpinner.setEditor(new JSpinner.DateEditor(endSpinner, "yyyy-MM-dd HH:mm"));

    JButton runButton = new JButton("Run");
    JLabel status = PandaTheme.label(" ", PandaTheme.BOLD, PandaTheme.MUTED);

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    controls.setOpaque(false);
    controls.add(new JLabel("From"));
    controls.add(startSpinner);
    controls.add(new JLabel("To"));
    controls.add(endSpinner);
    controls.add(runButton);

    JPanel header = new JPanel(new BorderLayout(0, 6));
    header.setOpaque(false);
    header.add(PandaTheme.label(title, PandaTheme.BOLD, PandaTheme.MUTED), BorderLayout.NORTH);
    header.add(controls, BorderLayout.CENTER);
    header.add(status, BorderLayout.SOUTH);
    q.add(header, BorderLayout.NORTH);

    DefaultTableModel tableModel =
      new DefaultTableModel(new String[] {"Item", "Qty Sold", "Revenue", "Est. Profit", "Margin"},
        0) {
        /** Returns whether this table cell permits editing. */
        public boolean isCellEditable(int row, int column) {
          return false;
        }
      };
    q.add(PandaTheme.scroll(new JTable(tableModel)), BorderLayout.CENTER);

    Runnable refresh = () -> {
      // whole minutes
      LocalDateTime start = ((java.util.Date) startSpinner.getValue())
                              .toInstant()
                              .atZone(ZoneId.systemDefault())
                              .toLocalDateTime()
                              .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
      LocalDateTime end = ((java.util.Date) endSpinner.getValue())
                            .toInstant()
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                            .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
      if (start.isAfter(end)) {
        status.setText("Start must be on or before end");
        return;
      }
      tableModel.setRowCount(0);

      // Meal selections count as portions; their revenue is included in the meal price.
      // EstCost is the item's recipe cost (QuantityRequired * Inventory.UnitCost) per unit sold.
      int totalQty = 0;
      double totalRevenue = 0;
      double totalProfit = 0;
      try (PreparedStatement ps =
             conn.prepareStatement("SELECT m.MenuItemName, SUM(oi.Qty) AS QtySold, "
               + "       SUM(oi.PaidQty * m.Price) AS Revenue, "
               + "       SUM(oi.Qty) * COALESCE((SELECT SUM(mii.QuantityRequired * i.UnitCost) "
               + "         FROM MenuItemInventory mii "
               + "         JOIN Inventory i ON i.InventoryID = mii.InventoryID "
               + "         WHERE mii.MenuItemID = m.MenuItemID), 0) AS EstCost "
               + "FROM Orders o "
               + "JOIN (SELECT OrderID, MenuItemID, Qty, Qty AS PaidQty FROM OrderItem "
               + "UNION ALL SELECT parent.OrderID, s.MenuItemID, parent.Qty * s.Qty, 0 "
               + "FROM OrderItemSelection s "
               + "JOIN OrderItem parent ON parent.OrderItemID = s.OrderItemID) oi "
               + "ON oi.OrderID = o.OrderID "
               + "JOIN MenuItem m   ON m.MenuItemID  = oi.MenuItemID "
               + "WHERE (o.OrderDate + o.OrderTime) >= ? "
               + "  AND (o.OrderDate + o.OrderTime) <  ? "
               + "GROUP BY m.MenuItemID, m.MenuItemName "
               + "ORDER BY Revenue DESC")) {
        ps.setTimestamp(1, java.sql.Timestamp.valueOf(start));
        ps.setTimestamp(2, java.sql.Timestamp.valueOf(end.plusMinutes(1)));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            int qty = rs.getInt("QtySold");
            double revenue = rs.getDouble("Revenue");
            double profit = revenue - rs.getDouble("EstCost");
            String margin = revenue > 0
              ? String.format("%.1f%%", 100.0 * profit / revenue) : "--";
            tableModel.addRow(new Object[] {rs.getString("MenuItemName"), qty,
              String.format("$%.2f", revenue), String.format("$%.2f", profit), margin});
            totalQty += qty;
            totalRevenue += revenue;
            totalProfit += profit;
          }
        }
      } catch (SQLException ex) {
        status.setText("Query failed: " + ex.getMessage());
        ex.printStackTrace();
        return;
      }

      status.setText(String.format(
        "%d items (including meal selections), $%.2f revenue, $%.2f est. profit",
        totalQty, totalRevenue, totalProfit));
    };

    runButton.addActionListener(e -> refresh.run());
    refresh.run();

    return q;
  }

  /**
   * Fills the quadrant for the daily sales and profit margin report: one row per
   * day with revenue from order history, an estimated ingredient cost, the
   * estimated profit, and the margin percentage.
   *
   * Ingredient cost is estimated from recipes (MenuItemInventory.QuantityRequired)
   * times the estimated Inventory.UnitCost added by migrations/001_inventory_unit_costs.sql.
   * Revenue is the recorded Orders.Total, which includes tax on customer web orders.
   *
   * @param title quadrant title
   * @return the quadrant panel
   */
  private JPanel makeProfitMarginReport(String title) {
    JPanel q = new JPanel(new BorderLayout(0, 8));
    q.setBackground(PandaTheme.WHITE);
    q.setBorder(
      new CompoundBorder(new LineBorder(PandaTheme.GRID, 1), new EmptyBorder(12, 12, 12, 12)));

    // default window 6 days ago through today
    LocalDate today = LocalDate.now();
    JSpinner startSpinner = new JSpinner(new SpinnerDateModel(
      java.util.Date.from(today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant()),
      null, null, java.util.Calendar.DAY_OF_MONTH));
    JSpinner endSpinner = new JSpinner(new SpinnerDateModel(
      java.util.Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant()), null,
      null, java.util.Calendar.DAY_OF_MONTH));
    startSpinner.setEditor(new JSpinner.DateEditor(startSpinner, "yyyy-MM-dd"));
    endSpinner.setEditor(new JSpinner.DateEditor(endSpinner, "yyyy-MM-dd"));

    JButton runButton = new JButton("Run");
    JLabel status = PandaTheme.label(" ", PandaTheme.BOLD, PandaTheme.MUTED);

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    controls.setOpaque(false);
    controls.add(new JLabel("From"));
    controls.add(startSpinner);
    controls.add(new JLabel("To"));
    controls.add(endSpinner);
    controls.add(runButton);

    JPanel header = new JPanel(new BorderLayout(0, 6));
    header.setOpaque(false);
    header.add(PandaTheme.label(title, PandaTheme.BOLD, PandaTheme.MUTED), BorderLayout.NORTH);
    header.add(controls, BorderLayout.CENTER);
    header.add(status, BorderLayout.SOUTH);
    q.add(header, BorderLayout.NORTH);

    DefaultTableModel tableModel =
      new DefaultTableModel(new String[] {"Date", "Revenue", "Est. Cost", "Est. Profit", "Margin"},
        0) {
        /** Returns whether this table cell permits editing. */
        public boolean isCellEditable(int row, int column) {
          return false;
        }
      };
    q.add(PandaTheme.scroll(new JTable(tableModel)), BorderLayout.CENTER);

    Runnable refresh = () -> {
      LocalDate start = ((java.util.Date) startSpinner.getValue())
                          .toInstant()
                          .atZone(ZoneId.systemDefault())
                          .toLocalDate();
      LocalDate end = ((java.util.Date) endSpinner.getValue())
                        .toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();
      if (start.isAfter(end)) {
        status.setText("Start must be on or before end");
        return;
      }
      tableModel.setRowCount(0);

      // revenue[0] and cost[1] per day
      java.util.TreeMap<LocalDate, double[]> days = new java.util.TreeMap<LocalDate, double[]>();
      try (PreparedStatement ps = conn.prepareStatement(
             "SELECT OrderDate AS day, SUM(Total) AS revenue "
             + "FROM Orders WHERE OrderDate >= ? AND OrderDate <= ? "
             + "GROUP BY OrderDate ORDER BY day")) {
        ps.setDate(1, java.sql.Date.valueOf(start));
        ps.setDate(2, java.sql.Date.valueOf(end));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            double[] sums = days.computeIfAbsent(rs.getDate("day").toLocalDate(),
              d -> new double[2]);
            sums[0] = rs.getDouble("revenue");
          }
        }

        // Meal selections consume their own recipes, scaled by the meals ordered.
        try (PreparedStatement costPs = conn.prepareStatement(
               "SELECT o.OrderDate AS day, "
               + "       SUM(oi.Qty * mii.QuantityRequired * i.UnitCost) AS cost "
               + "FROM Orders o "
               + "JOIN (SELECT OrderID, MenuItemID, Qty FROM OrderItem "
               + "UNION ALL SELECT parent.OrderID, s.MenuItemID, parent.Qty * s.Qty "
               + "FROM OrderItemSelection s "
               + "JOIN OrderItem parent ON parent.OrderItemID = s.OrderItemID) oi "
               + "ON oi.OrderID = o.OrderID "
               + "JOIN MenuItemInventory mii ON mii.MenuItemID = oi.MenuItemID "
               + "JOIN Inventory i           ON i.InventoryID  = mii.InventoryID "
               + "WHERE o.OrderDate >= ? AND o.OrderDate <= ? "
               + "GROUP BY o.OrderDate")) {
          costPs.setDate(1, java.sql.Date.valueOf(start));
          costPs.setDate(2, java.sql.Date.valueOf(end));
          try (ResultSet rs = costPs.executeQuery()) {
            while (rs.next()) {
              double[] sums = days.computeIfAbsent(rs.getDate("day").toLocalDate(),
                d -> new double[2]);
              sums[1] = rs.getDouble("cost");
            }
          }
        }
      } catch (SQLException ex) {
        status.setText("Query failed: " + ex.getMessage());
        ex.printStackTrace();
        return;
      }

      double totalRevenue = 0;
      double totalProfit = 0;
      for (java.util.Map.Entry<LocalDate, double[]> e : days.entrySet()) {
        double revenue = e.getValue()[0];
        double cost = e.getValue()[1];
        double profit = revenue - cost;
        String margin = revenue > 0 ? String.format("%.1f%%", 100.0 * profit / revenue) : "--";
        tableModel.addRow(new Object[] {e.getKey(), String.format("$%.2f", revenue),
          String.format("$%.2f", cost), String.format("$%.2f", profit), margin});
        totalRevenue += revenue;
        totalProfit += profit;
      }

      status.setText(String.format("%d days, $%.2f revenue, $%.2f estimated profit",
        days.size(), totalRevenue, totalProfit));
    };

    runButton.addActionListener(e -> refresh.run());
    refresh.run();

    return q;
  }

  /**
   * Fills the quadrant for the usage report for a date and time window
   * (within one day or across several days).
   *
   * @param title quadrant title
   * @return the quadrant panel
   */
  private JPanel makeUsageReport(String title) {
    JPanel q = new JPanel(new BorderLayout(0, 8));
    q.setBackground(PandaTheme.WHITE);
    q.setBorder(
      new CompoundBorder(new LineBorder(PandaTheme.GRID, 1), new EmptyBorder(12, 12, 12, 12)));

    // default window 6 days ago 00:00 through today 23:59
    LocalDate today = LocalDate.now();
    JSpinner startSpinner = new JSpinner(new SpinnerDateModel(
      java.util.Date.from(today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant()),
      null, null, java.util.Calendar.MINUTE));
    JSpinner endSpinner = new JSpinner(new SpinnerDateModel(
      java.util.Date.from(today.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant()), null,
      null, java.util.Calendar.MINUTE));
    startSpinner.setEditor(new JSpinner.DateEditor(startSpinner, "yyyy-MM-dd HH:mm"));
    endSpinner.setEditor(new JSpinner.DateEditor(endSpinner, "yyyy-MM-dd HH:mm"));

    JButton runButton = new JButton("Run");
    JLabel status = PandaTheme.label(" ", PandaTheme.BOLD, PandaTheme.MUTED);

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    controls.setOpaque(false);
    controls.add(new JLabel("From"));
    controls.add(startSpinner);
    controls.add(new JLabel("To"));
    controls.add(endSpinner);
    controls.add(runButton);

    JPanel header = new JPanel(new BorderLayout(0, 6));
    header.setOpaque(false);
    header.add(PandaTheme.label(title, PandaTheme.BOLD, PandaTheme.MUTED), BorderLayout.NORTH);
    header.add(controls, BorderLayout.CENTER);
    header.add(status, BorderLayout.SOUTH);
    q.add(header, BorderLayout.NORTH);

    DefaultTableModel tableModel =
      new DefaultTableModel(new String[] {"Item", "Unit", "Amount Used"}, 0) {
        /** Returns whether this table cell permits editing. */
        public boolean isCellEditable(int row, int column) {
          return false;
        }
      };
    q.add(PandaTheme.scroll(new JTable(tableModel)), BorderLayout.CENTER);

    Runnable refresh = () -> {
      // whole minutes
      LocalDateTime start = ((java.util.Date) startSpinner.getValue())
                              .toInstant()
                              .atZone(ZoneId.systemDefault())
                              .toLocalDateTime()
                              .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
      LocalDateTime end = ((java.util.Date) endSpinner.getValue())
                            .toInstant()
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                            .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
      if (start.isAfter(end)) {
        status.setText("Start must be on or before end");
        return;
      }
      if (conn == null) {
        status.setText("No database connection");
        return;
      }
      tableModel.setRowCount(0);

      // Include meal recipes and selected food, scaled by the number of meals ordered.
      int itemCount = 0;
      try (PreparedStatement ps = conn.prepareStatement("SELECT i.ItemName, i.Unit, "
             + "       SUM(oi.Qty * mii.QuantityRequired) AS AmountUsed "
             + "FROM Orders o "
             + "JOIN (SELECT OrderID, MenuItemID, Qty FROM OrderItem "
             + "UNION ALL SELECT parent.OrderID, s.MenuItemID, parent.Qty * s.Qty "
             + "FROM OrderItemSelection s "
             + "JOIN OrderItem parent ON parent.OrderItemID = s.OrderItemID) oi "
             + "ON oi.OrderID = o.OrderID "
             + "JOIN MenuItemInventory mii ON mii.MenuItemID = oi.MenuItemID "
             + "JOIN Inventory i           ON i.InventoryID  = mii.InventoryID "
             + "WHERE (o.OrderDate + o.OrderTime) >= ? "
             + "  AND (o.OrderDate + o.OrderTime) <  ? "
             + "GROUP BY i.InventoryID, i.ItemName, i.Unit "
             + "ORDER BY AmountUsed DESC")) {
        ps.setTimestamp(1, java.sql.Timestamp.valueOf(start));
        ps.setTimestamp(2, java.sql.Timestamp.valueOf(end.plusMinutes(1)));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            tableModel.addRow(new Object[] {rs.getString("ItemName"), rs.getString("Unit"),
              String.format("%.2f", rs.getDouble("AmountUsed"))});
            itemCount++;
          }
        }
      } catch (SQLException ex) {
        status.setText("Query failed: " + ex.getMessage());
        ex.printStackTrace();
        return;
      }

      status.setText(itemCount + " inventory items used");
    };

    runButton.addActionListener(e -> refresh.run());
    refresh.run();

    return q;
  }

  /**
   * Fills the quadrant for the restock report.
   *
   * @param title quadrant title
   * @return the quadrant panel
   */
  private JPanel makeRestockReport(String title) {
    JPanel q = new JPanel(new BorderLayout(0, 8));
    q.setBackground(PandaTheme.WHITE);
    q.setBorder(
      new CompoundBorder(new LineBorder(PandaTheme.GRID, 1), new EmptyBorder(12, 12, 12, 12)));

    JButton runButton = new JButton("Run");
    JLabel status = PandaTheme.label(" ", PandaTheme.BOLD, PandaTheme.MUTED);

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    controls.setOpaque(false);
    controls.add(runButton);
    controls.add(status);

    JPanel header = new JPanel(new BorderLayout(0, 6));
    header.setOpaque(false);
    header.add(PandaTheme.label(title, PandaTheme.BOLD, PandaTheme.MUTED), BorderLayout.NORTH);
    header.add(controls, BorderLayout.SOUTH);
    q.add(header, BorderLayout.NORTH);

    // makes a read-only table model for the restock report
    DefaultTableModel tableModel =
      new DefaultTableModel(new String[] {"Item", "Amount", "Minimum", "Unit"}, 0) {
        /** Returns whether this table cell permits editing. */
        public boolean isCellEditable(int row, int column) {
          return false;
        }
      };
    q.add(PandaTheme.scroll(new JTable(tableModel)), BorderLayout.CENTER);

    Runnable refresh = () -> {
      tableModel.setRowCount(0);

      // checks the inventory table for items that are at or below their threshold
      try (PreparedStatement ps =
             conn.prepareStatement("SELECT i.InventoryID, i.ItemName, i.Quantity, "
               + "       i." + InventoryDAO.THRESHOLD_COL + ", i.Unit "
               + "FROM Inventory i "
               + "WHERE i.Active = TRUE "
               + "AND i.Quantity <= i." + InventoryDAO.THRESHOLD_COL + " "
               + "ORDER BY (i." + InventoryDAO.THRESHOLD_COL + " - i.Quantity) DESC");
        ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          tableModel.addRow(new Object[] {rs.getString("ItemName"), rs.getDouble("Quantity"),
            rs.getDouble(InventoryDAO.THRESHOLD_COL), rs.getString("Unit")});
        }
      } catch (SQLException ex) {
        status.setText("Query failed: " + ex.getMessage());
        return;
      }

      // display the number of items that need restocking
      status.setText(tableModel.getRowCount() + " items need restocking");
    };

    runButton.addActionListener(e -> refresh.run());
    refresh.run();

    return q;
  }

  /**
   * Fills the remaining grid cell with notes about how the reports work.
   *
   * @return the notes panel
   */
  private JPanel makeNotesPanel() {
    JPanel q = new JPanel(new BorderLayout(0, 8));
    q.setBackground(PandaTheme.WHITE);
    q.setBorder(
      new CompoundBorder(new LineBorder(PandaTheme.GRID, 1), new EmptyBorder(12, 12, 12, 12)));
    q.add(PandaTheme.label("Notes", PandaTheme.BOLD, PandaTheme.MUTED), BorderLayout.NORTH);
    JLabel notes = PandaTheme.label("<html>Every report refreshes with its Run button."
      + "<br><br>Restock lists active items at or below their minimum threshold."
      + "<br><br>Profit margin uses the estimated ingredient unit costs in"
      + " Inventory.UnitCost (see migrations/001_inventory_unit_costs.sql), so treat it"
      + " as an estimate until real costs are entered.</html>",
      PandaTheme.BODY, PandaTheme.MUTED);
    notes.setVerticalAlignment(SwingConstants.TOP);
    q.add(notes, BorderLayout.CENTER);
    return q;
  }

  /**
   * Generates an X-Report showing hourly sales for the current day.
   * Read-only: does not modify orders, inventory, or register totals.
   *
   * @param title quadrant title
   * @return the quadrant panel
   */
  private JPanel makeXReport(String title) {
    JPanel q = new JPanel(new BorderLayout(0, 8));
    q.setBackground(PandaTheme.WHITE);
    q.setBorder(
      new CompoundBorder(new LineBorder(PandaTheme.GRID, 1), new EmptyBorder(12, 12, 12, 12)));

    JButton runButton = new JButton("Run");
    JLabel status = PandaTheme.label(" ", PandaTheme.BOLD, PandaTheme.MUTED);

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    controls.setOpaque(false);
    controls.add(runButton);
    controls.add(status);

    JPanel header = new JPanel(new BorderLayout(0, 6));
    header.setOpaque(false);
    header.add(PandaTheme.label(title, PandaTheme.BOLD, PandaTheme.MUTED), BorderLayout.NORTH);
    header.add(controls, BorderLayout.SOUTH);

    q.add(header, BorderLayout.NORTH);

    // Create line graph with 24 hours of data.
    XReportGraph chart = new XReportGraph();
    chart.setPreferredSize(new Dimension(500, 300));
    q.add(chart, BorderLayout.CENTER);

    Runnable refresh = () -> {
      // Reset sales before querying.
      double[] hourlySales = new double[24];

      LocalDate today = LocalDate.now();

      String sql = "SELECT EXTRACT(HOUR FROM OrderTime) AS Hour, "
        + "SUM(Total) AS TotalSales "
        + "FROM Orders "
        + "WHERE OrderDate = ? "
        + "GROUP BY EXTRACT(HOUR FROM OrderTime) "
        + "ORDER BY Hour";

      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setDate(1, java.sql.Date.valueOf(today));

        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            int hour = rs.getInt("Hour");
            double sales = rs.getDouble("TotalSales");

            if (hour >= 0 && hour < 24) {
              hourlySales[hour] = sales;
            }
          }
        }

        // Update graph.
        chart.setData(hourlySales);

        double total = 0;
        for (double sales : hourlySales) {
          total += sales;
        }

        status.setText(String.format("Today's Sales: $%.2f", total));

      } catch (SQLException ex) {
        status.setText("Query failed: " + ex.getMessage());
        ex.printStackTrace();
      }
    };

    runButton.addActionListener(e -> refresh.run());

    // Generate report when page opens.
    refresh.run();

    return q;
  }
}
