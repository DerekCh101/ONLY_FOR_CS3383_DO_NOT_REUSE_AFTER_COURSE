package org.example.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Persistent device_id for this install (laptop vs desktop). */
public final class DeviceConfig {

    private static final Path FILE = Path.of("scheduler-device-id.txt");

    private DeviceConfig() {}

    public static String getOrCreateDeviceId() {
        try {
            if (Files.isRegularFile(FILE)) {
                String id = Files.readString(FILE, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) return id;
            }
            String id = UUID.randomUUID().toString();
            Files.writeString(FILE, id, StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            // last resort: ephemeral (won't ack GC well across restarts)
            return UUID.randomUUID().toString();
        }
    }
}