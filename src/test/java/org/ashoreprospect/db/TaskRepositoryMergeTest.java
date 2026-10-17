/** Tests last-write-wins merge of local vs remote tasks. */
package org.ashoreprospect.db;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
import org.ashoreprospect.Workflow;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRepositoryMergeTest {

    private static final Instant TIE = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant OLDER = Instant.parse("2026-10-11T00:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-10-13T00:00:00Z");

    @Test
    void equalTaskTimestamp_clientTakesRemote() {
        Task local = task("local", TIE, TIE);
        Task remote = task("remote", TIE, TIE);

        Task merged = TaskRepository.mergeTasks(local, remote, false);

        assertEquals("remote", merged.getTaskName());
        assertEquals(TIE, merged.getLastUpdatedAt());
    }

    @Test
    void equalTaskTimestamp_hostKeepsLocal() {
        Task local = task("local", TIE, TIE);
        Task remote = task("remote", TIE, TIE);

        Task merged = TaskRepository.mergeTasks(local, remote, true);

        assertEquals("local", merged.getTaskName());
        assertEquals(TIE, merged.getLastUpdatedAt());
    }

    @Test
    void remoteNewer_winsOnHost() {
        Task local = task("local", OLDER, OLDER);
        Task remote = task("remote", NEWER, NEWER);

        Task merged = TaskRepository.mergeTasks(local, remote, true);

        assertEquals("remote", merged.getTaskName());
        assertEquals(NEWER, merged.getLastUpdatedAt());
    }

    @Test
    void localNewer_winsOnClient() {
        Task local = task("local", NEWER, NEWER);
        Task remote = task("remote", OLDER, OLDER);

        Task merged = TaskRepository.mergeTasks(local, remote, false);

        assertEquals("local", merged.getTaskName());
        assertEquals(NEWER, merged.getLastUpdatedAt());
    }

    @Test
    void equalPlacementTimestamp_clientTakesRemoteSlot() {
        Task local = placed("local", LocalTime.of(9, 0), TIE);
        Task remote = placed("remote", LocalTime.of(10, 0), TIE);

        Task merged = TaskRepository.mergeTasks(local, remote, false);

        assertEquals(LocalTime.of(10, 0), merged.getPlacedTime());
        assertEquals(TIE, merged.getPlacementUpdatedAt());
    }

    @Test
    void equalPlacementTimestamp_hostKeepsLocalSlot() {
        Task local = placed("local", LocalTime.of(9, 0), TIE);
        Task remote = placed("remote", LocalTime.of(10, 0), TIE);

        Task merged = TaskRepository.mergeTasks(local, remote, true);

        assertEquals(LocalTime.of(9, 0), merged.getPlacedTime());
        assertEquals(TIE, merged.getPlacementUpdatedAt());
    }

    @Test
    void equalBlockedTimestamp_clientTakesRemote() {
        BlockedTime local = blocked("local", TIE);
        BlockedTime remote = blocked("remote", TIE);

        assertSame(remote, TaskRepository.mergeBlocked(local, remote, false));
    }

    @Test
    void equalBlockedTimestamp_hostKeepsLocal() {
        BlockedTime local = blocked("local", TIE);
        BlockedTime remote = blocked("remote", TIE);

        assertSame(local, TaskRepository.mergeBlocked(local, remote, true));
    }

    @Test
    void remoteNewerBlocked_winsOnHost() {
        BlockedTime local = blocked("local", OLDER);
        BlockedTime remote = blocked("remote", NEWER);

        assertSame(remote, TaskRepository.mergeBlocked(local, remote, true));
    }

    @Test
    void preferRemote_equalUsesHostFlag() {
        assertTrue(TaskRepository.preferRemote(TIE, TIE, false));
        assertFalse(TaskRepository.preferRemote(TIE, TIE, true));
        assertTrue(TaskRepository.preferRemote(OLDER, NEWER, true));
        assertFalse(TaskRepository.preferRemote(NEWER, OLDER, false));
    }

    @Test
    void bothNull_keepsLocalOnClientAndHost() {
        assertFalse(TaskRepository.preferRemote(null, null, false));
        assertFalse(TaskRepository.preferRemote(null, null, true));
        BlockedTime local = blocked("local", TIE);
        BlockedTime remote = blocked("remote", TIE);
        local.setLastUpdatedAt(null);
        remote.setLastUpdatedAt(null);
        assertSame(local, TaskRepository.mergeBlocked(local, remote, false));
        assertSame(local, TaskRepository.mergeBlocked(local, remote, true));
    }

    private static Task task(String name, Instant last, Instant place) {
        Task t = Task.loadTaskFromdb(
                "task-1", name, 1, 3,
                null, null, null, null, null, null
        );
        t.setWorkflow(Workflow.UNFINISHED);
        t.setLastUpdatedAt(last);
        t.setPlacementUpdatedAt(place);
        return t;
    }

    private static Task placed(String name, LocalTime slot, Instant placeAt) {
        Task t = Task.loadTaskFromdb(
                "task-1", name, 1, 3,
                null, null, null, null,
                LocalDate.of(2026, 10, 12), slot
        );
        t.setLastUpdatedAt(placeAt);
        t.setPlacementUpdatedAt(placeAt);
        return t;
    }

    private static BlockedTime blocked(String title, Instant last) {
        return new BlockedTime(
                "block-1", title, DayOfWeek.MONDAY, null,
                LocalTime.of(8, 0), LocalTime.of(9, 0), last, "user"
        );
    }
}
