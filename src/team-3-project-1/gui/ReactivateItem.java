/** Provides the dialog for restoring a discontinued inventory ingredient. */

import java.sql.SQLException;
import java.util.List;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Allows a manager to restore a discontinued inventory item.
 *
 * Existing unit, minimum threshold, and shelf life values are preserved.
 * The manager only provides the new starting quantity.
 */
public class ReactivateItem extends JDialog {
  private final InventoryDAO dao;
  private final Runnable onReactivated;

  private List<InventoryDAO.Item> inactiveItems;

  private JComboBox<String> itemBox;
  private JLabel unitValue;
  private JLabel thresholdValue;
  private JLabel shelfLifeValue;
  private JTextField quantityField;
  private JLabel error;

  /**
   * Creates the reactivate dialog and loads the discontinued items.
   *
   * @param owner owning frame
   * @param dao inventory data access object
   * @param onReactivated callback run after a successful reactivation
   */
  public ReactivateItem(JFrame owner, final InventoryDAO dao, final Runnable onReactivated) {
    super(owner, "Reactivate Inventory Item", true);

    this.dao = dao;
    this.onReactivated = onReactivated;

    setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

    try {
      inactiveItems = dao.fetchInactiveItems();
    } catch (SQLException ex) {
      JOptionPane.showMessageDialog(owner,
        "Could not load discontinued inventory items:\n" + ex.getMessage(), "Database Error",
        JOptionPane.ERROR_MESSAGE);

      dispose();
      return;
    }

    if (inactiveItems.isEmpty()) {
      JOptionPane.showMessageDialog(
        owner, "There are no discontinued inventory items to reactivate.");

      dispose();
      return;
    }

    build();

    pack();
    setLocationRelativeTo(owner);
  }

  /** Builds the GUI for the dialog. */
  private void build() {
    JPanel root = new JPanel(new BorderLayout(0, 12));
    root.setBackground(PandaTheme.WHITE);
    root.setBorder(new EmptyBorder(18, 20, 18, 20));

    root.add(PandaTheme.label("Reactivate Inventory Item", PandaTheme.HEADING, PandaTheme.BLACK),
      BorderLayout.NORTH);

    // -----------------------------------------------------
    // Item dropdown
    // -----------------------------------------------------

    String[] names = new String[inactiveItems.size()];

    for (int i = 0; i < inactiveItems.size(); i++) {
      names[i] = inactiveItems.get(i).name;
    }

    itemBox = new JComboBox<String>(names);
    itemBox.setFont(PandaTheme.BODY);

    unitValue = PandaTheme.label("", PandaTheme.BODY, PandaTheme.BLACK);

    thresholdValue = PandaTheme.label("", PandaTheme.BODY, PandaTheme.BLACK);

    shelfLifeValue = PandaTheme.label("", PandaTheme.BODY, PandaTheme.BLACK);

    quantityField = PandaTheme.textField(18);

    JPanel form = new JPanel(new GridBagLayout());
    form.setOpaque(false);

    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(5, 0, 5, 12);
    c.anchor = GridBagConstraints.WEST;

    addRow(form, c, 0, "Discontinued item", itemBox);

    addRow(form, c, 1, "Unit", unitValue);

    addRow(form, c, 2, "Minimum threshold", thresholdValue);

    addRow(form, c, 3, "Shelf life", shelfLifeValue);

    addRow(form, c, 4, "Starting quantity", quantityField);

    root.add(form, BorderLayout.CENTER);

    // -----------------------------------------------------
    // Buttons
    // -----------------------------------------------------

    error = PandaTheme.label(" ", PandaTheme.SMALL, PandaTheme.RED);

    JButton cancel = PandaTheme.secondaryButton("Cancel");

    JButton reactivate = PandaTheme.primaryButton("Reactivate Item");

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

    buttons.setOpaque(false);
    buttons.add(cancel);
    buttons.add(reactivate);

    JPanel bottom = new JPanel(new BorderLayout(0, 8));
    bottom.setOpaque(false);

    bottom.add(error, BorderLayout.NORTH);
    bottom.add(buttons, BorderLayout.SOUTH);

    root.add(bottom, BorderLayout.SOUTH);

    // -----------------------------------------------------
    // Selection changes
    // -----------------------------------------------------

    itemBox.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        updateItemDetails();
      }
    });

    cancel.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        dispose();
      }
    });

    reactivate.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        reactivateSelectedItem();
      }
    });

    updateItemDetails();

    getRootPane().setDefaultButton(reactivate);

    add(root);
  }

  /**
   * Adds a row to the form panel with a label and a field.
   *
   * @param panel form panel
   * @param c shared grid bag constraints
   * @param row row index
   * @param label label text
   * @param field field component
   */
  private void addRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent field) {
    c.gridy = row;

    c.gridx = 0;
    c.weightx = 0;
    c.fill = GridBagConstraints.NONE;

    panel.add(PandaTheme.label(label, PandaTheme.BOLD, PandaTheme.BLACK), c);

    c.gridx = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;

    panel.add(field, c);
  }

  /** Updates the details of the selected inventory item in the form fields. */
  private void updateItemDetails() {
    int index = itemBox.getSelectedIndex();

    if (index < 0) {
      return;
    }

    InventoryDAO.Item item = inactiveItems.get(index);

    unitValue.setText(item.unit);

    thresholdValue.setText(String.format("%.2f", item.threshold));

    shelfLifeValue.setText(item.shelfLifeDays + " days");

    quantityField.setText("");
  }

  /** Reactivates the selected inventory item after confirming the starting quantity. */
  private void reactivateSelectedItem() {
    int index = itemBox.getSelectedIndex();

    if (index < 0) {
      error.setText("Select an inventory item.");
      return;
    }

    InventoryDAO.Item item = inactiveItems.get(index);

    Double quantity = parseNonNegative(quantityField.getText());

    if (quantity == null) {
      error.setText("Starting quantity must be a non-negative number.");
      return;
    }

    int confirm = JOptionPane.showConfirmDialog(this,
      "Reactivate \"" + item.name + "\" with a starting quantity of " + quantity + " " + item.unit
        + "?",
      "Reactivate Inventory Item", JOptionPane.YES_NO_OPTION);

    if (confirm != JOptionPane.YES_OPTION) {
      return;
    }

    try {
      dao.reactivateInventoryItem(item.id, quantity);

      if (onReactivated != null) {
        onReactivated.run();
      }

      JOptionPane.showMessageDialog(this, "\"" + item.name + "\" was reactivated.");

      dispose();

    } catch (SQLException ex) {
      error.setText("Reactivation failed: " + ex.getMessage());
    }
  }

  /**
   * Parses a non-negative double from the given text.
   *
   * @param text input text
   * @return parsed value, or null if invalid
   */
  private static Double parseNonNegative(String text) {
    try {
      double value = Double.parseDouble(text.trim());

      if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
        return null;
      }

      return value;

    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
