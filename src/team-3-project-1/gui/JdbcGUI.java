/** Demonstrates displaying JDBC query results in a Swing window. */

import java.sql.*;
import javax.swing.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

/**
 * CSCE 331
 * 9-25-2019 Original
 * 2/7/2020 Update for AWS
 * <p>
 * Early JDBC Swing demo: opens a connection to the team database and
 * displays the MenuItem table in a simple window.
 */
public class JdbcGUI {
  // Database connection object
  static Connection conn = null;

  /**
   * Opens the database connection using the given credentials.
   *
   * @param credentials database username and password
   */
  public static void openConnection(DbSetup credentials) {
    try {
      conn = DriverManager.getConnection("jdbc:postgresql://csce-315-db.engr.tamu.edu/team3db",
        credentials.USER, credentials.PASSWORD);
    } catch (Exception e) {
      e.printStackTrace();
      System.err.println(e.getClass().getName() + ": " + e.getMessage());
      System.exit(0);
    }
    JOptionPane.showMessageDialog(null, "Opened database successfully");
  }

  /**
   * Closes the database connection.
   */
  public static void closeConnection() {
    try {
      conn.close();
      JOptionPane.showMessageDialog(null, "Connection closed.");
    } catch (Exception e) {
      JOptionPane.showMessageDialog(null, "Connection NOT closed.");
    }
  }

  /**
   * Runs the demo: connects, queries the MenuItem table, and shows the result in a window.
   *
   * @param args command line arguments (unused)
   */
  public static void main(String args[]) {
    // DbSetup hides the username and password
    DbSetup credentials = new DbSetup();

    // build the connection
    openConnection(credentials);

    try {
      // create an sql statement
      Statement stmt = conn.createStatement();
      String sqlStatement = "SELECT * FROM MenuItem";

      // send the sql statement to the db
      ResultSet result = stmt.executeQuery(sqlStatement);

      // gather result
      String data = String.format("%-30s", "Vendors") + "\tProducts\n";
      data += "==========================================\n";
      while (result.next()) {
        data += String.format("%-30s", result.getString("menuitemname")) + "\t"
          + result.getString("price") + "\n";
      }

      // output result
      // creating a custom JFrame that displays the result and closes the window and connection when
      // "Done" button clicked
      JFrame frame = new JFrame("DB GUI");
      frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
      frame.setSize(600, 400);
      frame.setLocationRelativeTo(null); // center window on screen

      JTextArea text = new JTextArea(data);

      JButton button = new JButton("Done");
      button.addActionListener(new ActionListener() {
        /** Handles this control's user action. */
        @Override
        public void actionPerformed(ActionEvent e) {
          if (e.getActionCommand().equals("Done")) {
            frame.dispose();
            closeConnection();
            System.exit(0);
          }
        }
      });

      JPanel panel = new JPanel();
      panel.add(text);
      panel.add(button);

      frame.add(panel);
      frame.setVisible(true);

    } catch (Exception e) {
      JOptionPane.showMessageDialog(null, "Error accessing database.");
    }
  }
}
