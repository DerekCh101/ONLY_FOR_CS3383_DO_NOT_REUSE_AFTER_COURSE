import java.time.LocalDate;
import java.time.LocalTime;

public class Task {

    private String taskName;
    private LocalDate deadLineDate; // optional
    private LocalTime deadLineTime; // optional
    private int durationHours;      // optional, default 2
    private LocalDate startingDate; // optional
    private LocalTime startingTime; // optional
    private boolean fixedTime;

    Task(String tName, LocalDate dDate, LocalTime dTime,
         int durations, LocalDate sD, LocalTime sT) {

        this.taskName = tName;
        this.deadLineDate = dDate;
        this.deadLineTime = dTime;
        this.durationHours = durations;

        if (sD == null) {
            fixedTime = false;
        } else {
            fixedTime = true;
            this.startingDate = sD;
            this.startingTime = sT;
        }
    }



    public String getTaskName() {
        return taskName;
    }

    public LocalDate getDeadLineDate() {
        return deadLineDate;
    }

    public LocalTime getDeadLineTime() {
        return deadLineTime;
    }

    public int getDurationHours() {
        return durationHours;
    }

    public LocalDate getStartingDate() {
        return startingDate;
    }

    public LocalTime getStartingTime() {
        return startingTime;
    }

    public boolean isFixedTime() {
        return fixedTime;
    }

    @Override
    public String toString() {
        return String.format("{%s},{%s},{%s},{%s},{%s}",
                taskName, deadLineDate, deadLineTime,
                durationHours, fixedTime);
    }
}