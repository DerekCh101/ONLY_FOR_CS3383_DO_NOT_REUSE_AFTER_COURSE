import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class Day{
    
    private int avaliableHours;
    private Task[] todayTaskGrid;
    private static final int START_HOUR = 6;
    private static final int HOURS_PER_DAY = 17;

    public Day(){
        this.todayTaskGrid[] = new Task[HOURS_PER_DAY];
    }

    // Check slot availability with optional blocked rule
    public boolean canPlace(int day, int hour,
                             int duration, boolean ignoreBlocked) {

        if (hour < 0 || hour + duration > HOURS_PER_DAY)
            return false;

        for (int i = 0; i < duration; i++) {

            if (todayTaskGrid[hour + i] != null)
                return false;

            if (!ignoreBlocked) {
                LocalTime checkTime =
                        LocalTime.of(START_HOUR + hour + i, 0);

                if (blockedTime.isBlocked(checkTime))
                    return false;
            }
        }

        return true;
    }


    public placeTask(){



    }



}
