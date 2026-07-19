package org.example;

import java.time.LocalDate;
import java.time.LocalTime;

// Fixed task has clickedDate, clickedTime, null, null
// deadline task has clickedDate, clickedTime, secDate, secTime
// flexible task has clickedDate, clickedTime, null, null
public class ValidateAddTaskInput {

    //this didn't check time conflict between tasks
    public static boolean validateAddTaskInput(
            String tName,
            int duration,
            int state,
            LocalDate clickedDate,
            LocalTime clickedTime,
            LocalDate secDate,
            LocalTime secTime
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

        // Basic date/time required
        if (clickedDate == null || clickedTime == null) {
            return false;
        }

        // Cannot be in the past
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        if (clickedDate.isBefore(today)) {
            return false;
        }

        if (clickedDate.equals(today) && clickedTime.isBefore(now)) {
            return false;
        }

        // State validation
        if (state < 1 || state > 3) {
            return false;
        }

        switch (state) {

            case 1: // Fixed
                if (secDate != null || secTime != null) {
                    return false;
                }
                break;

            case 2: // Deadline
                if (secDate == null || secTime == null) {
                    return false;
                }

                if (secDate.isBefore(clickedDate)) {
                    return false;
                }

                if (secDate.equals(clickedDate) &&
                        secTime.isBefore(clickedTime)) {
                    return false;
                }
                break;

            case 3: // Flexible
                if (secDate != null || secTime != null) {
                    return false;
                }
                break;
        }

        return true;
    }
}