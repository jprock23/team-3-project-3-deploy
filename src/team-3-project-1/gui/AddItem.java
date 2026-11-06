/** Provides the dialog for adding an inventory ingredient. */

import java.sql.SQLException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

/**
 * Form for adding a brand-new Inventory item.
 *
 * Discontinued inventory items should be restored using
 * ReactivateItem instead of being inserted again.
 */
public class AddItem extends JDialog {
  /**
   * Creates the modal "Add Inventory Item" dialog.
   *
   * @param owner   parent frame the dialog is centered on
   * @param dao     data access object used to insert the item
   * @param onAdded callback run after a successful insert (may be null)
   */
  public AddItem(JFrame owner, final InventoryDAO dao, final Runnable onAdded) {
    super(owner, "Add Inventory Item", true);

    setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

    final JTextField name = PandaTheme.textField(18);
    final JTextField unit = PandaTheme.textField(18);
    final JTextField threshold = PandaTheme.textField(18);
    final JTextField quantity = PandaTheme.textField(18);
    final JTextField shelfLife = PandaTheme.textField(18);

    JPanel root = new JPanel(new BorderLayout(0, 12));
    root.setBackground(PandaTheme.WHITE);
    root.setBorder(new EmptyBorder(18, 20, 18, 20));

    root.add(PandaTheme.label("Add Inventory Item", PandaTheme.HEADING, PandaTheme.BLACK),
      BorderLayout.NORTH);

    JPanel form = new JPanel(new GridBagLayout());
    form.setOpaque(false);

    String[] labels = {
      "Item name", "Unit", "Minimum threshold", "Starting quantity", "Shelf life (days)"};

    JComponent[] fields = {name, unit, threshold, quantity, shelfLife};

    GridBagConstraints constraints = new GridBagConstraints();
    constraints.insets = new Insets(5, 0, 5, 12);
    constraints.anchor = GridBagConstraints.WEST;

    for (int i = 0; i < labels.length; i++) {
      constraints.gridy = i;

      constraints.gridx = 0;
      constraints.weightx = 0;
      constraints.fill = GridBagConstraints.NONE;

      form.add(PandaTheme.label(labels[i], PandaTheme.BOLD, PandaTheme.BLACK), constraints);

      constraints.gridx = 1;
      constraints.weightx = 1;
      constraints.fill = GridBagConstraints.HORIZONTAL;

      form.add(fields[i], constraints);
    }

    root.add(form, BorderLayout.CENTER);

    final JLabel error = PandaTheme.label(" ", PandaTheme.SMALL, PandaTheme.RED);

    JButton cancel = PandaTheme.secondaryButton("Cancel");

    JButton ok = PandaTheme.primaryButton("Add Item");

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

    buttons.setOpaque(false);
    buttons.add(cancel);
    buttons.add(ok);

    JPanel bottom = new JPanel(new BorderLayout(0, 8));
    bottom.setOpaque(false);

    bottom.add(error, BorderLayout.NORTH);
    bottom.add(buttons, BorderLayout.SOUTH);

    root.add(bottom, BorderLayout.SOUTH);

    cancel.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        dispose();
      }
    });

    ok.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        String itemName = name.getText().trim();
        String itemUnit = unit.getText().trim();

        Double minThreshold = parseNonNegative(threshold.getText());

        Double startingQuantity = parseNonNegative(quantity.getText());

        Integer shelfLifeDays = parseNonNegativeInt(shelfLife.getText());

        String err = null;

        if (itemName.isEmpty()) {
          err = "Item name is required.";
        } else if (itemName.length() > 100) {
          err = "Item name is too long (max 100 characters).";
        } else if (itemUnit.isEmpty()) {
          err = "Unit is required.";
        } else if (itemUnit.length() > 20) {
          err = "Unit is too long (max 20 characters).";
        } else if (minThreshold == null) {
          err = "Minimum threshold must be a non-negative number.";
        } else if (startingQuantity == null) {
          err = "Starting quantity must be a non-negative number.";
        } else if (shelfLifeDays == null) {
          err = "Shelf life must be a non-negative whole number.";
        }

        if (err != null) {
          error.setText(err);
          return;
        }

        try {
          dao.insertItem(itemName, itemUnit, minThreshold, startingQuantity, shelfLifeDays);

          if (onAdded != null) {
            onAdded.run();
          }

          JOptionPane.showMessageDialog(
            AddItem.this, "\"" + itemName + "\" was added to inventory.");

          dispose();

        } catch (SQLException ex) {
          error.setText("Add failed: " + ex.getMessage());
        }
      }
    });

    getRootPane().setDefaultButton(ok);

    add(root);
    pack();
    setLocationRelativeTo(owner);
  }

  /**
   * Parses a non-negative double from a string.
   *
   * @param text raw user input
   * @return the parsed value, or null if invalid or negative
   */
  static Double parseNonNegative(String text) {
    try {
      double d = Double.parseDouble(text.trim());

      if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) {
        return null;
      }

      return d;

    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Parses a non-negative integer from a string.
   *
   * @param text raw user input
   * @return the parsed value, or null if invalid or negative
   */
  static Integer parseNonNegativeInt(String text) {
    try {
      int value = Integer.parseInt(text.trim());

      if (value < 0) {
        return null;
      }

      return value;

    } catch (NumberFormatException e) {
      return null;
    }
  }
}
