package org.example.reviewer;

import java.io.InputStream;
import java.util.Properties;

/**
 * Enterprise Configuration Manager.
 * Implements a hierarchical fallback strategy:
 * 1. System Environment Variables (Highest Priority - ideal for CI/CD and Docker)
 * 2. application.properties (Ideal for local testing)
 * 3. Default Fallbacks
 */
public class ConfigLoader {

    private static final Properties properties = new Properties();

    static {
        try (InputStream stream = ConfigLoader.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (Exception e) {
            System.err.println("ℹ️ [Config] Note: Could not load application.properties (" + e.getMessage() + "). Relying on Environment Variables.");
        }
    }

    /**
     * Resolves a configuration value by checking environment variables first, then properties.
     *
     * @param envKey       Environment variable name (e.g. GOOGLE_API_KEY)
     * @param propKey      Property key in application.properties (e.g. google.api.key)
     * @param defaultValue Default value if neither is configured
     * @return Resolved configuration string
     */
    public static String get(String envKey, String propKey, String defaultValue) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String propValue = properties.getProperty(propKey);
        if (propValue != null && !propValue.isBlank()) {
            return propValue.trim();
        }

        return defaultValue;
    }

    /**
     * Resolves an integer configuration value with fallback.
     */
    public static int getInt(String propKey, int defaultValue) {
        String propValue = properties.getProperty(propKey);
        if (propValue != null && !propValue.isBlank()) {
            try {
                return Integer.parseInt(propValue.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
