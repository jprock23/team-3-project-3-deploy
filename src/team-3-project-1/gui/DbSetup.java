/**
 * CSCE 331
 * Database connection settings.
 * Purpose: Keeping the database URL, username, and password out of source code.
 *
 * Settings are read from environment variables first, then from a {@code .env}
 * file in the current directory or any parent directory (the repository root
 * holds one locally; it is git-ignored and must never be committed):
 *
 * <pre>
 * DB_URL=jdbc:postgresql://&lt;host&gt;/neondb?sslmode=require
 * DB_USER=&lt;user&gt;
 * DB_PASSWORD=&lt;password&gt;
 * </pre>
 *
 * Copy {@code .env.example} at the repository root to {@code .env} and fill it in.
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DbSetup {
  /** Values loaded from the nearest {@code .env} file, if any. */
  private static final Map<String, String> DOT_ENV = loadDotEnv();

  /** JDBC connection URL, e.g. {@code jdbc:postgresql://host/neondb?sslmode=require}. */
  public static final String URL = require("DB_URL");

  /** Database username. */
  public static final String USER = require("DB_USER");

  /** Database password. */
  public static final String PASSWORD = require("DB_PASSWORD");

  /**
   * Opens a new connection using the configured settings.
   *
   * @return an open database connection
   * @throws SQLException if the connection fails
   */
  public static Connection getConnection() throws SQLException {
    return DriverManager.getConnection(URL, USER, PASSWORD);
  }

  /**
   * Looks up a setting, preferring a real environment variable over {@code .env}.
   *
   * @param key setting name
   * @return the setting's value
   * @throws IllegalStateException if the setting is missing
   */
  private static String require(String key) {
    String value = System.getenv(key);
    if (value == null || value.isEmpty()) {
      value = DOT_ENV.get(key);
    }
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("Missing database setting " + key
          + ". Set it as an environment variable or in the repository's .env file"
          + " (see .env.example).");
    }
    return value;
  }

  /**
   * Reads KEY=VALUE lines from the first {@code .env} found walking up from the
   * working directory. Blank lines and lines starting with # are skipped.
   *
   * @return the parsed settings, or an empty map if no file is found
   */
  private static Map<String, String> loadDotEnv() {
    Map<String, String> values = new HashMap<String, String>();
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path file = dir.resolve(".env");
      if (Files.isRegularFile(file)) {
        try {
          List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
          for (String line : lines) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
              continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
              value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
          }
        } catch (IOException e) {
          System.err.println("Could not read " + file + ": " + e.getMessage());
        }
        break;
      }
      dir = dir.getParent();
    }
    return values;
  }

  /** Not instantiated for settings; the constructor is kept for existing callers. */
  public DbSetup() { }
} // end class
