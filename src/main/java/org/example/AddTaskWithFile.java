package org.example;

import java.io.InputStream;
import java.util.*;
import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.time.LocalTime;

public class AddTaskWithFile implements Command {

    private ArrayList<Task> addedTaskList = new ArrayList<>();
    private TaskManagementSystem theSystem;

    AddTaskWithFile(TaskManagementSystem theSystem) throws FileNotFoundException {

        this.theSystem = theSystem;
        InputStream input = getClass().getClassLoader().getResourceAsStream("taskInput1.txt");
        Scanner file = new Scanner(input);

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");

        while(file.hasNextLine()){

            String taskName = file.nextLine().trim();
            if(taskName.isBlank()){
                continue;
            }

            LocalDate ddate = null;
            LocalTime dtime = null;
            LocalDate startingDate = null;
            LocalTime startingTime = null;
            int duration = 2;

            // Deadline Date
            if(file.hasNextLine()){
                String deadLineDateString = file.nextLine().trim();
                if(!deadLineDateString.isBlank()){
                    ddate = LocalDate.parse(deadLineDateString, dateFormatter);
                }
            }

            // Deadline Time
            if(file.hasNextLine()){
                String deadLineTimeString = file.nextLine().trim();
                if(!deadLineTimeString.isBlank()){
                    dtime = LocalTime.parse(deadLineTimeString, timeFormatter);
                }
            }

            // Duration
            if(file.hasNextLine()){
                String durationString = file.nextLine().trim();
                if(!durationString.isBlank()){
                    duration = Integer.parseInt(durationString);
                }
            }

            // Starting Date
            if(file.hasNextLine()){
                String startingDateString = file.nextLine().trim();
                if(!startingDateString.isBlank()){
                    startingDate = LocalDate.parse(startingDateString, dateFormatter);
                }
            }

            // Starting Time
            if(file.hasNextLine()){
                String startingTimeString = file.nextLine().trim();
                if(!startingTimeString.isBlank()){
                    startingTime = LocalTime.parse(startingTimeString, timeFormatter);
                }
            }

            // ===== MUTUAL EXCLUSIVITY CHECK =====
            if(ddate != null && startingDate != null){
                System.out.println("Error in file: Task '" + taskName +
                        "' has both deadline and fixed start. Skipping.");
                continue;
            }

            Task task = new Task(taskName, ddate, dtime, duration, startingDate, startingTime);
            addedTaskList.add(task);
        }

        file.close();
        System.out.println("File loading complete.");
    }

    @Override
    public void execute() {
        for(Task task : addedTaskList){
            theSystem.getTasks().add(task);
        }
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("Tasks added from file.");
    }

    @Override
    public void undo(){
        for(Task task : addedTaskList){
            theSystem.getTasks().remove(task);
        }
        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo(){
        for(Task task : addedTaskList){
            theSystem.getTasks().add(task);
        }
        RecordedCommand.getRedoList().removeLast();
        RecordedCommand.getUndoList().add(this);
    }
}