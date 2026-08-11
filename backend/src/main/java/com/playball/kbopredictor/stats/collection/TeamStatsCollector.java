package com.playball.kbopredictor.stats.collection;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TeamStatsCollector {

    private static final int KBO_TEAM_COUNT = 10;

    private final OfficialTeamStatsSource source;
    private final OfficialTeamStatsParser parser;

    public List<CollectedTeamStat> collect() {
        List<OfficialTeamStanding> standings = parser.parseStandings(
                source.fetchStandings()
        );
        Map<String, BigDecimal> batting = parser.parseBattingAverages(
                source.fetchTeamBatting()
        );
        Map<String, BigDecimal> eras = parser.parseEras(
                source.fetchTeamPitching()
        );

        if (standings.size() != KBO_TEAM_COUNT
                || batting.size() != KBO_TEAM_COUNT
                || eras.size() != KBO_TEAM_COUNT) {
            throw new PregameDataCollectionException(
                    "KBO 10개 구단 통계가 모두 수집되지 않았습니다. standings="
                            + standings.size()
                            + ", batting=" + batting.size()
                            + ", pitching=" + eras.size()
            );
        }

        return standings.stream()
                .map(standing -> new CollectedTeamStat(
                        standing,
                        required(batting, standing.teamCode(), "타율"),
                        required(eras, standing.teamCode(), "평균자책점")
                ))
                .toList();
    }

    private BigDecimal required(
            Map<String, BigDecimal> values,
            String teamCode,
            String fieldName
    ) {
        BigDecimal value = values.get(teamCode);
        if (value == null) {
            throw new PregameDataCollectionException(
                    teamCode + "의 " + fieldName + "이 없습니다."
            );
        }
        return value;
    }
}
