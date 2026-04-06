import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Scanner;

public class BlockedTimePeriod {
    public LocalTime[] timePeriod;
    public ArrayList<LocalTime[]> periods;
    public DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");
    private final int START_HOUR = 6;
    private final int END_HOUR = 23;
    private final Scanner scanner = new Scanner(System.in);


    BlockedTimePeriod(){
        timePeriod = new LocalTime[2];
        periods = new ArrayList<>();
        String time1 = "06-00 07-00"; 
        String time2 = "12-00 13-00";
        String time3 = "22-00 23-00";
        String[] parts1 = time1.split(" ");
        String[] parts2 = time2.split(" ");
        String[] parts3 = time3.split(" ");
        LocalTime timeParts1 = LocalTime.parse(parts1[0],timeFormatter);
        LocalTime timeParts2 = LocalTime.parse(parts1[1],timeFormatter);
        LocalTime timeParts3 = LocalTime.parse(parts2[0],timeFormatter);
        LocalTime timeParts4 = LocalTime.parse(parts2[1],timeFormatter);
        LocalTime timeParts5 = LocalTime.parse(parts3[0],timeFormatter);
        LocalTime timeParts6 = LocalTime.parse(parts3[1],timeFormatter);
        timePeriod[0] = timeParts1;
        timePeriod[1] = timeParts2;
        periods.add(timePeriod);
        timePeriod[0] = timeParts3;
        timePeriod[1] = timeParts4;
        periods.add(timePeriod);
        timePeriod[0] = timeParts5;
        timePeriod[1] = timeParts6;
        periods.add(timePeriod);
        for(LocalTime[] time:periods){
            System.out.println(time);
        }
    }

    private boolean validatePeriod(LocalTime start, LocalTime end) { //check can this period be added

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

        // Check overlap
        for (LocalTime[] period : periods) {
            LocalTime existingStart = period[0];
            LocalTime existingEnd = period[1];

            if (start.isBefore(existingEnd) && end.isAfter(existingStart)) {
                System.out.println("Time overlaps with existing blocked period.");
                return false;
            }
        }

        return true;
    }

    public void changeBlockedTime() {
        if (periods.isEmpty()) {
            System.out.println("No blocked periods available.");
            return;
        }


        printPeriods();

        System.out.println("Enter the number of the period to change:");
        try {
            int choice = Integer.parseInt(scanner.nextLine());

            if (choice < 1 || choice > periods.size()) {
                System.out.println("Invalid selection.");
                return;
            }

            System.out.println("Enter new start time (HH-mm): ");
            String startStr = scanner.nextLine();

            System.out.println("Enter new end time (HH-mm): ");
            String endStr = scanner.nextLine();

            LocalTime newStart = LocalTime.parse(startStr, timeFormatter);
            LocalTime newEnd = LocalTime.parse(endStr, timeFormatter);

            // Temporarily remove old period for validation
            LocalTime[] old = periods.remove(choice - 1);

            if (validatePeriod(newStart, newEnd)) {
                LocalTime[] newPeriod = new LocalTime[2];
                newPeriod[0] = newStart;
                newPeriod[1] = newEnd;
                periods.add(choice - 1, newPeriod);
                System.out.println("Blocked period updated.");
            } else {
                // Restore old if validation fails
                periods.add(choice - 1, old);
            }

        } catch (NumberFormatException | DateTimeParseException e) {
            System.out.println("Invalid input.");
        }
    }

    public void deleteBlockedTime() {
        if (periods.isEmpty()) {
            System.out.println("No blocked periods available.");
            return;
        }


        printPeriods();

        System.out.println("Enter the number of the period to delete:");
        try {
            int choice = Integer.parseInt(scanner.nextLine());

            if (choice < 1 || choice > periods.size()) {
                System.out.println("Invalid selection.");
                return;
            }

            periods.remove(choice - 1);
            System.out.println("Blocked period deleted.");

        } catch (NumberFormatException e) {
            System.out.println("Invalid input.");
        }
    }

    public void addBlockedTime(){
        System.out.println("Current blocked time.");
        for(LocalTime[] time:periods){
            System.out.println(time[0] + "-" + time[1]);
        }
        System.out.println("Time period you wanted to add?");
        System.out.println("Enter Start time(HH-mm).");
        String startStr = scanner.nextLine();
        System.out.println("Enter End time(HH-mm).");
        String endStr = scanner.nextLine();
        try {
            LocalTime start = LocalTime.parse(startStr, timeFormatter);
            LocalTime end = LocalTime.parse(endStr, timeFormatter);

            if (validatePeriod(start, end)) {
                LocalTime[] newPeriod = new LocalTime[2];
                newPeriod[0] = start;
                newPeriod[1] = end;
                periods.add(newPeriod);
                System.out.println("Blocked period added.");
            }
        } 
        catch (DateTimeParseException e) {
            System.out.println("Invalid time format. Use HH-mm.");
        }
    }
    
    private void printPeriods() {
        for (int i = 0; i < periods.size(); i++) {
            LocalTime[] time = periods.get(i);
            System.out.println((i + 1) + ") " + time[0] + " - " + time[1]);
        }
    }

    //check single hour
    public boolean isBlocked(LocalTime time) {
        for (LocalTime[] period : periods) {
            if (!time.isBefore(period[0]) && time.isBefore(period[1])) {
                return true;
            }
        }
        return false;
    }

    // Check multi-hour task
    public boolean violatesBlocked(LocalTime start, int duration) {
        for (int i = 0; i < duration; i++) {
            LocalTime checkTime = start.plusHours(i);
            if (isBlocked(checkTime)) {
                return true;
            }
        }
        return false;
    }


    
}
