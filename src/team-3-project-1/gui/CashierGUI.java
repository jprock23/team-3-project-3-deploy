/** Provides cashier ordering, meal selection, and transactional checkout. */

import java.sql.*;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cashier GUI for team 3 project (Panda Express style POS).
 *
 * Layout follows the cashier GUI sketch:
 *   - Top nav bar:  MENU | INV. | REP.  (INV./REP. are manager only)
 *   - Left:  category buttons (Appetizers, Meals, Drinks, Extras)
 *            whose item buttons are pulled live from the MenuItem table.
 *   - Right: running order ticket (qty, name, price), Subtotal / Tax / Total,
 *            and a red Checkout button.
 *
 * On Checkout (single transaction):
 *   1. INSERT INTO Orders (OrderID, OrderDate, OrderTime, Total)
 *   2. INSERT INTO OrderItem for each ticket line
 *   3. INSERT INTO OrderItemSelection for meal choices
 *   4. UPDATE Inventory ... FROM MenuItemInventory for meals and selected food
 * Items with a MenuItemInventory mapping are checked for availability before
 * they can be added to the ticket.
 *
 * Styling comes from PandaTheme so the cashier side matches ManagerGUI.
 */
public class CashierGUI {
  static Connection conn = null;

  static final double TAX_RATE = 0.0825;

  // 4-digit PIN required to switch from the cashier side to the manager side
  static final String MANAGER_PIN = "1234";

  // Size suffixes used to group drinks ("NAME - SIZE"); MEDDIUM is a typo in the data
  static final String[] SIZES = {"KIDS", "SMALL", "MEDIUM", "MEDDIUM", "LARGE"};

  // List of menu item categories.
  static final String[] CATEGORIES = {"Appetizers", "Meals", "Extras", "Drinks"};

  JFrame frame;
  JPanel itemsPanel;
  LinkedHashMap<String, JButton> catButtons = new LinkedHashMap<String, JButton>();
  DefaultListModel<String> ticketModel;
  JLabel headerTotal, subtotalLabel, taxLabel, totalLabel;

  // Unique ticket line -> [description, price, qty, menuItemID, selected component IDs]
  LinkedHashMap<Integer, Object[]> orderLines = new LinkedHashMap<Integer, Object[]>();

  int nextLineId = 0;

  /**
   * Entry point: opens the database connection and launches the cashier window.
   *
   * @param args unused
   */
  public static void main(String args[]) {
    openConnection(new DbSetup());
    PandaTheme.useSystemLook();
    SwingUtilities.invokeLater(new Runnable() {
      /** Runs the UI initialization or refresh callback. */
      public void run() {
        new CashierGUI().build();
      }
    });
  }

  /**
   * Opens the shared database connection, or exits if it fails.
   *
   * @param credentials database login credentials
   */
  static void openConnection(DbSetup credentials) {
    try {
      conn = DriverManager.getConnection("jdbc:postgresql://csce-315-db.engr.tamu.edu/team3db",
        credentials.USER, credentials.PASSWORD);
    } catch (Exception e) {
      e.printStackTrace();
      System.err.println(e.getClass().getName() + ": " + e.getMessage());
      System.exit(0);
    }
  }

  /** Closes the shared database connection, ignoring any exceptions. */
  static void closeConnection() {
    try {
      conn.close();
    } catch (Exception e) { /* ignore */
    }
  }

  /** Builds the main GUI layout for the cashier interface. */
  void build() {
    frame = new JFrame("Panda Express - Cashier");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setSize(1100, 650);
    frame.setLocationRelativeTo(null);

    JPanel root = new JPanel(new BorderLayout());
    root.setBackground(PandaTheme.WHITE);
    root.add(buildNavBar(), BorderLayout.NORTH);

    JPanel content = new JPanel(new BorderLayout(12, 0));
    content.setOpaque(false);
    content.setBorder(new EmptyBorder(16, 20, 16, 20));
    content.add(buildMenuArea(), BorderLayout.CENTER);
    content.add(buildTicket(), BorderLayout.EAST);
    root.add(content, BorderLayout.CENTER);

    frame.add(root);
    frame.setVisible(true);
    showCategory("Meals");
  }

  // ---- Top nav bar: MENU | INV. | REP. ----

  /**
   * Builds the top nav bar (MENU | INV. | REP.) with a Switch Mode button.
   *
   * @return the nav bar panel
   */
  JPanel buildNavBar() {
    JPanel nav = new JPanel(new BorderLayout());
    nav.setBackground(PandaTheme.GREY);

    JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    tabs.setOpaque(false);

    JButton menu = PandaTheme.primaryButton("MENU");
    menu.setBorder(new EmptyBorder(10, 22, 10, 22));
    JButton inv = PandaTheme.secondaryButton("INV.");
    inv.setBorder(new EmptyBorder(10, 22, 10, 22));
    JButton rep = PandaTheme.secondaryButton("REP.");
    rep.setBorder(new EmptyBorder(10, 22, 10, 22));

    inv.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        JOptionPane.showMessageDialog(frame, "Inventory view is manager only.");
      }
    });
    rep.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        JOptionPane.showMessageDialog(frame, "Reports view is manager only.");
      }
    });

    tabs.add(menu);
    tabs.add(inv);
    tabs.add(rep);
    nav.add(tabs, BorderLayout.WEST);

    JButton switchMode =
      PandaTheme.button("Switch Mode", PandaTheme.WHITE, PandaTheme.BLACK, PandaTheme.GREY_LIGHT);
    switchMode.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        switchToManager();
      }
    });
    JPanel right = new JPanel(new GridBagLayout());
    right.setOpaque(false);
    right.setBorder(new EmptyBorder(0, 0, 0, 12));
    right.add(switchMode);
    nav.add(right, BorderLayout.EAST);
    return nav;
  }

  /** Asks for the manager PIN, then opens the manager side. Wrong PIN stays here. */
  void switchToManager() {
    if (!orderLines.isEmpty()
      && JOptionPane.showConfirmDialog(frame,
           "The current order will be discarded. Switch to manager mode?", "Switch Mode",
           JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) {
      return;
    }
    JPasswordField pin = new JPasswordField(10);
    pin.setFont(PandaTheme.BODY);
    int choice = JOptionPane.showConfirmDialog(
      frame, pin, "Enter Manager PIN", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (choice != JOptionPane.OK_OPTION)
      return;
    if (!MANAGER_PIN.equals(new String(pin.getPassword()))) {
      JOptionPane.showMessageDialog(
        frame, "Incorrect PIN.", "Manager PIN", JOptionPane.ERROR_MESSAGE);
      return;
    }
    frame.dispose();
    PandaTheme.useSystemLook();
    new ManagerGUI(conn).build(conn);
  }

  // ---- Left: category row + scrollable grid of item buttons ----

  /**
   * Builds the category row plus scrollable grid of item buttons.
   *
   * @return the menu area panel
   */
  JPanel buildMenuArea() {
    JPanel area = new JPanel(new BorderLayout(0, 12));
    area.setOpaque(false);

    JPanel cats = new JPanel(new GridLayout(1, CATEGORIES.length, 6, 0));
    cats.setOpaque(false);
    for (final String cat : CATEGORIES) {
      JButton b = PandaTheme.secondaryButton(cat);
      b.addActionListener(new ActionListener() {
        /** Handles this control's user action. */
        public void actionPerformed(ActionEvent e) {
          showCategory(cat);
        }
      });
      catButtons.put(cat, b);
      cats.add(b);
    }

    itemsPanel = new JPanel(new GridLayout(0, 4, 8, 8));
    itemsPanel.setBackground(PandaTheme.WHITE);
    JScrollPane scroll = new JScrollPane(itemsPanel);
    scroll.setBorder(new LineBorder(PandaTheme.GRID, 1));
    scroll.getViewport().setBackground(PandaTheme.WHITE);
    scroll.getVerticalScrollBar().setUnitIncrement(16);

    area.add(cats, BorderLayout.NORTH);
    area.add(scroll, BorderLayout.CENTER);
    return area;
  }

  /**
   * Displays the active, in-stock menu items for the specified category.
   *
   * @param category one of {@link #CATEGORIES}
   */
  void showCategory(String category) {
    for (String c : catButtons.keySet()) {
      if (c.equals(category)) {
        PandaTheme.setBase(
          catButtons.get(c), PandaTheme.RED, PandaTheme.WHITE, PandaTheme.RED_DARK);
      } else {
        PandaTheme.setBase(
          catButtons.get(c), PandaTheme.GREY, PandaTheme.BLACK, PandaTheme.GREY_DARK);
      }
    }
    List<Object[]> items = new ArrayList<Object[]>();
    try {
      PreparedStatement ps = conn.prepareStatement("SELECT m.MenuItemID, m.MenuItemName, m.Price "
        + "FROM MenuItem m "
        + "WHERE m.Category = ? "
        + "AND m.Active = TRUE "
        + "AND NOT EXISTS ("
        + "  SELECT 1 "
        + "  FROM MenuItemInventory mii "
        + "  JOIN Inventory i ON i.InventoryID = mii.InventoryID "
        + "  WHERE mii.MenuItemID = m.MenuItemID "
        + "  AND i.Active = FALSE"
        + ") "
        + "ORDER BY m.MenuItemID");

      ps.setString(1, category);
      ResultSet rs = ps.executeQuery();
      while (rs.next()) {
        items.add(new Object[] {
          rs.getInt("MenuItemID"), rs.getString("MenuItemName"), rs.getDouble("Price")});
      }
      rs.close();
      ps.close();
    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(frame, "Error loading menu: " + ex.getMessage());
    }
    if ("Drinks".equals(category))
      showDrinkGrid(items);
    else
      showItemGrid(items);
  }

  /**
   * Flat grid: one button per menu item.
   *
   * @param items rows of {MenuItemID, MenuItemName, Price}
   */
  void showItemGrid(List<Object[]> items) {
    itemsPanel.removeAll();
    for (Object[] it : items) {
      itemsPanel.add(itemButton((Integer) it[0], (String) it[1], (Double) it[2], (String) it[1]));
    }
    itemsPanel.revalidate();
    itemsPanel.repaint();
  }

  /**
   * Drinks: one button per drink name; sizes are picked on the next screen.
   *
   * @param items rows of {MenuItemID, MenuItemName, Price}
   */
  void showDrinkGrid(List<Object[]> items) {
    LinkedHashMap<String, List<Object[]>> drinks = new LinkedHashMap<String, List<Object[]>>();
    for (Object[] it : items) {
      String base = baseName((String) it[1]);
      if (!drinks.containsKey(base))
        drinks.put(base, new ArrayList<Object[]>());
      drinks.get(base).add(it);
    }
    itemsPanel.removeAll();
    for (Map.Entry<String, List<Object[]>> e : drinks.entrySet()) {
      final String base = e.getKey();
      final List<Object[]> sizes = e.getValue();
      if (sizes.size() == 1) { // no size variants: add it directly
        Object[] it = sizes.get(0);
        itemsPanel.add(itemButton((Integer) it[0], (String) it[1], (Double) it[2], (String) it[1]));
      } else {
        JButton b = gridButton(base);
        b.addActionListener(new ActionListener() {
          /** Handles this control's user action. */
          public void actionPerformed(ActionEvent e2) {
            showSizeGrid(base, sizes);
          }
        });
        itemsPanel.add(b);
      }
    }
    itemsPanel.revalidate();
    itemsPanel.repaint();
  }

  /**
   * Size picker for one drink: back button, then one button per size.
   *
   * @param base  drink name without the size suffix
   * @param sizes rows of {MenuItemID, MenuItemName, Price} for each size
   */
  void showSizeGrid(String base, List<Object[]> sizes) {
    List<Object[]> sorted = new ArrayList<Object[]>(sizes);
    Collections.sort(sorted, new Comparator<Object[]>() {
      /** Compares drink sizes in their display order. */
      public int compare(Object[] a, Object[] b) {
        return sizeOrder((String) a[1]) - sizeOrder((String) b[1]);
      }
    });
    itemsPanel.removeAll();
    JButton back = gridButton("&lt; Back");
    back.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        showCategory("Drinks");
      }
    });
    itemsPanel.add(back);
    for (Object[] it : sorted) {
      itemsPanel.add(
        itemButton((Integer) it[0], (String) it[1], (Double) it[2], sizeLabel((String) it[1])));
    }
    itemsPanel.revalidate();
    itemsPanel.repaint();
  }

  /**
   * Drink name without the size suffix ("COCA-COLA - SMALL" -&gt; "COCA-COLA").
   *
   * @param name full menu item name
   * @return the name without its size suffix, or unchanged if none
   */
  static String baseName(String name) {
    int i = name.lastIndexOf(" - ");
    if (i < 0)
      return name;
    String suffix = name.substring(i + 3);
    for (String s : SIZES) {
      if (suffix.equals(s))
        return name.substring(0, i);
    }
    return name;
  }

  /**
   * Title-cased size for the button label ("... - MEDDIUM" -&gt; "Medium").
   *
   * @param name full menu item name ending in " - SIZE"
   * @return title-cased size text
   */
  static String sizeLabel(String name) {
    String s = name.substring(name.lastIndexOf(" - ") + 3);
    if (s.equals("MEDDIUM"))
      s = "MEDIUM";
    return s.charAt(0) + s.substring(1).toLowerCase();
  }

  /**
   * Returns the relative order of a size for sorting purposes.
   *
   * @param name full menu item name ending in " - SIZE"
   * @return 0 (kids) through 3 (large), 4 for unknown sizes
   */
  static int sizeOrder(String name) {
    String s = name.substring(name.lastIndexOf(" - ") + 3);
    if (s.equals("KIDS"))
      return 0;
    if (s.equals("SMALL"))
      return 1;
    if (s.equals("MEDIUM") || s.equals("MEDDIUM"))
      return 2;
    if (s.equals("LARGE"))
      return 3;
    return 4;
  }

  /**
   * Creates a grid button with the specified label.
   *
   * @param label button text (may contain simple HTML)
   * @return the styled button
   */
  JButton gridButton(String label) {
    JButton b = PandaTheme.secondaryButton("<html><center>" + label + "</center></html>");
    b.setFont(PandaTheme.SMALL);
    b.setBorder(new EmptyBorder(4, 6, 4, 6));
    b.setPreferredSize(new Dimension(150, 70));
    return b;
  }

  /**
   * Item button: shows label + price, adds the item to the ticket on click.
   *
   * @param id    MenuItemID
   * @param name  menu item name
   * @param price unit price
   * @param label text shown on the button
   * @return the styled button
   */
  JButton itemButton(final int id, final String name, final double price, String label) {
    JButton b = gridButton(label + "<br>$" + String.format("%.2f", price));
    b.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        addItem(id, name, price);
      }
    });
    return b;
  }

  // ---- Right: order ticket ----

  /**
   * Builds the order ticket: line list, remove button, totals, and Checkout.
   *
   * @return the ticket panel
   */
  JPanel buildTicket() {
    JPanel ticket = new JPanel(new BorderLayout(0, 12));
    ticket.setPreferredSize(new Dimension(280, 0));
    ticket.setBackground(PandaTheme.GREY_LIGHT);
    ticket.setBorder(
      new CompoundBorder(new LineBorder(PandaTheme.GRID, 1), new EmptyBorder(12, 12, 12, 12)));

    headerTotal = PandaTheme.label("Total: $0.00", PandaTheme.HEADING, PandaTheme.BLACK);
    ticket.add(headerTotal, BorderLayout.NORTH);

    ticketModel = new DefaultListModel<String>();
    JList<String> list = new JList<String>(ticketModel);
    list.setFont(new Font("Monospaced", Font.PLAIN, 13));
    list.setBackground(PandaTheme.WHITE);
    JScrollPane listScroll = new JScrollPane(list);
    listScroll.setBorder(new LineBorder(PandaTheme.GRID, 1));
    ticket.add(listScroll, BorderLayout.CENTER);

    JPanel bottom = new JPanel();
    bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
    bottom.setOpaque(false);

    JButton remove = PandaTheme.secondaryButton("Remove Selected");
    remove.setAlignmentX(Component.CENTER_ALIGNMENT);
    remove.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        int idx = list.getSelectedIndex();
        if (idx >= 0) {
          int id = (Integer) orderLines.keySet().toArray()[idx];
          Object[] line = orderLines.get(id);
          int qty = (Integer) line[2];
          if (qty > 1)
            line[2] = qty - 1;
          else
            orderLines.remove(id);
          refreshTicket();
        }
      }
    });

    JPanel sums = new JPanel(new GridLayout(3, 1));
    sums.setOpaque(false);
    subtotalLabel = PandaTheme.label("Subtotal: $0.00", PandaTheme.BODY, PandaTheme.BLACK);
    taxLabel = PandaTheme.label("Tax: $0.00", PandaTheme.BODY, PandaTheme.BLACK);
    totalLabel =
      PandaTheme.label("Total: $0.00", new Font(PandaTheme.FONT, Font.BOLD, 16), PandaTheme.BLACK);
    sums.add(subtotalLabel);
    sums.add(taxLabel);
    sums.add(totalLabel);

    JButton checkout = PandaTheme.primaryButton("Checkout");
    checkout.setAlignmentX(Component.CENTER_ALIGNMENT);
    checkout.setFont(PandaTheme.HEADING);
    checkout.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        checkout();
      }
    });

    bottom.add(remove);
    bottom.add(Box.createVerticalStrut(8));
    bottom.add(sums);
    bottom.add(Box.createVerticalStrut(8));
    bottom.add(checkout);
    ticket.add(bottom, BorderLayout.SOUTH);
    return ticket;
  }

  /**
   * Adds an item to the order, updating the ticket display.
   * Meals prompt for side/entree choices; if the item is not
   * available, a message dialog is shown instead.
   *
   * @param id    MenuItemID
   * @param name  menu item name
   * @param price unit price
   */
  void addItem(int id, String name, double price) {
    List<Integer> selections = new ArrayList<Integer>();
    String description = name;
    try {
      int count;
      try (PreparedStatement ps = conn.prepareStatement(
             "SELECT EntreeCount FROM MenuItem WHERE MenuItemID = ? AND Active = TRUE")) {
        ps.setInt(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next())
            throw new SQLException("This menu item is no longer available.");
          count = rs.getInt(1);
        }
      }
      if (count > 0) {
        JPanel choices = new JPanel(new GridLayout(0, 1, 4, 4));
        List<JComboBox<String>> pickers = new ArrayList<JComboBox<String>>();
        List<List<Integer>> ids = new ArrayList<List<Integer>>();
        for (int slot = 0; slot <= count; slot++) {
          String category = slot == 0 ? "Sides" : "Entrees";
          JComboBox<String> picker = new JComboBox<String>();
          List<Integer> optionIds = new ArrayList<Integer>();
          try (
            PreparedStatement ps = conn.prepareStatement(
              "SELECT MenuItemID, MenuItemName FROM MenuItem WHERE Category = ? AND Active = TRUE "
              + "AND MenuItemName NOT LIKE '%SOUP%' ORDER BY MenuItemName")) {
            ps.setString(1, category);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                optionIds.add(rs.getInt(1));
                picker.addItem(rs.getString(2));
              }
            }
          }
          if (optionIds.isEmpty())
            throw new SQLException("No active " + category + " to choose from.");
          choices.add(new JLabel(slot == 0 ? "Side" : "Entree " + slot));
          choices.add(picker);
          pickers.add(picker);
          ids.add(optionIds);
        }
        if (JOptionPane.showConfirmDialog(frame, choices, name + " - choose your meal",
              JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
          != JOptionPane.OK_OPTION)
          return;
        for (int slot = 0; slot < pickers.size(); slot++) {
          selections.add(ids.get(slot).get(pickers.get(slot).getSelectedIndex()));
          description += " / " + pickers.get(slot).getSelectedItem();
        }
      }
      int key = ++nextLineId;
      orderLines.put(key, new Object[] {description, price, 1, id, selections});
      try {
        validateCart();
      } catch (SQLException ex) {
        orderLines.remove(key);
        throw ex;
      }
      refreshTicket();
    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(
        frame, ex.getMessage(), "Cannot add item", JOptionPane.ERROR_MESSAGE);
    }
  }

  /**
   * MenuItemID of the line plus the IDs of its selected components.
   *
   * @param line a ticket line from {@link #orderLines}
   * @return the line's own ID followed by its selected component IDs
   */
  @SuppressWarnings("unchecked")
  List<Integer> components(Object[] line) {
    List<Integer> ids = new ArrayList<Integer>();
    ids.add((Integer) line[3]);
    ids.addAll((List<Integer>) line[4]);
    return ids;
  }

  /**
   * Checks combined demand across the whole cart, including repeated entree choices.
   *
   * @return total ingredient demand, including meal containers and selected food
   * @throws SQLException if an item is inactive, has no recipe, or stock is insufficient
   */
  Map<Integer, Double> validateCart() throws SQLException {
    Map<Integer, Double> required = new LinkedHashMap<Integer, Double>();
    for (Object[] line : orderLines.values()) {
      for (int id : components(line)) {
        try (PreparedStatement ps =
               conn.prepareStatement("SELECT Active FROM MenuItem WHERE MenuItemID = ?")) {
          ps.setInt(1, id);
          try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || !rs.getBoolean(1))
              throw new SQLException("A selected item is no longer active.");
          }
        }
        try (
          PreparedStatement ps = conn.prepareStatement(
            "SELECT InventoryID, QuantityRequired FROM MenuItemInventory WHERE MenuItemID = ?")) {
          ps.setInt(1, id);
          try (ResultSet rs = ps.executeQuery()) {
            boolean hasRecipe = false;
            while (rs.next()) {
              hasRecipe = true;
              int inventoryId = rs.getInt(1);
              required.put(inventoryId,
                required.getOrDefault(inventoryId, 0.0) + rs.getDouble(2) * (Integer) line[2]);
            }
            if (!hasRecipe)
              throw new SQLException("A selected item has no inventory recipe (menu item " + id + ").");
          }
        }
      }
    }
    for (Map.Entry<Integer, Double> entry : required.entrySet()) {
      try (PreparedStatement ps = conn.prepareStatement(
             "SELECT ItemName, Quantity, Active FROM Inventory WHERE InventoryID = ?")) {
        ps.setInt(1, entry.getKey());
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next())
            throw new SQLException("A required inventory item is missing.");
          if (!rs.getBoolean(3) || rs.getDouble(2) + 0.000001 < entry.getValue())
            throw new SQLException("Not enough " + rs.getString(1) + " for this order.");
        }
      }
    }
    return required;
  }

  /**
   * Deducts the validated cart's combined ingredient demand inside the checkout transaction.
   * @param required ingredient quantities returned by {@link #validateCart()}
   * @throws SQLException if an ingredient is unavailable or cannot cover the demand
   */
  void deductInventory(Map<Integer, Double> required) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
           "UPDATE Inventory SET Quantity = Quantity - ? "
           + "WHERE InventoryID = ? AND Active = TRUE AND Quantity >= ?")) {
      for (Map.Entry<Integer, Double> entry : required.entrySet()) {
        ps.setDouble(1, entry.getValue());
        ps.setInt(2, entry.getKey());
        ps.setDouble(3, entry.getValue());
        if (ps.executeUpdate() != 1)
          throw new SQLException("Inventory changed; this order cannot be completed.");
      }
    }
  }

  /**
   * Calculates the subtotal of all items in the current order.
   *
   * @return sum of price * qty over all ticket lines
   */
  double subtotal() {
    double sum = 0;
    for (Object[] line : orderLines.values())
      sum += (Double) line[1] * (Integer) line[2];
    return sum;
  }

  /** Refreshes the ticket display, including subtotal, tax, and total. */
  void refreshTicket() {
    ticketModel.clear();
    for (Object[] line : orderLines.values()) {
      String name = (String) line[0];

      ticketModel.addElement(String.format(
        "%dx %-24s $%.2f", (Integer) line[2], name, (Double) line[1] * (Integer) line[2]));
    }
    double sub = subtotal(), tax = sub * TAX_RATE, tot = sub + tax;
    headerTotal.setText(String.format("Total: $%.2f", tot));
    subtotalLabel.setText(String.format("Subtotal: $%.2f", sub));
    taxLabel.setText(String.format("Tax: $%.2f", tax));
    totalLabel.setText(String.format("Total: $%.2f", tot));
  }

  // ---- Checkout: write the order to the database in one transaction ----

  /**
   * Writes the order to the database in one transaction: Orders row,
   * OrderItem rows, OrderItemSelection rows, and inventory decrements.
   * Rolls back if validation fails or any inventory would go negative.
   */
  void checkout() {
    if (orderLines.isEmpty()) {
      JOptionPane.showMessageDialog(frame, "The order is empty.");
      return;
    }
    double sub = subtotal(), tot = sub * (1 + TAX_RATE);
    int confirm = JOptionPane.showConfirmDialog(
      frame, String.format("Charge $%.2f?", tot), "Checkout", JOptionPane.YES_NO_OPTION);
    if (confirm != JOptionPane.YES_OPTION)
      return;

    try {
      conn.setAutoCommit(false);
      // Serialize checkout and manager stock changes while validating combined demand.
      try (Statement lock = conn.createStatement()) {
        lock.execute("LOCK TABLE MenuItem, MenuItemInventory IN SHARE MODE");
        lock.execute("LOCK TABLE Inventory IN SHARE ROW EXCLUSIVE MODE");
        lock.execute("LOCK TABLE Orders, OrderItem IN EXCLUSIVE MODE");
      }
      Map<Integer, Double> required = validateCart();

      Statement st = conn.createStatement();
      ResultSet rs =
        st.executeQuery("SELECT (SELECT COALESCE(MAX(OrderID),0)+1 FROM Orders) AS oid, "
          + "       (SELECT COALESCE(MAX(OrderItemID),0) FROM OrderItem) AS oiid");
      rs.next();
      int orderId = rs.getInt("oid");
      int nextOrderItemId = rs.getInt("oiid");
      rs.close();
      st.close();

      PreparedStatement insOrder =
        conn.prepareStatement("INSERT INTO Orders (OrderID, OrderDate, OrderTime, Total) "
          + "VALUES (?, CURRENT_DATE, CURRENT_TIME, ?)");
      insOrder.setInt(1, orderId);
      insOrder.setDouble(2, tot);
      insOrder.executeUpdate();

      PreparedStatement insItem =
        conn.prepareStatement("INSERT INTO OrderItem (OrderItemID, OrderID, MenuItemID, Qty) "
          + "VALUES (?, ?, ?, ?)");
      PreparedStatement insSelection = conn.prepareStatement(
        "INSERT INTO OrderItemSelection (OrderItemID, MenuItemID, Qty) VALUES (?, ?, ?)");
      for (Map.Entry<Integer, Object[]> e : orderLines.entrySet()) {
        int menuItemId = (Integer) e.getValue()[3];
        int qty = (Integer) e.getValue()[2];
        insItem.setInt(1, ++nextOrderItemId);
        insItem.setInt(2, orderId);
        insItem.setInt(3, menuItemId);
        insItem.setInt(4, qty);
        insItem.executeUpdate();
        Map<Integer, Integer> selectedCounts = new LinkedHashMap<Integer, Integer>();
        List<Integer> selected = new ArrayList<Integer>(components(e.getValue()));
        selected.remove(0); // the meal itself is already stored in OrderItem
        for (int component : selected)
          selectedCounts.put(component, selectedCounts.getOrDefault(component, 0) + 1);
        for (Map.Entry<Integer, Integer> selection : selectedCounts.entrySet()) {
          insSelection.setInt(1, nextOrderItemId);
          insSelection.setInt(2, selection.getKey());
          insSelection.setInt(3, selection.getValue());
          insSelection.executeUpdate();
        }
      }

      deductInventory(required);

      try (Statement check = conn.createStatement();
        ResultSet neg = check.executeQuery("SELECT itemname FROM Inventory WHERE Quantity < 0")) {
        if (neg.next()) {
          conn.rollback();
          JOptionPane.showMessageDialog(
            frame, "Not enough " + neg.getString(1) + " to complete this order.");
          return;
        }
      }

      insOrder.close();
      insItem.close();
      insSelection.close();
      conn.commit();
      JOptionPane.showMessageDialog(frame,
        String.format(
          "Order #%d submitted. Total: $%.2f\nThank you, have a nice day!", orderId, tot));
      orderLines.clear();
      refreshTicket();
    } catch (SQLException ex) {
      try {
        conn.rollback();
      } catch (SQLException ignored) {
      }
      JOptionPane.showMessageDialog(frame, "Order failed: " + ex.getMessage());
    } finally {
      try {
        conn.setAutoCommit(true);
      } catch (SQLException ignored) {
      }
    }
  }

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      /** Runs the UI initialization or refresh callback. */
      public void run() {
        closeConnection();
      }
    }));
  }
}
