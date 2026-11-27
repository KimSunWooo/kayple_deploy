package com.kayple.reservation.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared MySQL 8.0 container for integration tests. Started once per JVM when Docker is available.
 */
public final class MySQLTestcontainer {

    private static final boolean AVAILABLE = detectDocker();

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = AVAILABLE
            ? new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("app")
            .withUsername("app")
            .withPassword("app")
            .withReuse(false)
            : null;

    static {
        if (AVAILABLE) {
            MYSQL.start();
        }
    }

    private MySQLTestcontainer() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static MySQLContainer<?> mysql() {
        if (!AVAILABLE) {
            throw new IllegalStateException("Docker/MySQL testcontainer unavailable");
        }
        return MYSQL;
    }

    private static boolean detectDocker() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
