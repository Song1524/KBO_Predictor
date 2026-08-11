package com.playball.kbopredictor.stats.collection;

import java.time.LocalDate;

public interface OfficialStartingPitcherSource {

    String fetchGameList(LocalDate date);

    String fetchPlayerDetail(String kboPlayerId);
}
