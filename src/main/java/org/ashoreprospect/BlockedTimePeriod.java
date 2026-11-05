/** Holds every blocked interval and answers conflict checks against a proposed slot. */
package org.ashoreprospect;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class BlockedTimePeriod {

    public List<BlockedTime> blockedPeriod = new ArrayList<>();
    public ArrayList<LocalTime[]> periods = new ArrayList<>();
    public LocalTime[] timePeriod;
    public DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");

    private final int START_HOUR = 6;
    private final int END_HOUR = 23;
    private final Scanner scanner = new Scanner(System.in);

    public BlockedTimePeriod() {
        rebuildPeriods();
    }

    public void rebuildPeriods() {
        periods.clear();
        for (BlockedTime b : blockedPeriod) {
            if (b.isDaily() && !b.isException() && b.getStartTime() != null && b.getEndTime() != null) {
                periods.add(b.asPeriod());
            }
        }
    }

    public void replaceBlockedPeriods(List<BlockedTime> loaded) {
        blockedPeriod.clear();
        if (loaded != null) blockedPeriod.addAll(loaded);
        rebuildPeriods();
    }

    private boolean validatePeriod(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            System.out.println("Time cannot be null.");
            return false;
        }
        if (!start.isBefore(end)) {
            System.out.println("Start time must be before end time.");
            return false;
        }
        if (start.getHour() < START_HOUR || end.getHour() > END_HOUR) {
            System.out.println("Time must be between 06:00 and 23:00.");
            return false;
        }
        for (LocalTime[] period : periods) {
            if (start.isBefore(period[1]) && end.isAfter(period[0])) {
                System.out.println("Time overlaps with existing blocked period.");
                return false;
            }
        }
        return true;
    }

    public void changeBlockedTime() {
        if (blockedPeriod.isEmpty()) {
            System.out.println("No blocked periods available.");
            return;
        }
        printPeriods();
        System.out.println("Enter the number of the period to change:");
        try {
            int choice = Integer.parseInt(scanner.nextLine());
            if (choice < 1 || choice > blockedPeriod.size()) {
                System.out.println("Invalid selection.");
                return;
            }
            System.out.println("Enter new start time (HH-mm): ");
            String startStr = scanner.nextLine();
            System.out.println("Enter new end time (HH-mm): ");
            String endStr = scanner.nextLine();

            LocalTime newStart = LocalTime.parse(startStr, timeFormatter);
            LocalTime newEnd = LocalTime.parse(endStr, timeFormatter);

            BlockedTime old = blockedPeriod.get(choice - 1);
            LocalTime oldStart = old.getStartTime();
            LocalTime oldEnd = old.getEndTime();
            old.setStartTime(newStart);
            old.setEndTime(newEnd);
            rebuildPeriods();

            if (validatePeriod(newStart, newEnd)) {
                old.touch();
                System.out.println("Blocked period updated in memory (not saved to DB yet).");
            } else {
                old.setStartTime(oldStart);
                old.setEndTime(oldEnd);
                rebuildPeriods();
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            System.out.println("Invalid input.");
        }
    }

    public void deleteBlockedTime() {
        if (blockedPeriod.isEmpty()) {
            System.out.println("No blocked periods available.");
            return;
        }
        printPeriods();
        System.out.println("Enter the number of the period to delete:");
        try {
            int choice = Integer.parseInt(scanner.nextLine());
            if (choice < 1 || choice > blockedPeriod.size()) {
                System.out.println("Invalid selection.");
                return;
            }
            blockedPeriod.remove(choice - 1);
            rebuildPeriods();
            System.out.println("Blocked period deleted in memory (not saved to DB yet).");
        } catch (NumberFormatException e) {
            System.out.println("Invalid input.");
        }
    }

    public void addBlockedTime() {
        printPeriods();
        System.out.println("Enter Start time(HH-mm).");
        String startStr = scanner.nextLine();
        System.out.println("Enter End time(HH-mm).");
        String endStr = scanner.nextLine();
        try {
            LocalTime start = LocalTime.parse(startStr, timeFormatter);
            LocalTime end = LocalTime.parse(endStr, timeFormatter);
            if (validatePeriod(start, end)) {
                blockedPeriod.add(BlockedTime.daily("custom", start, end));
                rebuildPeriods();
                System.out.println("Blocked period added in memory (not saved to DB yet).");
            }
        } catch (DateTimeParseException e) {
            System.out.println("Invalid time format. Use HH-mm.");
        }
    }

    public boolean isBlocked(LocalDate date, LocalTime time) {
        if (date == null || time == null) return false;

        boolean dailyHit = false;
        boolean specificHit = false;
        boolean exceptionHit = false;

        for (BlockedTime b : blockedPeriod) {
            if (b.getStartTime() == null || b.getEndTime() == null) continue;
            if (time.isBefore(b.getStartTime()) || !time.isBefore(b.getEndTime())) continue;

            if (b.freesOn(date)) {
                exceptionHit = true;
            } else if (b.isException()) {
                continue;
            } else if (b.isDaily()) {
                dailyHit = true;
            } else if (b.appliesOn(date)) {
                specificHit = true;
            }
        }

        if (exceptionHit) return false;
        if (specificHit) return true;
        return dailyHit;
    }

    public boolean violatesBlocked(LocalDate date, LocalTime start, int durationHours) {
        for (int i = 0; i < durationHours; i++) {
            if (isBlocked(date, start.plusHours(i))) return true;
        }
        return false;
    }

    private void printPeriods() {
        for (int i = 0; i < blockedPeriod.size(); i++) {
            BlockedTime b = blockedPeriod.get(i);
            System.out.println((i + 1) + ") " + b.getStartTime() + " - " + b.getEndTime());
        }
    }

    public boolean isBlocked(LocalTime time) {
        for (LocalTime[] period : periods) {
            if (!time.isBefore(period[0]) && time.isBefore(period[1])) {
                return true;
            }
        }
        return false;
    }

    public boolean violatesBlocked(LocalTime start, int duration) {
        for (int i = 0; i < duration; i++) {
            if (isBlocked(start.plusHours(i))) return true;
        }
        return false;
    }

    public int returnTotalBlockedTime() {
        int totalHours = 0;
        for (LocalTime[] period : periods) {
            totalHours += (int) java.time.Duration.between(period[0], period[1]).toHours();
        }
        return totalHours;
    }
    public String conflictMessage(BlockedTime candidate) {
        if (candidate == null || candidate.getStartTime() == null || candidate.getEndTime() == null) {
            return "Invalid time.";
        }
        if (!candidate.getStartTime().isBefore(candidate.getEndTime())) {
            return "End must be after start.";
        }
        for (BlockedTime b : blockedPeriod) {
            if (b == null || (b.getId() != null && b.getId().equals(candidate.getId()))) continue;
            if (blockedOverlap(b, candidate)) {
                return "Overlaps existing blocked time: " + describe(b);
            }
        }
        return null;
    }

    public String conflictAmong(java.util.List<BlockedTime> incoming) {
        for (int i = 0; i < incoming.size(); i++) {
            String vsExisting = conflictMessage(incoming.get(i));
            if (vsExisting != null) return vsExisting;
            for (int j = 0; j < i; j++) {
                if (blockedOverlap(incoming.get(j), incoming.get(i))) {
                    return "Overlaps in this add: " + describe(incoming.get(j));
                }
            }
        }
        return null;
    }

    /**
     * Two rows clash when they are the same kind (block vs block, exception vs exception),
     * their clocks overlap, and there is at least one calendar day both cover.
     * Every-day (no date, no weekday) covers every day.
     */
    static boolean blockedOverlap(BlockedTime a, BlockedTime b) {
        if (a == null || b == null) return false;
        if (a.isException() != b.isException()) return false;
        if (!clockOverlap(a, b)) return false;
        return daysCanMeet(a, b);
    }

    /** True when there is at least one calendar day both rows cover. */
    static boolean daysCanMeet(BlockedTime a, BlockedTime b) {
        boolean openA = a.getSpecificDate() == null && a.getWeekday() == null;
        boolean openB = b.getSpecificDate() == null && b.getWeekday() == null;
        if (openA && openB) return true;
        if (openA) return true;
        if (openB) return true;
        java.time.LocalDate da = a.getSpecificDate();
        java.time.LocalDate db = b.getSpecificDate();
        java.time.DayOfWeek wa = a.getWeekday();
        java.time.DayOfWeek wb = b.getWeekday();
        if (da != null && db != null) return da.equals(db);
        if (da != null) return wb != null && da.getDayOfWeek() == wb;
        if (db != null) return wa != null && db.getDayOfWeek() == wa;
        return wa != null && wb != null && wa == wb;
    }

    static boolean clockOverlap(BlockedTime a, BlockedTime b) {
        if (a.getStartTime() == null || a.getEndTime() == null
                || b.getStartTime() == null || b.getEndTime() == null) {
            return false;
        }
        int a0 = a.getStartTime().toSecondOfDay();
        int a1 = a.getEndTime().toSecondOfDay();
        int b0 = b.getStartTime().toSecondOfDay();
        int b1 = b.getEndTime().toSecondOfDay();
        if (a0 >= a1 || b0 >= b1) return false;
        return a0 < b1 && b0 < a1;
    }

    static String describe(BlockedTime b) {
        String when;
        if (b.isException()) {
            when = "exception " + (b.getWeekday() != null ? b.getWeekday() : b.getSpecificDate());
        } else if (b.isDaily()) {
            when = "every day";
        } else if (b.getWeekday() != null) {
            when = String.valueOf(b.getWeekday());
        } else {
            when = String.valueOf(b.getSpecificDate());
        }
        return (b.getTitle() == null ? "blocked" : b.getTitle())
                + " " + when + " " + b.getStartTime() + "–" + b.getEndTime();
    }
}