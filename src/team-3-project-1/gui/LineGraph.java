/** Draws line charts for manager reports. */

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/** Simple line chart panel for manager reports, drawn with Java2D. */
public class LineGraph extends JPanel {
  private String title;
  private String[] labels;
  private double[] values;

  private static final int PADDING = 60; // space around the plot area
  private static final int TICK_COUNT = 5; // number of y-axis gridlines
  private static final int POINT_RADIUS = 4;
  private static final Color LINE_COLOR = new Color(66, 133, 244);
  private static final Color GRID_COLOR = new Color(220, 220, 220);

  /**
   * Creates a line graph with the given data.
   *
   * @param title  chart title drawn at the top
   * @param labels x-axis label for each point
   * @param values point heights, parallel to labels
   * @throws IllegalArgumentException if labels and values differ in length
   */
  public LineGraph(String title, String[] labels, double[] values) {
    setData(title, labels, values);
    setPreferredSize(new Dimension(800, 500));
    setBackground(Color.WHITE);
  }

  /**
   * Replaces the chart data and repaints, keeping the current title.
   *
   * @param labels x-axis label for each point
   * @param values point heights, parallel to labels
   * @throws IllegalArgumentException if labels and values differ in length
   */
  public void setData(String[] labels, double[] values) {
    setData(this.title, labels, values);
  }

  /**
   * Replaces the title and chart data, then repaints.
   *
   * @param title  chart title drawn at the top
   * @param labels x-axis label for each point
   * @param values point heights, parallel to labels
   * @throws IllegalArgumentException if labels and values differ in length
   */
  public void setData(String title, String[] labels, double[] values) {
    if (labels.length != values.length) {
      throw new IllegalArgumentException("labels and values must be the same length");
    }
    this.title = title;
    this.labels = labels;
    this.values = values;
    repaint();
  }

  /** Draws the title, gridlines, line with points, and axes. */
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

    // Title
    g2.setFont(new Font("SansSerif", Font.BOLD, 18));
    FontMetrics tfm = g2.getFontMetrics();
    g2.setColor(Color.BLACK);
    g2.drawString(title, (width - tfm.stringWidth(title)) / 2, PADDING / 2 + tfm.getAscent() / 2);

    if (values.length == 0)
      return;

    // Y range: start at 0 unless data goes negative
    double dataMin = Math.min(0, min(values));
    double dataMax = Math.max(0, max(values));
    double yMin = dataMin < 0 ? -niceMax(-dataMin) : 0;
    double yMax = dataMax > 0 ? niceMax(dataMax) : 0;
    if (yMax == yMin)
      yMax = yMin + 1;
    double range = yMax - yMin;

    // Gridlines + y-axis labels
    g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
    FontMetrics fm = g2.getFontMetrics();
    for (int i = 0; i <= TICK_COUNT; i++) {
      double tickValue = yMin + range * i / TICK_COUNT;
      int y = toY(tickValue, yMin, range, plotBottom, plotHeight);
      g2.setColor(GRID_COLOR);
      g2.drawLine(plotLeft, y, plotRight, y);
      String text = formatNumber(tickValue);
      g2.setColor(Color.DARK_GRAY);
      g2.drawString(text, plotLeft - fm.stringWidth(text) - 8, y + fm.getAscent() / 2 - 1);
    }

    // X positions: spread points evenly, centered in equal slots
    int n = values.length;
    double slot = (double) plotWidth / n;
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      xs[i] = plotLeft + slot * i + slot / 2;
      ys[i] = toY(values[i], yMin, range, plotBottom, plotHeight);
    }

    // Line
    Path2D path = new Path2D.Double();
    path.moveTo(xs[0], ys[0]);
    for (int i = 1; i < n; i++)
      path.lineTo(xs[i], ys[i]);
    g2.setColor(LINE_COLOR);
    g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g2.draw(path);

    // Points, value labels, x labels
    g2.setStroke(new BasicStroke(1f));
    for (int i = 0; i < n; i++) {
      g2.setColor(Color.WHITE);
      g2.fill(new Ellipse2D.Double(
        xs[i] - POINT_RADIUS, ys[i] - POINT_RADIUS, POINT_RADIUS * 2, POINT_RADIUS * 2));
      g2.setColor(LINE_COLOR);
      g2.draw(new Ellipse2D.Double(
        xs[i] - POINT_RADIUS, ys[i] - POINT_RADIUS, POINT_RADIUS * 2, POINT_RADIUS * 2));

      String valueText = formatNumber(values[i]);
      g2.setColor(Color.BLACK);
      g2.drawString(
        valueText, (int) (xs[i] - fm.stringWidth(valueText) / 2.0), (int) ys[i] - POINT_RADIUS - 5);

      String label = labels[i];
      g2.setColor(Color.DARK_GRAY);
      g2.drawString(
        label, (int) (xs[i] - fm.stringWidth(label) / 2.0), plotBottom + fm.getAscent() + 6);
    }

    // Axes (x-axis drawn at zero)
    g2.setColor(Color.BLACK);
    g2.setStroke(new BasicStroke(1.5f));
    int zeroY = toY(0, yMin, range, plotBottom, plotHeight);
    g2.drawLine(plotLeft, plotTop, plotLeft, plotBottom);
    g2.drawLine(plotLeft, zeroY, plotRight, zeroY);
  }

  /** Maps a data value to a y pixel within the plot area. */
  private static int toY(double v, double yMin, double range, int plotBottom, int plotHeight) {
    return plotBottom - (int) Math.round(plotHeight * ((v - yMin) / range));
  }

  /** Returns the largest value in the array. */
  private static double max(double[] arr) {
    double m = Double.NEGATIVE_INFINITY;
    for (double v : arr)
      m = Math.max(m, v);
    return m;
  }

  /** Returns the smallest value in the array. */
  private static double min(double[] arr) {
    double m = Double.POSITIVE_INFINITY;
    for (double v : arr)
      m = Math.min(m, v);
    return m;
  }

  /** Rounds up to a "nice" number (1, 2, 5 × 10^k) so ticks are clean. */
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
