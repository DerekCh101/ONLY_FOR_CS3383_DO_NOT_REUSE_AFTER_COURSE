package org.ashoreprospect.net;

import org.ashoreprospect.TaskManagementSystem;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

public class IsolatedTestEnvironment implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    // Port 0 cannot accept connections; this address also skips automatic Postgres setup.
    private static final String OFFLINE_NODE = "http://127.0.0.2:0";

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        String rootProperty = System.getProperty("scheduler.test.root", "");
        if (rootProperty.isBlank()) {
            throw unsafeEnvironment();
        }
        Path root = Path.of(rootProperty).toAbsolutePath().normalize();
        Path work = Path.of("").toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (!work.equals(root.resolve("work")) || !home.equals(root.resolve("home"))
                || !OsEnv.userEnvFile().toAbsolutePath().normalize().startsWith(home)
                || !environmentIsBlank(Endpoints.NODE_ENV)
                || !environmentIsBlank(Endpoints.PG_ENV)
                || !environmentIsBlank("PGPASSWORD")
                || !environmentIsBlank("SMTP_HOST")
                || !environmentIsBlank("SMTP_PORT")
                || !environmentIsBlank("SMTP_USER")
                || !environmentIsBlank("SMTP_FROM")
                || !environmentIsBlank("SMTP_PASS")) {
            throw unsafeEnvironment();
        }
        try {
            DriverManager.getDriver("jdbc:postgresql://127.0.0.1:5432/Scheduler");
        } catch (SQLException expected) {
            return;
        }
        throw unsafeEnvironment();
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        TestEnv.set(Endpoints.NODE_ENV, OFFLINE_NODE);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        TaskManagementSystem.resetInstance();
        TestEnv.set(Endpoints.NODE_ENV, OFFLINE_NODE);
    }

    private static boolean environmentIsBlank(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank();
    }

    private static IllegalStateException unsafeEnvironment() {
        return new IllegalStateException(
                "Tests require the isolated Gradle test environment. Run ./gradlew test.");
    }
}
