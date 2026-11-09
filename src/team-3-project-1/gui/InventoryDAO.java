/** Loads and updates inventory, menu items, and ingredient recipes. */

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Database calls for the manager GUI (model side of MVC).
 *
 *   fetchAll()          - every Inventory row, ordered by name
 *   updateQuantities()  - batch update of Quantity for a set of items (one transaction)
 *   insertItem()        - add a new Inventory row; InventoryID = MAX + 1
 *   fetchInactiveItems() - every inactive Inventory row, ordered by name
 *   reactivateInventoryItem() - restore a previously deleted Inventory row by setting its Active
 * flag to TRUE discontinueInventoryItem() - remove a previously active Inventory row by setting its
 * Active flag to FALSE fetchMenuItems()    - every MenuItem row, ordered by MenuItemID
 *   updatePrices()      - batch update of Price for a set of menu items (one transaction)
 *   insertMenuItem()    - add a new MenuItem row; MenuItemID = MAX + 1
 *   discontinueMenuItem() - remove a previously active MenuItem row by setting its Active flag to
 * FALSE
 *
 * A new price applies to orders placed after it is saved. Past orders are not
 * touched: their totals are already stored in Orders.Total.
 *
 * The threshold column is Minimum_Threshold in the live database
 * (see generate_base_data.cpp), even though the CHANGELOG calls it MinimumThreshold.
 */
public class InventoryDAO {
  /** Name of the threshold column in the live database. */
  static final String THRESHOLD_COL = "Minimum_Threshold";

  /**
   * One Inventory row.
   */
  public static class Item {
    /** InventoryID primary key. */
    public int id;

    /** ItemName. */
    public String name;

    /** Unit of measure. */
    public String unit;

    /** Minimum threshold before the item counts as low. */
    public double threshold;

    /** Quantity on hand. */
    public double quantity;

    /** Shelf life in days. */
    public int shelfLifeDays;

    /**
     * "low" means at or below the minimum threshold.
     * @return true if quantity is at or below the threshold
     */
    public boolean isLow() {
      return quantity <= threshold;
    }
  }

  /**
   * One MenuItem row.
   */
  public static class MenuItem {
    /** MenuItemID primary key. */
    public int id;

    /** MenuItemName. */
    public String name;

    /** Category (may be unset for legacy rows). */
    public String category;

    /** Price in dollars. */
    public double price;
  }

  private final Connection conn;

  /**
   * Creates a DAO over the manager's existing database connection.
   * @param conn open JDBC connection; ownership stays with the caller
   */
  public InventoryDAO(Connection conn) {
    this.conn = conn;
  }

  /**
   * Fetches all active inventory items from the database, ordered by name.
   * @return list of active inventory items
   * @throws SQLException if the query fails
   */
  public List<Item> fetchAll() throws SQLException {
    List<Item> items = new ArrayList<Item>();
    Statement st = conn.createStatement();
    ResultSet rs = st.executeQuery("SELECT InventoryID, ItemName, Unit, " + THRESHOLD_COL
      + ", Quantity, ShelfLifeDays "
      + "FROM Inventory "
      + "WHERE Active = TRUE "
      + "ORDER BY ItemName");
    while (rs.next()) {
      Item it = new Item();
      it.id = rs.getInt("InventoryID");
      it.name = rs.getString("ItemName");
      it.unit = rs.getString("Unit");
      it.threshold = rs.getDouble(THRESHOLD_COL);
      it.quantity = rs.getDouble("Quantity");
      it.shelfLifeDays = rs.getInt("ShelfLifeDays");
      items.add(it);
    }
    rs.close();
    st.close();
    return items;
  }

  /**
   * Updates the quantities of inventory items in a single transaction.
   * @param changes map of InventoryID to new Quantity
   * @throws SQLException if the batch update fails (transaction is rolled back)
   */
  public void updateQuantities(Map<Integer, Double> changes) throws SQLException {
    if (changes.isEmpty())
      return;
    try {
      conn.setAutoCommit(false);
      PreparedStatement ps =
        conn.prepareStatement("UPDATE Inventory SET Quantity = ? WHERE InventoryID = ?");
      for (Map.Entry<Integer, Double> e : changes.entrySet()) {
        ps.setDouble(1, e.getValue());
        ps.setInt(2, e.getKey());
        ps.addBatch();
      }
      ps.executeBatch();
      ps.close();
      conn.commit();
    } catch (SQLException ex) {
      try {
        conn.rollback();
      } catch (SQLException ignored) {
      }
      throw ex;
    } finally {
      try {
        conn.setAutoCommit(true);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Inserts a new inventory item into the database, ensuring no duplicate active items exist.
   * @param name item name (case-insensitive unique, even against inactive rows)
   * @param unit unit of measure
   * @param threshold minimum threshold
   * @param quantity starting quantity
   * @param shelfLifeDays shelf life in days
   * @return the new InventoryID (MAX + 1)
   * @throws SQLException if a row with the same name exists or the insert fails
   */
  public int insertItem(String name, String unit, double threshold, double quantity,
    int shelfLifeDays) throws SQLException {
    if (!conn.getAutoCommit()) {
      throw new SQLException("A transaction is already in progress.");
    }

    try {
      conn.setAutoCommit(false);

      // Do not allow another row with the same name, even if it is inactive
      // Inactive items should be restored through the Reactivate Item button
      try (PreparedStatement check = conn.prepareStatement("SELECT InventoryID, Active "
             + "FROM Inventory "
             + "WHERE LOWER(ItemName) = LOWER(?)")) {
        check.setString(1, name);

        try (ResultSet rs = check.executeQuery()) {
          if (rs.next()) {
            boolean active = rs.getBoolean("Active");

            if (active) {
              throw new SQLException(
                "An active inventory item named \"" + name + "\" already exists.");
            } else {
              throw new SQLException(
                "\"" + name + "\" is discontinued. Use the Reactivate Item button instead.");
            }
          }
        }
      }

      int id;

      try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(InventoryID), 0) + 1 FROM Inventory")) {
        rs.next();
        id = rs.getInt(1);
      }

      try (PreparedStatement insert = conn.prepareStatement("INSERT INTO Inventory ("
             + "InventoryID, ItemName, Unit, " + THRESHOLD_COL + ", Quantity, ShelfLifeDays, Active"
             + ") VALUES (?, ?, ?, ?, ?, ?, TRUE)")) {
        insert.setInt(1, id);
        insert.setString(2, name);
        insert.setString(3, unit);
        insert.setDouble(4, threshold);
        insert.setDouble(5, quantity);
        insert.setInt(6, shelfLifeDays);

        insert.executeUpdate();
      }

      conn.commit();

      return id;

    } catch (SQLException | RuntimeException ex) {
      try {
        conn.rollback();
      } catch (SQLException rollback) {
        ex.addSuppressed(rollback);
      }

      throw ex;

    } finally {
      try {
        conn.setAutoCommit(true);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Fetches all inactive inventory items from the database, ordered by name.
   * @return list of inactive inventory items
   * @throws SQLException if the query fails
   */
  public List<Item> fetchInactiveItems() throws SQLException {
    List<Item> items = new ArrayList<Item>();

    try (PreparedStatement ps = conn.prepareStatement("SELECT InventoryID, ItemName, Unit, "
           + THRESHOLD_COL + ", Quantity, ShelfLifeDays "
           + "FROM Inventory "
           + "WHERE Active = FALSE "
           + "ORDER BY ItemName");

      ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        Item it = new Item();

        it.id = rs.getInt("InventoryID");
        it.name = rs.getString("ItemName");
        it.unit = rs.getString("Unit");
        it.threshold = rs.getDouble(THRESHOLD_COL);
        it.quantity = rs.getDouble("Quantity");
        it.shelfLifeDays = rs.getInt("ShelfLifeDays");

        items.add(it);
      }
    }

    return items;
  }

  /**
   * Reactivates an inactive inventory item, setting its starting quantity and marking it as active.
   * @param inventoryId InventoryID of the inactive item
   * @param startingQuantity quantity to restock with
   * @throws SQLException if the item is not inactive or the update fails
   */
  public void reactivateInventoryItem(int inventoryId, double startingQuantity)
    throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("UPDATE Inventory "
           + "SET Quantity = ?, Active = TRUE "
           + "WHERE InventoryID = ? "
           + "AND Active = FALSE")) {
      ps.setDouble(1, startingQuantity);
      ps.setInt(2, inventoryId);

      int rowsUpdated = ps.executeUpdate();

      if (rowsUpdated == 0) {
        throw new SQLException("This inventory item could not be reactivated. "
          + "It may already be active.");
      }
    }
  }

  /**
   * Creates a menu item and its recipe atomically; reactivation preserves its recipe.
   * @param name menu item name (case-insensitive unique among active rows)
   * @param category menu category
   * @param price price in dollars
   * @param ingredients map of InventoryID to QuantityRequired for the recipe
   * @return the new (or reactivated) MenuItemID
   * @throws SQLException if a duplicate exists, an ingredient is invalid, or the insert fails
   */
  public int insertMenuItem(String name, String category, double price,
    Map<Integer, Double> ingredients) throws SQLException {
    if (!conn.getAutoCommit())
      throw new SQLException("A transaction is already in progress.");
    try {
      conn.setAutoCommit(false);
      Integer existingId = null;
      try (PreparedStatement check = conn.prepareStatement(
             "SELECT MenuItemID, Active FROM MenuItem WHERE LOWER(MenuItemName) = LOWER(?)")) {
        check.setString(1, name);
        try (ResultSet rs = check.executeQuery()) {
          while (rs.next()) {
            if (rs.getBoolean("Active")) {
              throw new SQLException("An active menu item named \"" + name + "\" already exists.");
            }
            if (existingId != null)
              throw new SQLException("Multiple discontinued items have this name.");
            existingId = rs.getInt("MenuItemID");
          }
        }
      }
      if (existingId != null) {
        try (PreparedStatement ps = conn.prepareStatement(
               "UPDATE MenuItem SET Price = ?, Category = ?, Active = TRUE WHERE MenuItemID = ?")) {
          ps.setDouble(1, price);
          ps.setString(2, category);
          ps.setInt(3, existingId);
          ps.executeUpdate();
        }
        conn.commit();
        return existingId;
      }

      // Validate and lock selected ingredients until the recipe is saved
      try (PreparedStatement check = conn.prepareStatement(
             "SELECT Active FROM Inventory WHERE InventoryID = ? FOR UPDATE")) {
        for (Map.Entry<Integer, Double> e : ingredients.entrySet()) {
          double qty = e.getValue();
          if (!Double.isFinite(qty) || qty <= 0 || qty > 9999999.999
            || java.math.BigDecimal.valueOf(qty).stripTrailingZeros().scale() > 3) {
            throw new SQLException(
              "Ingredient quantities must be positive with at most three decimal places.");
          }
          check.setInt(1, e.getKey());
          try (ResultSet rs = check.executeQuery()) {
            if (!rs.next() || !rs.getBoolean("Active")) {
              throw new SQLException("A selected ingredient is no longer active. Reopen the form.");
            }
          }
        }
      }
      int id;
      try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(MenuItemID), 0) + 1 FROM MenuItem")) {
        rs.next();
        id = rs.getInt(1);
      }
      try (PreparedStatement ps =
             conn.prepareStatement("INSERT INTO MenuItem (MenuItemID, MenuItemName, Category, "
               + "Price, Active) VALUES (?, ?, ?, ?, TRUE)")) {
        ps.setInt(1, id);
        ps.setString(2, name);
        ps.setString(3, category);
        ps.setDouble(4, price);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
             conn.prepareStatement("INSERT INTO MenuItemInventory (MenuItemID, InventoryID, "
               + "QuantityRequired) VALUES (?, ?, ?)")) {
        for (Map.Entry<Integer, Double> e : ingredients.entrySet()) {
          ps.setInt(1, id);
          ps.setInt(2, e.getKey());
          ps.setDouble(3, e.getValue());
          ps.addBatch();
        }
        ps.executeBatch();
      }
      conn.commit();
      return id;
    } catch (SQLException | RuntimeException ex) {
      try {
        conn.rollback();
      } catch (SQLException rollback) {
        ex.addSuppressed(rollback);
      }
      throw ex;
    } finally {
      conn.setAutoCommit(true);
    }
  }

  /**
   * Inserts a new menu item without any ingredients.
   * @param name menu item name
   * @param price price in dollars
   * @param category menu category
   * @return the new MenuItemID
   * @throws SQLException if the insert fails
   */
  public int insertMenuItem(String name, double price, String category) throws SQLException {
    return insertMenuItem(name, category, price, java.util.Collections.<Integer, Double>emptyMap());
  }

  /**
   * Fetches all active menu items from the database, ordered by ID.
   * @return list of active menu items
   * @throws SQLException if the query fails
   */
  public List<MenuItem> fetchMenuItems() throws SQLException {
    List<MenuItem> items = new ArrayList<MenuItem>();
    Statement st = conn.createStatement();
    ResultSet rs = st.executeQuery("SELECT MenuItemID, MenuItemName, Price FROM MenuItem WHERE "
      + "Active = TRUE ORDER BY MenuItemID");
    while (rs.next()) {
      MenuItem it = new MenuItem();
      it.id = rs.getInt("MenuItemID");
      it.name = rs.getString("MenuItemName");
      it.price = rs.getDouble("Price");
      items.add(it);
    }
    rs.close();
    st.close();
    return items;
  }

  /**
   * Updates the prices of multiple menu items in a single transaction.
   * @param changes map of MenuItemID to new Price
   * @throws SQLException if the batch update fails (transaction is rolled back)
   */
  public void updatePrices(Map<Integer, Double> changes) throws SQLException {
    if (changes.isEmpty())
      return;
    try {
      conn.setAutoCommit(false);
      PreparedStatement ps =
        conn.prepareStatement("UPDATE MenuItem SET Price = ? WHERE MenuItemID = ?");
      for (Map.Entry<Integer, Double> e : changes.entrySet()) {
        ps.setDouble(1, e.getValue());
        ps.setInt(2, e.getKey());
        ps.addBatch();
      }
      ps.executeBatch();
      ps.close();
      conn.commit();
    } catch (SQLException ex) {
      try {
        conn.rollback();
      } catch (SQLException ignored) {
      }
      throw ex;
    } finally {
      try {
        conn.setAutoCommit(true);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Discontinues a menu item by setting its Active flag to FALSE.
   * @param menuItemId MenuItemID to discontinue
   * @throws SQLException if no row matches or the update fails
   */
  public void discontinueMenuItem(int menuItemId) throws SQLException {
    PreparedStatement ps =
      conn.prepareStatement("UPDATE MenuItem SET Active = FALSE WHERE MenuItemID = ?");

    ps.setInt(1, menuItemId);

    int rowsUpdated = ps.executeUpdate();
    ps.close();

    if (rowsUpdated == 0) {
      throw new SQLException("No menu item found with ID " + menuItemId);
    }
  }

  /**
   * Discontinues an inventory item by setting its Active flag to FALSE.
   * @param inventoryId InventoryID to discontinue
   * @throws SQLException if no row matches or the update fails
   */
  public void discontinueInventoryItem(int inventoryId) throws SQLException {
    PreparedStatement ps =
      conn.prepareStatement("UPDATE Inventory SET Active = FALSE WHERE InventoryID = ?");

    ps.setInt(1, inventoryId);

    int rowsUpdated = ps.executeUpdate();
    ps.close();

    if (rowsUpdated == 0) {
      throw new SQLException("No inventory item found with ID " + inventoryId);
    }
  }
}
