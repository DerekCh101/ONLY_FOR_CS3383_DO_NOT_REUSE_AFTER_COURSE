package org.example.db;

import java.sql.*;

public class TaskRepository {
    private Connection conn;
    private Statement st;
    String url = "jdbc:postgresql://localhost:5432/Scheduler";
    String user = "postgres";
    String password = System.getenv("postgres_pw");; // install password

    public TaskRepository(){

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_database(), version()")) {

            if (rs.next()) {
                System.out.println("Database: " + rs.getString(1));
                System.out.println("Version:  " + rs.getString(2));
                System.out.println("Connected OK");
                this.conn = conn;
                this.st =st;
            }
        } catch (Exception e)
        {
            e.printStackTrace();
        }

    }
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
    public void initialize() throws SQLException {
        conn = connect();
        st = conn.createStatement();
        st.execute()

    }
    public ResultSet addOperation(){
        st.execute("INSERT INTO ")
    }
}