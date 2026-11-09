/** Draws bar charts for manager reports. */

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;

/** Simple bar chart panel for manager reports, drawn with Java2D. */
public class BarGraph extends JPanel {
  private String title;
  private String[] labels;
  private double[] values;

  private static final int PADDING = 60; // space around the plot area
  private static final int TICK_COUNT = 5; // number of y-axis gridlines
  private static final Color BAR_COLOR = new Color(66, 133, 244);
  private static final Color GRID_COLOR = new Color(220, 220, 220);

  /**
   * Creates a bar graph with the given data.
   *
   * @param title  chart title drawn at the top
   * @param labels category label for each bar
   * @param values bar heights, parallel to labels
   * @throws IllegalArgumentException if labels and values differ in length
   */
  public BarGraph(String title, String[] labels, double[] values) {
    if (labels.length != values.length) {
      throw new IllegalArgumentException("labels and values must be the same length");
    }
    this.title = title;
    this.labels = labels;
    this.values = values;
    setPreferredSize(new Dimension(800, 500));
    setBackground(Color.WHITE);
  }

  /**
   * Replaces the chart data and repaints, keeping the current title.
   *
   * @param labels category label for each bar
   * @param values bar heights, parallel to labels
   * @throws IllegalArgumentException if labels and values differ in length
   */
  public void setData(String[] labels, double[] values) {
    if (labels.length != values.length) {
      throw new IllegalArgumentException("labels and values must be the same length");
    }
    this.labels = labels;
    this.values = values;
    repaint();
  }

  /** Draws the title, gridlines, bars, and axes. */
  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(
      RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    int width = getWidth();
    int height = getHeight();
    int plotLeft = PADDING;
    int plotRight = width - PADDING / 2;
    int plotTop = PADDING;
    int plotBottom = height - PADDING;
    int plotWidth = plotRight - plotLeft;
    int plotHeight = plotBottom - plotTop;

    double maxValue = niceMax(max(values));

    g2.setFont(new Font("SansSerif", Font.BOLD, 18));
    FontMetrics tfm = g2.getFontMetrics();
    g2.setColor(Color.BLACK);
    g2.drawString(title, (width - tfm.stringWidth(title)) / 2, PADDING / 2 + tfm.getAscent() / 2);

    if (values.length == 0) {
      g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
      String msg = "No data for this range";
      g2.setColor(Color.GRAY);
      g2.drawString(msg, (width - g2.getFontMetrics().stringWidth(msg)) / 2, height / 2);
      return;
    }

    // Gridlines + y-axis labels
    g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
    FontMetrics fm = g2.getFontMetrics();
    for (int i = 0; i <= TICK_COUNT; i++) {
      double tickValue = maxValue * i / TICK_COUNT;
      int y = plotBottom - (int) Math.round(plotHeight * (tickValue / maxValue));
      g2.setColor(GRID_COLOR);
      g2.drawLine(plotLeft, y, plotRight, y);
      String text = formatNumber(tickValue);
      g2.setColor(Color.DARK_GRAY);
      g2.drawString(text, plotLeft - fm.stringWidth(text) - 8, y + fm.getAscent() / 2 - 1);
    }

    // Bars
    int n = values.length;
    double slot = (double) plotWidth / n;
    double barWidth = slot * 0.6;
    for (int i = 0; i < n; i++) {
      double barHeight = plotHeight * (values[i] / maxValue);
      double x = plotLeft + i * slot + (slot - barWidth) / 2;
      double y = plotBottom - barHeight;

      g2.setColor(BAR_COLOR);
      g2.fill(new Rectangle2D.Double(x, y, barWidth, barHeight));

      // Value above bar
      String valueText = formatNumber(values[i]);
      g2.setColor(Color.BLACK);
      g2.drawString(valueText, (int) (x + (barWidth - fm.stringWidth(valueText)) / 2), (int) y - 5);

      // Category label below axis
      String label = labels[i];
      g2.setColor(Color.DARK_GRAY);
      g2.drawString(
        label, (int) (x + (barWidth - fm.stringWidth(label)) / 2), plotBottom + fm.getAscent() + 6);
    }

    // Axes
    g2.setColor(Color.BLACK);
    g2.setStroke(new BasicStroke(1.5f));
    g2.drawLine(plotLeft, plotTop, plotLeft, plotBottom);
    g2.drawLine(plotLeft, plotBottom, plotRight, plotBottom);
  }

  /** Returns the largest value in the array, or 0 if all are negative. */
  private static double max(double[] arr) {
    double m = 0;
    for (double v : arr)
      m = Math.max(m, v);
    return m;
  }

  /** Rounds the max up to a "nice" number (1, 2, 5 × 10^k) so ticks are clean. */
  private static double niceMax(double value) {
    if (value <= 0)
      return 1;
    double exponent = Math.floor(Math.log10(value));
    double fraction = value / Math.pow(10, exponent);
    double niceFraction;
    if (fraction <= 1)
      niceFraction = 1;
    else if (fraction <= 2)
      niceFraction = 2;
    else if (fraction <= 5)
      niceFraction = 5;
    else
      niceFraction = 10;
    return niceFraction * Math.pow(10, exponent);
  }

  /** Formats whole numbers without decimals, others with one decimal place. */
  private static String formatNumber(double v) {
    return (v == Math.rint(v)) ? String.valueOf((long) v) : String.format("%.1f", v);
  }
}
