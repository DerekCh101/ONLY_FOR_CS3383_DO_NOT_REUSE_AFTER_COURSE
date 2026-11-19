package org.ashoreprospect.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedTestEnvironmentTest {

    @Test
    void refusesToRunWithoutTheGradleIsolation() {
        String root = System.getProperty("scheduler.test.root");
        try {
            System.clearProperty("scheduler.test.root");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new IsolatedTestEnvironment().beforeAll(null));
            assertTrue(error.getMessage().contains("isolated Gradle test environment"));
        } finally {
            System.setProperty("scheduler.test.root", root);
        }
    }
}
