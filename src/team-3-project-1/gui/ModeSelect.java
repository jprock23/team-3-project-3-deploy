/** Opens the shared database connection and selects the application mode. */

import java.sql.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

/**
 * Entry screen: choose Cashier or Manager mode.
 *
 * Opens one database connection and hands it to whichever GUI is picked.
 * ManagerGUI has a "Switch Mode" button that comes back here;
 * CashierGUI has one that opens the manager side after a PIN check.
 */
public class ModeSelect {
  static Connection conn = null;

  JFrame frame;
  final Connection db;

  /**
   * Entry point: opens the database connection and shows the mode picker.
   *
   * @param args unused
   */
  public static void main(String args[]) {
    openConnection(new DbSetup());
    PandaTheme.useSystemLook();
    SwingUtilities.invokeLater(new Runnable() {
      /** Runs the UI initialization or refresh callback. */
      public void run() {
        new ModeSelect(conn).build();
      }
    });
  }

  /**
   * Opens a connection to the database using the provided credentials.
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

  /** Closes the database connection if it is open. */
  static void closeConnection() {
    try {
      conn.close();
    } catch (Exception e) { /* ignore */
    }
  }

  /**
   * Creates the mode picker backed by the given connection.
   *
   * @param c open database connection handed to whichever GUI is picked
   */
  public ModeSelect(Connection c) {
    db = c;
  }

  /** Builds the main frame for selecting the mode (Cashier or Manager). */
  void build() {
    frame = new JFrame("Panda Express - Select Mode");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setSize(460, 320);
    frame.setLocationRelativeTo(null);

    JPanel root = new JPanel(new BorderLayout());
    root.setBackground(PandaTheme.WHITE);

    // red header
    JPanel header = new JPanel();
    header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
    header.setBackground(PandaTheme.RED);
    header.setBorder(new EmptyBorder(18, 24, 18, 24));
    JLabel title = PandaTheme.label("PANDA EXPRESS", PandaTheme.TITLE, PandaTheme.WHITE);
    JLabel sub = PandaTheme.label("Point of Sale", PandaTheme.BODY, PandaTheme.WHITE);
    header.add(title);
    header.add(sub);

    // mode buttons
    JPanel body = new JPanel(new GridBagLayout());
    body.setBackground(PandaTheme.WHITE);
    body.setBorder(new EmptyBorder(16, 24, 24, 24));
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    c.insets = new Insets(6, 0, 6, 0);

    body.add(PandaTheme.label("Select a mode", PandaTheme.HEADING, PandaTheme.BLACK), c);

    JButton cashier = PandaTheme.primaryButton("Cashier");
    cashier.setFont(PandaTheme.HEADING);
    cashier.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        openCashier();
      }
    });
    body.add(cashier, c);

    JButton manager = PandaTheme.secondaryButton("Manager");
    manager.setFont(PandaTheme.HEADING);
    manager.addActionListener(new ActionListener() {
      /** Handles this control's user action. */
      public void actionPerformed(ActionEvent e) {
        openManager();
      }
    });
    body.add(manager, c);

    root.add(header, BorderLayout.NORTH);
    root.add(body, BorderLayout.CENTER);
    frame.add(root);
    frame.setVisible(true);
  }

  /** Opens the Cashier GUI and closes the current frame. */
  void openCashier() {
    frame.dispose();
    PandaTheme.useJavaLook();
    CashierGUI.conn = db;
    new CashierGUI().build();
  }

  /** Opens the Manager GUI and closes the current frame. */
  void openManager() {
    frame.dispose();
    new ManagerGUI(db).build(conn);
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
