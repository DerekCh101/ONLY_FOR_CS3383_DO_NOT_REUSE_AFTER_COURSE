/**
 * Keeps the working directory clean of the runtime files that {@code TypewriterStore}'s static
 * initialiser drops there ({@code scheduler-cache.db} and {@code typewriter-images/}).
 *
 * <p>The store opens these paths relative to the JVM start-up directory, and {@code java.io.File}
 * has already cached that directory, so {@code System.setProperty("user.dir", ...)} cannot redirect
 * them. Other test classes in this suite touch the same paths too, so a simple "delete what I
 * created" snapshot is not enough: whichever class runs first creates the file and the rest see it
 * as pre-existing.
 *
 * <p>The approach here is therefore <b>backup and restore</b>:
 * <ul>
 *   <li>{@link #snapshot()} copies any file that already exists into a temp folder.</li>
 *   <li>{@link #cleanUp()} puts the originals back byte-for-byte, or deletes whatever the test
 *       run left behind when there was nothing there to begin with.</li>
 * </ul>
 * A developer's real cache is never destroyed, and the directory looks exactly as it did before
 * the run.
 *
 * <p>Usage: {@link #snapshot()} from {@code @BeforeAll}, {@link #cleanUp()} from {@code @AfterAll}.
 */
package org.ashoreprospect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class TestRuntimeArtifacts {

    /** Paths the Typewriter static initialiser may create in the working directory. */
    private static final String[] CANDIDATES = {
            "scheduler-cache.db",
            "scheduler-cache.db-wal",
            "scheduler-cache.db-shm",
            "typewriter-images"
    };

    /** Relative name -> backup location, for paths that existed before the test class ran. */
    private static final List<String[]> SAVED = new ArrayList<>();
    private static Path backupRoot;
    private static boolean snapshotted;

    private TestRuntimeArtifacts() {}

    /** Copy any existing runtime files into a private temp folder. */
    public static synchronized void snapshot() {
        SAVED.clear();
        if (backupRoot != null) deleteRecursively(backupRoot.toFile());
        try {
            backupRoot = Files.createTempDirectory("tw-runtime-backup");
        } catch (IOException e) {
            backupRoot = null;
            snapshotted = false;
            return;
        }
        for (String name : CANDIDATES) {
            File src = new File(name);
            if (!src.exists()) continue;
            Path dest = backupRoot.resolve(name);
            try {
                copyRecursively(src, dest);
                SAVED.add(new String[]{name, dest.toString()});
            } catch (IOException ignored) {
                // Leave this path un-backed-up; it will not be deleted on restore either.
            }
        }
        snapshotted = true;
    }

    /**
     * Remove what the run produced, then put back anything that was there before.
     * If {@link #snapshot()} was never called this does nothing.
     */
    public static synchronized void cleanUp() {
        if (!snapshotted) return;

        // 1. Clear everything the run may have created.
        for (String name : CANDIDATES) deleteRecursively(new File(name));

        // 2. Restore the originals.
        for (String[] entry : SAVED) {
            try {
                copyRecursively(new File(entry[1]), new File(entry[0]).toPath());
            } catch (IOException ignored) {
                // Best effort: never fail a test run over cleanup.
            }
        }

        SAVED.clear();
        deleteRecursively(backupRoot == null ? null : backupRoot.toFile());
        backupRoot = null;
        snapshotted = false;
    }

    private static void copyRecursively(File src, Path dest) throws IOException {
        if (src.isDirectory()) {
            Files.createDirectories(dest);
            File[] children = src.listFiles();
            if (children == null) return;
            for (File child : children) copyRecursively(child, dest.resolve(child.getName()));
        } else {
            Path parent = dest.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.copy(src.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        try (Stream<Path> walk = Files.walk(file.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort only.
                }
            });
        } catch (IOException ignored) {
            // Best effort only.
        }
    }
}
