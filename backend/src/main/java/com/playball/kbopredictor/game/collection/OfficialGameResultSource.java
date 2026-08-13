package com.playball.kbopredictor.game.collection;

import java.time.LocalDate;

public interface OfficialGameResultSource {

    String fetchGameList(LocalDate date);
}
