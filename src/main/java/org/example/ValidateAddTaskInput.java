package org.example;

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

        TaskManagementSystem theSystem = TaskManagementSystem.getInstance();

        // Task name
        if (tName == null || tName.trim().isEmpty()) {
            return false;
        }

        // Duration
        if (duration <= 0) {
            return false;
        }

        if (duration >= theSystem.getEngine().getTotalHours()) {
            return false;
        }

        // State must be valid
        if (state < 1 || state > 3) {
            return false;
        }

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        switch (state) {

            case 1: // Fixed → must have start, must NOT have deadline
                if (startDate == null || startTime == null) {
                    return false;
                }
                if (deadlineDate != null || deadlineTime != null) {
                    return false;
                }

                // Cannot be in the past
                if (startDate.isBefore(today)) {
                    return false;
                }
                if (startDate.equals(today) && startTime.isBefore(now)) {
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

                // Cannot be in the past
                if (deadlineDate.isBefore(today)) {
                    return false;
                }
                if (deadlineDate.equals(today) && deadlineTime.isBefore(now)) {
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