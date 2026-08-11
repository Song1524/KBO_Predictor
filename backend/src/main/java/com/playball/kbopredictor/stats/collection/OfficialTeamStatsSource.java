package com.playball.kbopredictor.stats.collection;

public interface OfficialTeamStatsSource {

    String fetchStandings();

    String fetchTeamBatting();

    String fetchTeamPitching();
}
