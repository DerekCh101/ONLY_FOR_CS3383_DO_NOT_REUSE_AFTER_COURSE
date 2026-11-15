# Blocked-time sync defects

Five defects found while expanding the blocked-time test coverage in round 3.

All five were confirmed with automated probes rather than by reading, so the "observed" line in each
issue is what the code actually did when run. None has been fixed: the production code is untouched
and the probes deliberately pin the current behaviour.

## 1. Blocked period created while signed out is never sent to Node

Labels: `bug`, `data-loss`

`dbAddBlocked`/`dbClearBlocked` do call the Node push helpers, but when no token is present
`nodeReady()` returns silently and nothing records that the push was skipped. There is no outbox,
so the block created while signed out is never retried.

Verified with `BlockedSyncProbeTest`: probe A sees `POST /blocked` when signed in (the push works);
probe B sees only `GET /tasks`/`GET /blocked` after signing in, so a signed-out write is never sent.

Fix: mark skipped pushes dirty and flush them at the start of the next pull.

`TaskRepository` L296-319 (`dbAddBlocked`), L321-333 (`dbClearBlocked`), L482-484 (`nodeReady`).

## 2. An older row from Node is re-merged on every pull, and gets the block swept

Labels: `bug`, `data-loss`

`mergeBlocked` (TaskRepository L1139-1142) returns the local object unchanged when its stamp is
newer, so the stored `last_updated_at` never reaches the remote one. `sameBlockedStamp` (L629)
never matches, every pull reports `changed = true`, and `last_node_sync_at` keeps advancing.

Because the local stamp stays behind that advancing stamp, the delete sweep (L647) then treats the
row as eligible on every pull. One response from Node that omits it deletes the block locally and
tombstones it.

Probe C: identical payload merged twice -> `true` both times, stamp unmoved.
Probe D: Node omits the row once -> block deleted and tombstoned.

Fix: make `mergeBlocked` stamp-aware like `mergeTasks`, setting the result's stamp to
`max(local, remote)` so the next merge short-circuits.

## 3. A blocked-time tombstone is never cleared, so a swept block cannot return

Labels: `bug`, `data-loss`

When Node re-sends a block that was locally deleted, `mergeNodeBlocked` restores it and removes the
id from the in-memory set (TaskRepository L626) but never deletes the tombstone row in
`sync_deletes_blocked`. The task branch does this correctly via `sqlite.unrecordDelete` (L572).

There is no `unrecordBlockedDelete` in `SqliteTaskRepository` at all. The tombstone therefore
survives forever, `dbAddBlocked` skips the id on every later attempt ("id tombstoned, skip"), and
the delete keeps being re-sent for a block that exists.

Fix: add `unrecordBlockedDelete`, call it next to L626.

## 4. A non-ISO-8601 timestamp from Node silently keeps the stale local row

Labels: `bug`

`parseInstant` returns null for any stamp that is not ISO-8601. `mergeTasks` then compares that
null against the local stamp, which is always set (`bind` defaults it to `Instant.now()`), so the
remote edit loses - silently, with no log - while the merge still reports `changed = true`.

`TaskRepository` L834 (`parseInstant`), L1098 / L1113-1114 (`mergeTasks`).

The same null-on-failure applies to `parseMetaInstant`: a corrupt `last_node_sync_at` makes the
delete sweep a silent no-op instead of failing loudly.

This is unverified against the real server - if Node always sends ISO-8601, it is defensive only.

Fix: treat an unparseable remote stamp as "remote wins" and log the offending id.

## 5. A blocked period written while signed out is orphaned by the user-id filter

Labels: `bug`, `data-loss`

A block written with no session is stored with `user_id = NULL`, but both blocked-time reads filter
with `user_id = ?` and no `OR user_id IS NULL` clause. The row is in the table and unreachable by
anyone afterwards.

Probe B2: `rowExists=true user_id=null visibleWhileSignedOut=false visibleWhileSignedIn=false`.

The task reads get this right - `fetchAll` (SqliteTaskRepository L408) and `findById` (L423) both
use `user_id = ? OR user_id IS NULL`. Only `fetchAllBlocked` (L506-519) and `findBlockedById`
(L521-535) are missing it.

Fix: add the `OR user_id IS NULL` branch to both, matching the task queries.

## How the defects relate

- #1 is why the stranded row exists; #5 is why the user cannot even see it.
- #2 deletes a block Node still has, and #3 makes that deletion permanent. Together they are one
  data-loss path.
- #4 is latent: it only bites if Node ever emits a non-ISO stamp.

## Status

| # | Status | Change |
|---|---|---|
| 1 | **open** | Needs an outbox, which is a product decision (allow local-only use or not) |
| 2 | fixed | Served-row record (`sync_seen_blocked`); a row is only swept after two pulls omit it |
| 3 | fixed | `unrecordBlockedDelete()` called when Node re-sends a tombstoned block |
| 4 | fixed | An unusable remote stamp is treated as "now" and logged, not as "older than local" |
| 5 | fixed | `claimBlockedRowsWithNoOwner()` runs at the start of every pull |

Two notes on the #2 fix:

- The merged copy keeps the **local** stamp. Carrying Node's stamp forward looked simpler, but
  `upsertBlocked()` refuses any write older than the stored row, so that copy would never be saved.
  Convergence is decided by the served-row record instead.
- `upsertBlocked()`'s guard now also admits a write whose content is unchanged, so re-storing a row
  the local side owns is a no-op rather than a silent discard.

## Reproducing

Probes: `src/test/java/org/ashoreprospect/db/BlockedSyncProbeTest.java` (5 tests, all green).
Most run without any server; the two that need Node use a loopback HTTP server on 127.0.0.1.

Regression tests pinning the same behaviour: `TaskRepositoryNodeMergeTest`,
`SqliteTaskRepositorySignedInTest`, `TaskRepositoryWireFormatTest`.

```
.\gradlew.bat check
```

656 tests, 0 failures. The probe assertions pin buggy behaviour on purpose, so they pass; a fix must
update the probe assertions along with the production code.
