package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialStartingPitcherParserTest {

    private OfficialStartingPitcherParser parser;

    @BeforeEach
    void setUp() {
        parser = new OfficialStartingPitcherParser(new ObjectMapper());
    }

    @Test
    void parsesBothOfficialStartersAndSkipsUnannouncedGame() {
        String json = """
                {
                  "game": [
                    {
                      "G_DT": "20260811",
                      "G_ID": "20260811HHOB0",
                      "SEASON_ID": 2026,
                      "AWAY_ID": "HH",
                      "HOME_ID": "OB",
                      "T_PIT_P_ID": 56719,
                      "T_PIT_P_NM": "왕옌청 ",
                      "B_PIT_P_ID": 68220,
                      "B_PIT_P_NM": "곽빈 ",
                      "START_PIT_CK": 1
                    },
                    {
                      "G_DT": "20260811",
                      "G_ID": "20260811LGWO0",
                      "SEASON_ID": 2026,
                      "START_PIT_CK": 0
                    }
                  ]
                }
                """;

        OfficialStartingPitcherParser.ParsedStartingPitcherList result =
                parser.parseGameList(json, LocalDate.of(2026, 8, 11));

        assertThat(result.sourceGameCount()).isEqualTo(2);
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().get(0).externalGameId())
                .isEqualTo("20260811HHOB0");
        assertThat(result.candidates().get(0).side())
                .isEqualTo(StartingPitcherSide.AWAY);
        assertThat(result.candidates().get(0).kboPlayerId()).isEqualTo("56719");
        assertThat(result.candidates().get(1).side())
                .isEqualTo(StartingPitcherSide.HOME);
        assertThat(result.candidates().get(1).playerName()).isEqualTo("곽빈");
    }

    @Test
    void parsesEraWinsLossesInningsAndWhipFromOfficialPlayerPage() {
        String html = """
                <h6>2026 성적</h6>
                <table summary="투수성적으로 평균자책점과 이닝을 표시합니다">
                  <thead><tr><th>팀명</th><th>ERA</th><th>G</th><th>CG</th>
                    <th>SHO</th><th>W</th><th>L</th><th>SV</th><th>HLD</th>
                    <th>WPCT</th><th>TBF</th><th>NP</th><th><a title="이닝">IP</a></th></tr></thead>
                  <tbody><tr><td>두산</td><td>2.66</td><td>20</td><td>0</td>
                    <td>0</td><td>9</td><td>4</td><td>0</td><td>0</td>
                    <td>0.692</td><td>476</td><td>1981</td><td>115</td></tr></tbody>
                </table>
                <table summary="투수 WHIP 기록">
                  <thead><tr><th>SAC</th><th>SF</th><th>BB</th><th>IBB</th>
                    <th>SO</th><th>WP</th><th>BK</th><th>R</th><th>ER</th>
                    <th>BSV</th><th><a title="이닝당 출루허용률">WHIP</a></th></tr></thead>
                  <tbody><tr><td>6</td><td>3</td><td>30</td><td>0</td><td>138</td>
                    <td>3</td><td>0</td><td>38</td><td>34</td><td>0</td><td>1.15</td></tr></tbody>
                </table>
                """;

        CollectedPitcherSeasonStat stat = parser
                .parsePlayerDetail(html, 2026)
                .orElseThrow();

        assertThat(stat.era()).isEqualByComparingTo("2.66");
        assertThat(stat.wins()).isEqualTo(9);
        assertThat(stat.losses()).isEqualTo(4);
        assertThat(stat.innings()).isEqualTo("115");
        assertThat(stat.whip()).isEqualByComparingTo("1.15");
    }

    @Test
    void missingOrDifferentSeasonStatsRemainMissing() {
        assertThat(parser.parsePlayerDetail("<h6>2025 성적</h6>", 2026))
                .isEmpty();
    }
}
