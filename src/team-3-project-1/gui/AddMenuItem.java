/** Provides the menu item form and ingredient recipe editor. */

import java.awt.*;
import java.awt.event.*;
import java.sql.SQLException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigDecimal;

/**
 * Form for adding a brand-new Menu item.
 *
 * Discontinued menu items should be restored using
 * ReactivateMenuItem instead of being inserted again.
 */
public class AddMenuItem extends JDialog {
  private final InventoryDAO dao;
  private final Runnable onSuccess;

  private JTextField nameField;
  private JTextField priceField;
  private JComboBox<String> categoryBox;
  private List<InventoryDAO.Item> inventory = new ArrayList<InventoryDAO.Item>();
  private DefaultTableModel recipeModel;
  private JTable recipeTable;
  private JButton addButton;

  /**
   * Creates the modal "Add Menu Item" dialog.
   *
   * @param parent    parent frame the dialog is centered on
   * @param dao       data access object used to load ingredients and insert the item
   * @param onSuccess callback run after a successful insert (may be null)
   */
  public AddMenuItem(JFrame parent, InventoryDAO dao, Runnable onSuccess) {
    super(parent, "Add Menu Item", true);

    this.dao = dao;
    this.onSuccess = onSuccess;

    build();

    pack();
    setLocationRelativeTo(parent);
  }

  /** Builds the UI for the AddMenuItem dialog. */
  private void build() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(new EmptyBorder(16, 16, 16, 16));

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(6, 6, 6, 6);
    gbc.fill = GridBagConstraints.HORIZONTAL;

    nameField = new JTextField(20);
    priceField = new JTextField(10);

    categoryBox = new JComboBox<String>(CashierGUI.CATEGORIES);

    gbc.gridx = 0;
    gbc.gridy = 0;
    panel.add(new JLabel("Name:"), gbc);

    gbc.gridx = 1;
    panel.add(nameField, gbc);

    gbc.gridx = 0;
    gbc.gridy = 1;
    panel.add(new JLabel("Price:"), gbc);

    gbc.gridx = 1;
    panel.add(priceField, gbc);

    gbc.gridx = 0;
    gbc.gridy = 2;
    panel.add(new JLabel("Category:"), gbc);

    gbc.gridx = 1;
    panel.add(categoryBox, gbc);

    recipeModel =
      new DefaultTableModel(new String[] {"Inventory item", "Unit", "Quantity per menu item"}, 0) {
        /** Returns whether this table cell permits editing. */
        public boolean isCellEditable(int row, int column) {
          return column == 2;
        }
      };
    recipeTable = new JTable(recipeModel);
    recipeTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    JScrollPane recipeScroll = new JScrollPane(recipeTable);
    recipeScroll.setPreferredSize(new Dimension(520, 220));
    gbc.gridx = 0;
    gbc.gridy = 3;
    gbc.gridwidth = 2;
    panel.add(
      new JLabel("Recipe: enter quantities in the listed units; leave unused ingredients blank."),
      gbc);
    gbc.gridy = 4;
    panel.add(recipeScroll, gbc);
    gbc.gridy = 5;
    panel.add(new JLabel("Reactivating a discontinued item preserves its existing recipe."), gbc);

    JButton cancel = new JButton("Cancel");
    cancel.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        dispose();
      }
    });

    JButton add = new JButton("Add Item");
    addButton = add;
    add.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        addItem();
      }
    });

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(cancel);
    buttons.add(add);

    gbc.gridx = 0;
    gbc.gridy = 6;
    gbc.gridwidth = 2;
    panel.add(buttons, gbc);
    try {
      inventory = dao.fetchAll();
      for (InventoryDAO.Item item : inventory) {
        recipeModel.addRow(new Object[] {item.name, item.unit, ""});
      }
    } catch (SQLException ex) {
      addButton.setEnabled(false);
      JOptionPane.showMessageDialog(this, "Could not load recipe ingredients: " + ex.getMessage());
    }

    add(panel);
  }

  /** Validates the form and adds the menu item if all inputs are correct. */
  private void addItem() {
    String name = nameField.getText().trim();
    String priceText = priceField.getText().trim();
    String category = (String) categoryBox.getSelectedItem();

    if (name.isEmpty() || name.length() > 150) {
      JOptionPane.showMessageDialog(this, "Enter a menu item name of at most 150 characters.");
      return;
    }

    double price;

    try {
      price = Double.parseDouble(priceText);
    } catch (NumberFormatException ex) {
      JOptionPane.showMessageDialog(this, "Price must be a valid number.");
      return;
    }

    if (!Double.isFinite(price) || price < 0 || price > 99999999.99) {
      JOptionPane.showMessageDialog(this, "Price must be finite and between 0 and 99,999,999.99.");
      return;
    }

    if (recipeTable.isEditing() && !recipeTable.getCellEditor().stopCellEditing())
      return;
    Map<Integer, Double> ingredients = new LinkedHashMap<Integer, Double>();
    for (int row = 0; row < recipeModel.getRowCount(); row++) {
      Object value = recipeModel.getValueAt(row, 2);
      String text = value == null ? "" : value.toString().trim();
      if (text.isEmpty())
        continue;
      try {
        BigDecimal qty = new BigDecimal(text);
        if (qty.signum() <= 0 || qty.compareTo(new BigDecimal("9999999.999")) > 0
          || qty.stripTrailingZeros().scale() > 3)
          throw new NumberFormatException();
        ingredients.put(inventory.get(row).id, qty.doubleValue());
      } catch (NumberFormatException ex) {
        JOptionPane.showMessageDialog(this,
          "Quantity for " + inventory.get(row).name
            + " must be positive, at most 9,999,999.999, with at most three decimal places.");
        return;
      }
    }
    try {
      dao.insertMenuItem(name, category, price, ingredients);

      if (onSuccess != null) {
        onSuccess.run();
      }

      JOptionPane.showMessageDialog(this, "\"" + name + "\" was added to the menu.");

      dispose();

    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(this, "Could not add menu item:\n" + ex.getMessage(),
        "Database Error", JOptionPane.ERROR_MESSAGE);
    }
  }
}
