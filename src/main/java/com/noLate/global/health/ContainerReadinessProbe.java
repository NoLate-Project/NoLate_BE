package com.noLate.global.health;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Dependency-free Docker health probe for the runtime JRE image.
 *
 * Keeping the probe on the JRE avoids installing curl solely for Docker health checks. It accepts
 * only the application's exact readiness 200 response and never follows redirects.
 */
public final class ContainerReadinessProbe {

    private static final int DEFAULT_PORT = 5522;
    private static final int TIMEOUT_MILLIS = 3_000;

    private ContainerReadinessProbe() {
    }

    public static void main(String[] args) {
        try {
            System.exit(isReady(readinessUri(System.getenv("SERVER_PORT"))) ? 0 : 1);
        } catch (Exception ignored) {
            // A health command must fail closed without printing connection or environment details.
            System.exit(1);
        }
    }

    static URI readinessUri(String configuredPort) {
        int port = DEFAULT_PORT;
        if (configuredPort != null && !configuredPort.isBlank()) {
            port = Integer.parseInt(configuredPort);
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Server port is outside the TCP range.");
        }
        return URI.create("http://127.0.0.1:" + port + "/health/readiness");
    }

    static boolean isReady(URI endpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
