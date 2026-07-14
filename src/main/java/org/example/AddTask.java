package org.example;
import java.util.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.LocalTime;

public class AddTask extends RecordedCommand {

    private Task thisTask;
    private TaskManagementSystem theSystem;
    private Scanner scanner;

    AddTask(TaskManagementSystem theSystem){

        this.theSystem = theSystem;
        scanner = new Scanner(System.in);

        System.out.println("Enter the task name:");
        String taskName = scanner.nextLine();
        while(taskName.isBlank()){
            System.out.println("Task name cannot be blank. Enter again:");
            taskName = scanner.nextLine();
        }

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");

        LocalDate ddate = null;
        LocalTime dtime = null;
        LocalDate startingDate = null;
        LocalTime startingTime = null;

        // ===== DEADLINE INPUT =====
        System.out.println("Enter deadline date (dd-MM-yyyy). Press Enter if none:");
        String deadLineDateString = scanner.nextLine();

        if(!deadLineDateString.isBlank()){
            ddate = LocalDate.parse(deadLineDateString, dateFormatter);

            System.out.println("Enter deadline time (HH-mm). Press Enter if none:");
            String deadLineTimeString = scanner.nextLine();
            if(!deadLineTimeString.isBlank()){
                dtime = LocalTime.parse(deadLineTimeString, timeFormatter);
            }
        }
        else {
            // ===== ONLY ASK FOR FIXED START IF NO DEADLINE =====
            System.out.println("Enter fixed starting date (dd-MM-yyyy). Press Enter if none:");
            String startingDateString = scanner.nextLine();

            if(!startingDateString.isBlank()){
                startingDate = LocalDate.parse(startingDateString, dateFormatter);

                System.out.println("Enter fixed starting time (HH-mm):");
                String startingTimeString = scanner.nextLine();

                if(startingTimeString.isBlank()){
                    System.out.println("Starting time is required if starting date is given.");
                    return;
                }

                startingTime = LocalTime.parse(startingTimeString, timeFormatter);
            }
        }

        // ===== DURATION =====
        System.out.println("Expected duration in hours (default 2):");
        String durationString = scanner.nextLine();
        int duration = 2;
        if(!durationString.isBlank()){
            duration = Integer.parseInt(durationString);
        }

        thisTask = new Task(taskName, ddate, dtime, duration, startingDate, startingTime);

        System.out.println("Task created successfully.");
    }

    @Override
    public void execute() {
        if(thisTask != null){
            theSystem.getTasks().add(thisTask);
            RecordedCommand.getRedoList().clear();
            RecordedCommand.getUndoList().add(this);
        }
    }

    @Override
    public void undo(){
        if(thisTask != null){
            theSystem.getTasks().remove(thisTask);
            RecordedCommand.getRedoList().add(this);
            RecordedCommand.getUndoList().remove(this);
        }
    }

    @Override
    public void redo(){
        if(thisTask != null){
            theSystem.getTasks().add(thisTask);
            RecordedCommand.getRedoList().removeLast();
            RecordedCommand.getUndoList().add(this);
        }
    }
}