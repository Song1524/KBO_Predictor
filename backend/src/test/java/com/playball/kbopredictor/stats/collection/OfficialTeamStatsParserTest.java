package com.playball.kbopredictor.stats.collection;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialTeamStatsParserTest {

    private static final List<OfficialRow> OFFICIAL_2026_08_13 = List.of(
            row(1, "KT", 100, 60, 38, 2, "0.612", "0", "7승0무3패", "2패", "29-1-19", "31-1-19"),
            row(2, "삼성", 103, 60, 41, 2, "0.594", "1.5", "3승0무7패", "1승", "29-1-19", "31-1-22"),
            row(3, "LG", 104, 57, 46, 1, "0.553", "5.5", "4승1무5패", "1승", "32-0-20", "25-1-26"),
            row(4, "두산", 104, 54, 46, 4, "0.540", "7", "7승1무2패", "1승", "30-2-20", "24-2-26"),
            row(5, "KIA", 103, 54, 47, 2, "0.535", "7.5", "5승0무5패", "1패", "28-2-21", "26-0-26"),
            row(6, "한화", 102, 48, 51, 3, "0.485", "12.5", "4승0무6패", "1패", "22-2-27", "26-1-24"),
            row(7, "NC", 98, 45, 51, 2, "0.469", "14", "3승1무6패", "2승", "25-0-27", "20-2-24"),
            row(8, "롯데", 103, 45, 56, 2, "0.446", "16.5", "5승0무5패", "1승", "19-0-30", "26-2-26"),
            row(9, "SSG", 106, 41, 61, 4, "0.402", "21", "5승0무5패", "1패", "21-3-29", "20-1-32"),
            row(10, "키움", 107, 39, 66, 2, "0.371", "24.5", "4승0무6패", "1패", "23-1-33", "16-1-33")
    );

    private final OfficialTeamStatsParser parser =
            new OfficialTeamStatsParser();

    @Test
    void parsesOfficialTenTeamsInOfficialRankOrder() {
        List<OfficialTeamStanding> standings = parser.parseStandings(
                standingsFixture()
        );

        assertThat(standings).hasSize(10);
        assertThat(standings).extracting(OfficialTeamStanding::rank)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(standings).extracting(OfficialTeamStanding::teamCode)
                .containsExactly("KT", "SS", "LG", "OB", "HT", "HH", "NC", "LT", "SK", "WO");

        OfficialTeamStanding kt = standings.getFirst();
        assertThat(kt.games()).isEqualTo(100);
        assertThat(kt.wins()).isEqualTo(60);
        assertThat(kt.losses()).isEqualTo(38);
        assertThat(kt.draws()).isEqualTo(2);
        assertThat(kt.winRate()).isEqualByComparingTo("0.612");
        assertThat(kt.gamesBehind()).isEqualByComparingTo("0");
        assertThat(kt.streak()).isEqualTo("2패");
        assertThat(kt.recent10Wins()).isEqualTo(7);
        assertThat(kt.recent10Draws()).isZero();
        assertThat(kt.recent10Losses()).isEqualTo(3);
        assertThat(kt.homeWins()).isEqualTo(29);
        assertThat(kt.awayWins()).isEqualTo(31);
        assertThat(standings).allSatisfy(standing ->
                assertThat(standing.wins() + standing.losses() + standing.draws())
                        .isEqualTo(standing.games())
        );
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

    @Test
    void rejectsEmptyResponse() {
        assertThatThrownBy(() -> parser.parseStandings(""))
                .isInstanceOf(PregameDataCollectionException.class)
                .hasMessageContaining("표를 찾을 수 없습니다");
    }

    @Test
    void rejectsMalformedRowInsteadOfReturningPartialValues() {
        String malformed = standingsFixture().replaceFirst(
                "<td>100</td><td>60</td>",
                "<td>not-a-number</td><td>60</td>"
        );

        assertThatThrownBy(() -> parser.parseStandings(malformed))
                .isInstanceOf(PregameDataCollectionException.class)
                .hasMessageContaining("행을 해석할 수 없습니다");
    }

    @Test
    void rejectsGamesThatDoNotEqualWinsLossesAndDraws() {
        String inconsistent = standingsFixture().replaceFirst(
                "<td>100</td><td>60</td><td>38</td><td>2</td>",
                "<td>101</td><td>60</td><td>38</td><td>2</td>"
        );

        assertThatThrownBy(() -> parser.parseStandings(inconsistent))
                .isInstanceOf(PregameDataCollectionException.class)
                .hasMessageContaining("승/패/무 합계");
    }

    static String standingsFixture() {
        StringBuilder rows = new StringBuilder();
        for (OfficialRow row : OFFICIAL_2026_08_13) {
            rows.append("<tr>")
                    .append(td(row.rank())).append(td(row.team()))
                    .append(td(row.games())).append(td(row.wins()))
                    .append(td(row.losses())).append(td(row.draws()))
                    .append(td(row.winRate())).append(td(row.gamesBehind()))
                    .append(td(row.recent10())).append(td(row.streak()))
                    .append(td(row.home())).append(td(row.away()))
                    .append("</tr>");
        }
        return "<table><thead><tr><th>순위</th><th>팀명</th><th>경기</th>"
                + "<th>승</th><th>패</th><th>무</th><th>승률</th><th>게임차</th>"
                + "<th>최근10경기</th><th>연속</th><th>홈</th><th>방문</th>"
                + "</tr></thead><tbody>" + rows + "</tbody></table>";
    }

    static String metricFixture(String dataId, String lgValue) {
        StringBuilder rows = new StringBuilder();
        for (OfficialRow officialRow : OFFICIAL_2026_08_13) {
            String value = officialRow.team().equals("LG") ? lgValue : "0.300";
            rows.append("<tr>").append(td(officialRow.rank()))
                    .append(td(officialRow.team()))
                    .append("<td data-id=\"").append(dataId).append("\">")
                    .append(value).append("</td></tr>");
        }
        return "<table><tbody>" + rows + "</tbody></table>";
    }

    private static OfficialRow row(
            int rank,
            String team,
            int games,
            int wins,
            int losses,
            int draws,
            String winRate,
            String gamesBehind,
            String recent10,
            String streak,
            String home,
            String away
    ) {
        return new OfficialRow(
                rank, team, games, wins, losses, draws,
                winRate, gamesBehind, recent10, streak, home, away
        );
    }

    private static String td(Object value) {
        return "<td>" + value + "</td>";
    }

    private record OfficialRow(
            int rank,
            String team,
            int games,
            int wins,
            int losses,
            int draws,
            String winRate,
            String gamesBehind,
            String recent10,
            String streak,
            String home,
            String away
    ) {
    }
}
