/** Draws hourly sales for the daily X report. */

import java.awt.*;
import javax.swing.*;

/** Line chart of hourly sales for the X Report, one point per hour of day. */
public class XReportGraph extends JPanel {
  private double[] sales = new double[24];

  /** Creates an empty hourly sales graph. */
  public XReportGraph() {
    setBackground(Color.WHITE);
  }

  /**
   * Replaces the hourly sales data and repaints.
   *
   * @param data sales totals indexed by hour of day (0-23)
   */
  public void setData(double[] data) {
    this.sales = data.clone();
    repaint();
  }

  /** Draws the grid, axes, hourly sales line, and hour labels. */
  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);

    Graphics2D g2 = (Graphics2D) g.create();

    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int width = getWidth();
    int height = getHeight();

    int left = 60;
    int right = 20;
    int top = 30;
    int bottom = 55;

    int graphWidth = width - left - right;
    int graphHeight = height - top - bottom;

    if (graphWidth <= 0 || graphHeight <= 0) {
      g2.dispose();
      return;
    }

    // Find the highest hourly sales amount.
    double maxSales = 0;

    for (double value : sales) {
      maxSales = Math.max(maxSales, value);
    }

    // Make the graph scale readable.
    maxSales = Math.max(100, Math.ceil(maxSales / 100.0) * 100);

    // Draw horizontal grid lines and dollar labels.
    g2.setFont(new Font("SansSerif", Font.PLAIN, 11));

    for (int i = 0; i <= 5; i++) {
      int y = top + graphHeight - (i * graphHeight / 5);

      g2.setColor(new Color(225, 225, 225));
      g2.drawLine(left, y, left + graphWidth, y);

      g2.setColor(Color.DARK_GRAY);

      String label = String.format("$%.0f", maxSales * i / 5);

      g2.drawString(label, 8, y + 4);
    }

    // Draw axes.
    g2.setColor(Color.DARK_GRAY);
    g2.drawLine(left, top, left, top + graphHeight);
    g2.drawLine(left, top + graphHeight, left + graphWidth, top + graphHeight);

    // Draw hourly sales as a red line.
    g2.setColor(new Color(211, 47, 47));
    g2.setStroke(new BasicStroke(2.5f));

    int previousX = -1;
    int previousY = -1;

    for (int hour = 0; hour < 24; hour++) {
      int x = left + hour * graphWidth / 23;

      int y = top + graphHeight - (int) (sales[hour] / maxSales * graphHeight);

      if (previousX != -1) {
        g2.drawLine(previousX, previousY, x, y);
      }

      g2.fillOval(x - 3, y - 3, 6, 6);

      previousX = x;
      previousY = y;
    }

    // Draw hour labels every two hours.
    g2.setColor(Color.DARK_GRAY);
    g2.setFont(new Font("SansSerif", Font.PLAIN, 10));

    for (int hour = 0; hour < 24; hour += 2) {
      int x = left + hour * graphWidth / 23;

      String label;

      if (hour == 0) {
        label = "12 AM";
      } else if (hour < 12) {
        label = hour + " AM";
      } else if (hour == 12) {
        label = "12 PM";
      } else {
        label = (hour - 12) + " PM";
      }

      g2.drawString(label, x - 14, top + graphHeight + 20);
    }

    g2.setFont(new Font("SansSerif", Font.BOLD, 12));
    g2.drawString("Hour of Day", left + graphWidth / 2 - 35, height - 10);

    g2.dispose();
  }
}
