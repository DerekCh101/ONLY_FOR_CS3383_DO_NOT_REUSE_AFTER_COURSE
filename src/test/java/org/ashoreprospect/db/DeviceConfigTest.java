/**
 * Stable install id: {@code scheduler-device-id.txt} is created once, read back trimmed,
 * and regenerated when blank.
 */
package org.ashoreprospect.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code DeviceConfig.FILE} is a static final resolved from {@code user.dir}. */
@ResourceLock("device-id")
class DeviceConfigTest {

    private static final Path FILE = Path.of("scheduler-device-id.txt");

    private String saved;

    @BeforeEach
    void snapshot() throws Exception {
        saved = Files.isRegularFile(FILE) ? Files.readString(FILE, StandardCharsets.UTF_8) : null;
        Files.deleteIfExists(FILE);
    }

    @AfterEach
    void restore() throws Exception {
        Files.deleteIfExists(FILE);
        if (saved != null) Files.writeString(FILE, saved, StandardCharsets.UTF_8);
    }

    @Test
    void missingFileIsCreatedWithAParseableUuid() throws Exception {
        assertFalse(Files.exists(FILE));

        String id = DeviceConfig.getOrCreateDeviceId();

        assertEquals(id, UUID.fromString(id).toString());
        assertTrue(Files.isRegularFile(FILE));
        assertEquals(id, Files.readString(FILE, StandardCharsets.UTF_8));
    }

    @Test
    void secondCallReadsTheSameIdBack() {
        String first = DeviceConfig.getOrCreateDeviceId();
        String second = DeviceConfig.getOrCreateDeviceId();

        assertEquals(first, second);
    }

    @Test
    void storedValueIsTrimmed() throws Exception {
        Files.writeString(FILE, "  d-1234  \n", StandardCharsets.UTF_8);

        assertEquals("d-1234", DeviceConfig.getOrCreateDeviceId());
    }

    @Test
    void blankFileIsRegeneratedRatherThanReturnedBlank() throws Exception {
        Files.writeString(FILE, "   \n\t", StandardCharsets.UTF_8);

        String id = DeviceConfig.getOrCreateDeviceId();

        assertFalse(id.isBlank());
        assertNotEquals("", id.trim());
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, Files.readString(FILE, StandardCharsets.UTF_8));
    }
}
