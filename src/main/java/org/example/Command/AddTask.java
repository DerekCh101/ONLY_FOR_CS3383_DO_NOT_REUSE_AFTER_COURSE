package org.example.Command;
import org.example.Task;
import org.example.TaskManagementSystem;
import org.example.ValidateAddTaskInput;

import java.util.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.LocalTime;

public class AddTask extends RecordedCommand {

    private Task thisTask;
    private TaskManagementSystem theSystem;
    private Scanner scanner;
    private LocalDate clickedDate;
    private LocalTime clickedTime;


    public AddTask(String taskName,int duration,
                   int state,
                   LocalDate clickedDate, LocalTime clickedTime,
                   LocalDate secDate, LocalTime secTime){

        this.clickedDate = clickedDate;
        this.clickedTime = clickedTime;
        theSystem = TaskManagementSystem.getInstance();

        if(duration<=0){
            duration = 2;
        }
        try{
            ValidateAddTaskInput.validateAddTaskInput(taskName,duration,
                    state,clickedDate,clickedTime,secDate,secTime);

            thisTask = new Task(taskName,duration,state,clickedDate,clickedTime,secDate,secTime);

        }catch(Exception ignored){
            System.out.printf("\nSome thing went wrong, %s is not created.\n",taskName);
        }

    }

    public AddTask(TaskManagementSystem theSystem){//for debug only

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

        System.out.println("Enter the type of task, 1: fixed, 2: deadline 3: flexible.");
        int state = scanner.nextInt();

        if(state==1){
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
        }else if(state == 2){
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
        }

        // ===== DURATION =====
        System.out.println("Expected duration in hours (default 2):");
        String durationString = scanner.nextLine();
        int duration = 2;
        if(!durationString.isBlank()){
            duration = Integer.parseInt(durationString);
        }
        try{
            ValidateAddTaskInput.validateAddTaskInput(taskName, duration,state,
                    startingDate, startingTime, ddate, dtime);
            thisTask = new Task(taskName, duration,state, startingDate, startingTime, ddate, dtime);
        }catch(Exception ignored){
            System.out.printf("\nSome thing went wrong, %s is not created.\n",taskName);
        }

        System.out.println("Task created successfully.");

    }

    @Override
    public boolean execute() {
        if(thisTask != null){
            theSystem.getPendingTasks().add(thisTask);
            RecordedCommand.getRedoList().clear();
            RecordedCommand.getUndoList().add(this);
            return true;
        }
        return false;
    }

    @Override
    public void undo(){
        if(thisTask != null){
            theSystem.getPendingTasks().remove(thisTask);
            RecordedCommand.getRedoList().add(this);
            RecordedCommand.getUndoList().remove(this);
        }
    }

    @Override
    public void redo(){
        if(thisTask != null){
            theSystem.getPendingTasks().add(thisTask);
            RecordedCommand.getRedoList().removeLast();
            RecordedCommand.getUndoList().add(this);
        }
    }
}