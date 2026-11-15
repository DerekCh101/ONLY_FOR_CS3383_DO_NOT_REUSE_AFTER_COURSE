# Defect-register tests

This package contains tests that **pass by asserting current behaviour**, even where that
behaviour contradicts the specification in `ADVANCE_BEHAVIOUR.md` or `README.md` (both at the
repository root).

They exist so the defects stay visible and cannot regress silently into "looks fine", and so a
reader of the test suite can tell a *regression test* (asserts intended behaviour) from a
*defect pin* (asserts current, wrong behaviour).

| File | Defect | Pinned by |
| --- | --- | --- |
| `SchedulerEngineDeadlineOrderTest` | `updateSchedule()` throws `NullPointerException` when two state-2 tasks are compared and at least one has a null `deadLineDate` | `schedulerEngine_deadlineComparatorNpe` |
| `BlockedTimeEditorDefectTest` | `BlockedTimePeriod.changeBlockedTime()` rejects and rolls back **every** edit, because `validatePeriod()` compares the edited row against its own live `LocalTime[]` | `changeBlockedTime_rejectsEveryEdit`, `changeBlockedTime_rejectsAnEditThatWouldOverlapAnotherRow` |
| `BlockedTimeEditorDefectTest` | `BlockedTimePeriod.violatesBlocked()` keeps the original date when a task runs past midnight, so it never sees the next day's row | `violatesBlocked_crossingMidnightChecksTheNextCalendarDate` |
| `BlockedTimeEditorDefectTest` | `BlockedTimePeriod.returnTotalBlockedTime()` truncates with `Duration.toHours()`, so a 30-minute block counts as 0 hours | `returnTotalBlockedTime_truncatesHalfHoursToWholeHours` |
| `ValidateAddTaskInputDeadDurationTest` | The `hoursToSlots(duration) > SLOTS_PER_DAY` guard is dead code: `hoursToMinutes()` clamps to 1440 first, so >24 h durations are silently truncated instead of rejected | `durationAbove24HoursIsStillAccepted` |
| `ValidateAddTaskInputDeadDurationTest` | The README / §8 rule "a row with both a deadline and a fixed start is skipped" is unreachable: the parser only reads start fields for state 1 and deadline fields for state 2, so one import row cannot carry both | `fileImportParserCannotProduceBothStartAndDeadline` |

## Rules for this package

1. **Do not "fix" a defect by editing its test in place** unless the production code changes in
   the same commit. The test is the record of the bug.
2. Each test carries a `PINNED:` comment naming the production line responsible.
3. When the production code is fixed, delete the test here and add a normal regression test in
   the owning class's test file (`SchedulerEngineTest`, `BlockedTimePeriodTest`, …).

Every other `PINNED:` comment elsewhere in `src/test` documents a *reachability decision*
("this branch cannot be hit from the public API"), not a defect, and stays where it is
next to the test that relies on it.
