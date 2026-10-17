/** Rejects invalid add-task form values before a Command is built. */
package org.ashoreprospect;

import java.time.LocalDate;
import java.time.LocalTime;

public class ValidateAddTaskInput {

    public static boolean validateAddTaskInput(
            String tName,
            double duration,
            int state,
            LocalDate startDate,
            LocalTime startTime,
            LocalDate deadlineDate,
            LocalTime deadlineTime
    ) {

        // Task name
        if (tName == null || tName.trim().isEmpty()) {
            return false;
        }
        if (tName.trim().length() > 100) {
            return false;
        }

        // Duration
        if (duration <= 0) {
            return false;
        }

        if (AppTimeReference.hoursToSlots(duration) > AppTimeReference.SLOTS_PER_DAY) {
            return false;
        }

        // State must be valid
        if (state < 1 || state > 3) {
            return false;
        }

        java.time.LocalDateTime now = AppTimeReference.getCurrentDateTime();

        switch (state) {

            case 1: // Fixed → must have start, must NOT have deadline
                if (startDate == null || startTime == null) {
                    return false;
                }
                if (deadlineDate != null || deadlineTime != null) {
                    return false;
                }

                if (!java.time.LocalDateTime.of(startDate, startTime).isAfter(now)) {
                    return false;
                }
                break;

            case 2: // Deadline → must have deadline, must NOT have start
                if (deadlineDate == null || deadlineTime == null) {
                    return false;
                }
                if (startDate != null || startTime != null) {
                    return false;
                }

                if (!java.time.LocalDateTime.of(deadlineDate, deadlineTime).isAfter(now)) {
                    return false;
                }
                break;

            case 3: // Flexible → must have neither
                if (startDate != null || startTime != null) {
                    return false;
                }
                if (deadlineDate != null || deadlineTime != null) {
                    return false;
                }
                break;
        }

        return true;
    }
}