package com.kayple.reservation.support;

import org.springframework.test.context.DynamicPropertyRegistry;

public final class IntegrationTestProperties {

    private IntegrationTestProperties() {
    }

    public static void registerCommon(DynamicPropertyRegistry registry) {
        var mysql = MySQLTestcontainer.mysql();
        registry.add("DB_URL", mysql::getJdbcUrl);
        registry.add("DB_USERNAME", mysql::getUsername);
        registry.add("DB_PASSWORD", mysql::getPassword);
        registry.add("ADMIN_USERNAME", () -> "admin");
        registry.add("ADMIN_PASSWORD", () -> "adminpass");
        registry.add("SMS_API_KEY", () -> "test-sms-key");
        registry.add("SESSION_COOKIE_SECURE", () -> "false");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("app.sms.worker.enabled", () -> "false");
        registry.add("app.sms.worker.fixed-delay-ms", () -> "3600000");
    }
}
