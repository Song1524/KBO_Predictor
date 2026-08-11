package com.playball.kbopredictor.game.collection;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface GameDataCollector {

    GameCollectionBatch collect(LocalDate date);

    default Map<LocalDate, GameCollectionBatch> collectDates(
            List<LocalDate> dates
    ) {
        Map<LocalDate, GameCollectionBatch> batches = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            batches.put(date, collect(date));
        }
        return batches;
    }
}
