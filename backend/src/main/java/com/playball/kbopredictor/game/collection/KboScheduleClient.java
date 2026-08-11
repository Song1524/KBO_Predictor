package com.playball.kbopredictor.game.collection;

import java.time.YearMonth;

public interface KboScheduleClient {

    String fetchSchedule(YearMonth yearMonth);
}
