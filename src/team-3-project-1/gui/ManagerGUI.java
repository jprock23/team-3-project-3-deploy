/** Provides manager inventory, menu, order history, and report pages. */

import java.sql.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manager window: Inventory (edit amounts, add, discontinue, and reactivate items),
 * Menu (edit prices, add, and discontinue items),
 * Order History (today's orders and item quantities),
 * Reports, and a Schedule placeholder.
 * LOW = Quantity &lt;= Minimum_Threshold.
 */
public class ManagerGUI {
  static final String[] PAGES = {"Inventory", "Menu", "Order History", "Reports", "Schedule"};

  JFrame frame;
  final Connection db;
  InventoryDAO dao;
  TablePage<InventoryDAO.Item> inventory;
  TablePage<InventoryDAO.MenuItem> menu;
  OrderHistoryPage orderHistory;
  CardLayout cards;
  JPanel pages;
  LinkedHashMap<String, JButton> navButtons = new LinkedHashMap<String, JButton>();

  /**
   * Entry point: opens the database connection and launches the manager window.
   *
   * @param args unused
   */
  public static void main(String args[]) {
    ModeSelect.openConnection(new DbSetup());
    PandaTheme.useSystemLook();
    SwingUtilities.invokeLater(new Runnable() {
      /** Runs the UI initialization or refresh callback. */
      public void run() {
        new ManagerGUI(ModeSelect.conn).build(ModeSelect.conn);
      }
    });
  }

  /**
   * Creates the manager window backed by the given connection.
   *
   * @param c open database connection
   */
  public ManagerGUI(Connection c) {
    db = c;
    dao = new InventoryDAO(c);
  }

  /**
   * Builds the main GUI layout for the manager interface.
   *
   * @param conn open database connection (used for the Reports page)
   */
  void build(Connection conn) {
    frame = new JFrame("Panda Express - Manager");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setSize(900, 640);
    frame.setLocationRelativeTo(null);

    cards = new CardLayout();
    pages = new JPanel(cards);
    pages.add(buildInventoryPage(), "Inventory");
    pages.add(buildMenuPage(), "Menu");
    orderHistory = new OrderHistoryPage(frame, db);
    pages.add(orderHistory.panel, "Order History");
    pages.add(new ReportWithFourQuads(conn).panel, "Reports");
    pages.add(placeholder("Employee schedule"), "Schedule");

    JPanel root = new JPanel(new BorderLayout());
    root.setBackground(PandaTheme.WHITE);
    root.add(buildNavBar(), BorderLayout.NORTH);
    root.add(pages, BorderLayout.CENTER);

    frame.add(root);
    showPage("Inventory");
    frame.setVisible(true);
    inventory.reload();
    menu.reload();
  }

  /**
   * Nav bar: Inventory | Menu | Order History | Reports | Schedule ... Switch Mode.
   *
   * @return the nav bar panel
   */
  JPanel buildNavBar() {
    JPanel nav = new JPanel(new BorderLayout());
    nav.setBackground(PandaTheme.GREY);

    JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    tabs.setOpaque(false);
    for (final String name : PAGES) {
      JButton b = PandaTheme.secondaryButton(name);
      b.setBorder(new EmptyBorder(10, 22, 10, 22));
      b.addActionListener(new ActionListener() {
        /** Handles this control's user action. */
        public void actionPerformed(ActionEvent e) {
          showPage(name);
        }
      });
      navButtons.put(name, b);
      tabs.add(b);
    }
    nav.add(tabs, BorderLayout.WEST);

    JButton switchMode =
      PandaTheme.button("Switch Mode", PandaTheme.WHITE, PandaTheme.BLACK, PandaTheme.GREY_LIGHT);
    switchMode.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        switchMode();
      }
    });
    JPanel right = new JPanel(new GridBagLayout());
    right.setOpaque(false);
    right.setBorder(new EmptyBorder(0, 0, 0, 12));
    right.add(switchMode);
    nav.add(right, BorderLayout.EAST);
    return nav;
  }

  /**
   * Switches the currently displayed page in the main content area.
   *
   * @param name one of {@link #PAGES}
   */
  void showPage(String name) {
    cards.show(pages, name);
    if (name.equals("Order History")) {
      orderHistory.reload();
    }
    for (String n : navButtons.keySet()) {
      if (n.equals(name)) {
        PandaTheme.setBase(navButtons.get(n), PandaTheme.RED, PandaTheme.WHITE, PandaTheme.RED);
      } else {
        PandaTheme.setBase(
          navButtons.get(n), PandaTheme.GREY, PandaTheme.BLACK, PandaTheme.GREY_DARK);
      }
    }
  }

  /** Returns to mode selection, warning about unsaved changes. */
  void switchMode() {
    inventory.stopEditing();
    menu.stopEditing();
    if ((inventory.hasUnsaved() || menu.hasUnsaved())
      && JOptionPane.showConfirmDialog(frame,
           "You have unsaved changes. Discard them and switch mode?", "Switch Mode",
           JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) {
      return;
    }
    frame.dispose();
    new ModeSelect(db).build();
  }

  /**
   * Creates a placeholder panel with a title.
   *
   * @param title heading text
   * @return the placeholder panel
   */
  JPanel placeholder(String title) {
    JPanel p = new JPanel(new BorderLayout());
    p.setBackground(PandaTheme.WHITE);
    p.setBorder(new EmptyBorder(16, 20, 16, 20));
    p.add(PandaTheme.label(title, PandaTheme.HEADING, PandaTheme.BLACK), BorderLayout.NORTH);
    return p;
  }

  /**
   * Inventory page: Item | Amount | Unit | Status | Unsaved, with
   * Discontinue / Reactivate / Add buttons.
   *
   * @return the inventory page panel
   */
  JPanel buildInventoryPage() {
    TableEdit<InventoryDAO.Item> tableModel = new TableEdit<InventoryDAO.Item>(
      new String[] {"Item", "Amount", "Unit", "Status", "Unsaved"}) {
      /** Returns the database identifier for the row item. */
      int id(InventoryDAO.Item it) {
        return it.id;
      }

      /** Returns the editable numeric value for the row item. */
      double value(InventoryDAO.Item it) {
        return it.quantity;
      }

      /** Returns the display value for a noneditable table column. */
      Object cell(InventoryDAO.Item it, int c) {
        if (c == 0) {
          return it.name;
        }

        if (c == 2) {
          return it.unit;
        }

        return it.isLow() ? "LOW" : "ok";
      }
    };

    inventory = new TablePage<InventoryDAO.Item>(
      frame, "Inventory", null, "items", tableModel, "", new int[] {280, 110, 90, 100, 90}) {
      /** Loads the active records displayed by this manager table. */
      List<InventoryDAO.Item> load() throws SQLException {
        return dao.fetchAll();
      }

      /** Saves pending numeric changes through the data access object. */
      void store(Map<Integer, Double> changes) throws SQLException {
        dao.updateQuantities(changes);
      }
    };

    inventory.table.getColumnModel().getColumn(3).setCellRenderer(PandaTheme.statusRenderer());

    // -----------------------------------------------------
    // Discontinue
    // -----------------------------------------------------

    JButton discontinue = PandaTheme.secondaryButton("Discontinue Item");

    discontinue.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        removeSelected();
      }
    });

    // -----------------------------------------------------
    // Reactivate
    // -----------------------------------------------------

    JButton reactivate = PandaTheme.secondaryButton("Reactivate Item");

    reactivate.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        new ReactivateItem(frame, dao, new Runnable() {
          /** Runs the UI initialization or refresh callback. */
          public void run() {
            inventory.reload();
          }
        }).setVisible(true);
      }
    });

    // -----------------------------------------------------
    // Add new
    // -----------------------------------------------------

    JButton add = PandaTheme.secondaryButton("+ Add Item");

    add.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        new AddItem(frame, dao, new Runnable() {
          /** Runs the UI initialization or refresh callback. */
          public void run() {
            inventory.reload();
          }
        }).setVisible(true);
      }
    });

    inventory.topButtons.add(discontinue);
    inventory.topButtons.add(reactivate);
    inventory.topButtons.add(add);

    return inventory.panel;
  }

  /**
   * Menu page: Menu Item | Price | Unsaved, with Discontinue / Add buttons.
   *
   * @return the menu page panel
   */
  JPanel buildMenuPage() {
    TableEdit<InventoryDAO.MenuItem> tableModel =
      new TableEdit<InventoryDAO.MenuItem>(new String[] {"Menu Item", "Price", "Unsaved"}) {
        /** Returns the database identifier for the row item. */
        int id(InventoryDAO.MenuItem it) {
          return it.id;
        }

        /** Returns the editable numeric value for the row item. */
        double value(InventoryDAO.MenuItem it) {
          return it.price;
        }

        /** Returns the display value for a noneditable table column. */
        Object cell(InventoryDAO.MenuItem it, int c) {
          return it.name;
        }
      };
    menu = new TablePage<InventoryDAO.MenuItem>(frame, "Menu Prices",
      "New prices apply to future orders only. Past orders keep the totals they were charged.",
      "menu items", tableModel, "$", new int[] {420, 120, 90}) {
      /** Loads the active records displayed by this manager table. */
      List<InventoryDAO.MenuItem> load() throws SQLException {
        return dao.fetchMenuItems();
      }

      /** Saves pending numeric changes through the data access object. */
      void store(Map<Integer, Double> changes) throws SQLException {
        dao.updatePrices(changes);
      }

      /** Confirms applying the pending price changes. */
      boolean confirmSave(int n) {
        return JOptionPane.showConfirmDialog(frame,
                 "Update " + n + " price(s)? New prices apply to future orders only.",
                 "Save Prices", JOptionPane.YES_NO_OPTION)
          == JOptionPane.YES_OPTION;
      }
    };
    JButton remove = PandaTheme.secondaryButton("Discontinue Item");

    remove.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        removeSelectedMenuItem();
      }
    });
    JButton add = PandaTheme.secondaryButton("+ Add Item");

    add.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        new AddMenuItem(frame, dao, new Runnable() {
          /** Runs the UI initialization or refresh callback. */
          public void run() {
            menu.reload();
          }
        }).setVisible(true);
      }
    });

    menu.topButtons.add(remove);
    menu.topButtons.add(add);

    return menu.panel;
  }

  /** Discontinues the selected menu item after a confirmation prompt. */
  void removeSelectedMenuItem() {
    menu.stopEditing();

    int selectedRow = menu.table.getSelectedRow();

    if (selectedRow < 0) {
      JOptionPane.showMessageDialog(frame, "Select a menu item to discontinue.");
      return;
    }

    int row = menu.table.convertRowIndexToModel(selectedRow);

    InventoryDAO.MenuItem item = menu.model.items.get(row);

    if (JOptionPane.showConfirmDialog(frame, "Discontinue \"" + item.name + "\"?",
          "Discontinue Menu Item", JOptionPane.YES_NO_OPTION)
      != JOptionPane.YES_OPTION) {
      return;
    }

    try {
      dao.discontinueMenuItem(item.id);

      menu.reload();

      JOptionPane.showMessageDialog(frame, "\"" + item.name + "\" was discontinued.");

    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(frame, "Could not discontinue menu item:\n" + ex.getMessage(),
        "Database Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  /** Discontinues the selected inventory item after a confirmation prompt. */
  void removeSelected() {
    inventory.stopEditing();

    int selectedRow = inventory.table.getSelectedRow();

    if (selectedRow < 0) {
      JOptionPane.showMessageDialog(frame, "Select an inventory item to discontinue.");
      return;
    }

    int row = inventory.table.convertRowIndexToModel(selectedRow);

    InventoryDAO.Item item = inventory.model.items.get(row);

    if (JOptionPane.showConfirmDialog(frame, "Discontinue \"" + item.name + "\" from inventory?",
          "Discontinue Inventory Item", JOptionPane.YES_NO_OPTION)
      != JOptionPane.YES_OPTION) {
      return;
    }

    try {
      dao.discontinueInventoryItem(item.id);

      inventory.reload();

      JOptionPane.showMessageDialog(
        frame, "\"" + item.name + "\" was discontinued from inventory.");

    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(frame, "Could not discontinue item:\n" + ex.getMessage(),
        "Database Error", JOptionPane.ERROR_MESSAGE);
    }
  }
}
