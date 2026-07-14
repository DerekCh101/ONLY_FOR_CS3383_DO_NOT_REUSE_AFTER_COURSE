//Last being maintained 14/7/2026
//Meant to be early-debug only

//package org.example;
//
//import java.io.FileNotFoundException;
//import java.util.Scanner;
//
//public class CLIRunner {
//
//    TaskManagementSystem theSystem = TaskManagementSystem.getInstance();
//
//    public void command(String cmdString) {
//
//        boolean running = true;
//        Scanner input = new Scanner(System.in);
//
//        while (running) {
//
//            if (cmdString == null) {
//                System.out.print("Command> ");
//                cmdString = input.nextLine().trim();
//            }
//            if (cmdString.isEmpty()) {
//                cmdString = null;
//                continue;
//            }
//
//            switch (cmdString) {
//
//                case "addTask":
//                    Command addTask = new AddTask(theSystem);
//                    addTask.execute();
//                    cmdString = null;
//                    break;
//
//                case "addTaskFromFile":
//                    try {
//                        Command addTaskWithFile = new AddTaskWithFile(theSystem);
//                        addTaskWithFile.execute();
//                    } catch (FileNotFoundException e) {
//                        System.out.println("File Not Found!");
//                    }
//                    cmdString = null;
//                    break;
//
//                case "finishTask":
//                    Command finishTask = new FinishTask(theSystem);
//                    finishTask.execute();
//                    cmdString = null;
//                    break;
//
//                case "printAll":
//                    Command printAll = new PrintAllTask(theSystem);
//                    printAll.execute();
//                    cmdString = null;
//                    break;
//
//                case "undo":
//                    RecordedCommand undo = new Undo(theSystem);
//                    undo.execute();
//                    cmdString = null;
//                    break;
//
//                case "redo":
//                    RecordedCommand redo = new Redo(theSystem);
//                    redo.execute();
//                    cmdString = null;
//                    break;
//
//                case "update":
//                    theSystem.updateSchedule();
//                    System.out.println("GUI updated.");
//                    cmdString = null;
//                    break;
//
//                case "exit":
//                    running = false;
//                    input.close();
//                    System.exit(0);
//                    break;
//
//                default:
//                    System.out.println("Invalid Command.");
//                    cmdString = null;
//                    break;
//            }
//        }
//    }
//    public static void main(String[] arg){
//        TaskManagementSystem theSystem;
//        theSystem = TaskManagementSystem.getInstance();
//        String startingMessage = null;
//        theSystem.getCliMode().command(null);
//    }
//}
