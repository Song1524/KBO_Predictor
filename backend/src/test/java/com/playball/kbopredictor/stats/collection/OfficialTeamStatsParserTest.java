package com.playball.kbopredictor.stats.collection;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialTeamStatsParserTest {

    private static final List<String> TEAMS = List.of(
            "KT", "삼성", "LG", "두산", "KIA",
            "한화", "NC", "롯데", "SSG", "키움"
    );

    private final OfficialTeamStatsParser parser =
            new OfficialTeamStatsParser();

    @Test
    void parsesAllTenTeamsAndWinDrawLossSplits() {
        List<OfficialTeamStanding> standings = parser.parseStandings(
                standingsFixture()
        );

        assertThat(standings).hasSize(10);
        OfficialTeamStanding lg = standings.stream()
                .filter(value -> value.teamCode().equals("LG"))
                .findFirst()
                .orElseThrow();
        assertThat(lg.wins()).isEqualTo(55);
        assertThat(lg.losses()).isEqualTo(45);
        assertThat(lg.draws()).isEqualTo(1);
        assertThat(lg.winRate()).isEqualByComparingTo("0.550");
        assertThat(lg.recent10Wins()).isEqualTo(3);
        assertThat(lg.recent10Draws()).isEqualTo(1);
        assertThat(lg.recent10Losses()).isEqualTo(6);
        assertThat(lg.homeWins()).isEqualTo(32);
        assertThat(lg.homeDraws()).isZero();
        assertThat(lg.homeLosses()).isEqualTo(20);
        assertThat(lg.awayWins()).isEqualTo(23);
        assertThat(lg.awayDraws()).isEqualTo(1);
        assertThat(lg.awayLosses()).isEqualTo(25);
    }

    @Test
    void parsesBattingAverageAndEraForAllTenTeams() {
        Map<String, BigDecimal> batting = parser.parseBattingAverages(
                metricFixture("HRA_RT", "0.270")
        );
        Map<String, BigDecimal> eras = parser.parseEras(
                metricFixture("ERA_RT", "4.90")
        );

        assertThat(batting).hasSize(10).containsEntry("LG", new BigDecimal("0.270"));
        assertThat(eras).hasSize(10).containsEntry("LG", new BigDecimal("4.90"));
    }

    static String standingsFixture() {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < TEAMS.size(); index++) {
            String team = TEAMS.get(index);
            String recent = team.equals("LG") ? "3승1무6패" : "5승0무5패";
            String home = team.equals("LG") ? "32-0-20" : "25-1-24";
            String away = team.equals("LG") ? "23-1-25" : "25-1-24";
            int wins = team.equals("LG") ? 55 : 50;
            int losses = team.equals("LG") ? 45 : 48;
            int draws = team.equals("LG") ? 1 : 2;
            String rate = team.equals("LG") ? "0.550" : "0.510";
            rows.append("<tr>")
                    .append(td(index + 1)).append(td(team)).append(td(101))
                    .append(td(wins)).append(td(losses)).append(td(draws))
                    .append(td(rate)).append(td("0")).append(td(recent))
                    .append(td("1승")).append(td(home)).append(td(away))
                    .append("</tr>");
        }
        return "<table><thead><tr><th>순위</th><th>팀</th><th>경기</th>"
                + "<th>승</th><th>패</th><th>무</th><th>승률</th><th>게임차</th>"
                + "<th>최근10경기</th></tr></thead><tbody>"
                + rows + "</tbody></table>";
    }

    static String metricFixture(String dataId, String lgValue) {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < TEAMS.size(); index++) {
            String team = TEAMS.get(index);
            String value = team.equals("LG") ? lgValue : "0.300";
            rows.append("<tr>").append(td(index + 1)).append(td(team))
                    .append("<td data-id=\"").append(dataId).append("\">")
                    .append(value).append("</td></tr>");
        }
        return "<table><tbody>" + rows + "</tbody></table>";
    }

    private static String td(Object value) {
        return "<td>" + value + "</td>";
    }
}
