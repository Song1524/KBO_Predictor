package com.playball.kbopredictor.stats.collection;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartingPitcherCollectorTest {

    @Test
    void targetedRetryFetchesPlayerDetailsOnlyForIncompleteGame() {
        OfficialStartingPitcherSource source = mock(OfficialStartingPitcherSource.class);
        LocalDate date = LocalDate.of(2026, 8, 12);
        when(source.fetchGameList(date)).thenReturn("""
                {
                  "game": [
                    {
                      "G_DT": "20260812", "G_ID": "20260812HHOB0",
                      "SEASON_ID": 2026, "AWAY_ID": "HH", "HOME_ID": "OB",
                      "T_PIT_P_ID": 56724, "T_PIT_P_NM": "화이트",
                      "B_PIT_P_ID": 52043, "B_PIT_P_NM": "벤자민",
                      "START_PIT_CK": 1
                    },
                    {
                      "G_DT": "20260812", "G_ID": "20260812LTSK0",
                      "SEASON_ID": 2026, "AWAY_ID": "LT", "HOME_ID": "SK",
                      "T_PIT_P_ID": 67539, "T_PIT_P_NM": "나균안",
                      "B_PIT_P_ID": 56840, "B_PIT_P_NM": "김민준",
                      "START_PIT_CK": 1
                    }
                  ]
                }
                """);
        when(source.fetchPlayerDetail("67539")).thenReturn("<h6>2025 성적</h6>");
        when(source.fetchPlayerDetail("56840")).thenReturn("<h6>2025 성적</h6>");
        StartingPitcherCollector collector = new StartingPitcherCollector(
                source,
                new OfficialStartingPitcherParser(new ObjectMapper())
        );

        StartingPitcherCollectionBatch result = collector.collect(
                date,
                Set.of("20260812LTSK0")
        );

        assertThat(result.sourceGameCount()).isEqualTo(2);
        assertThat(result.pitchers())
                .extracting(CollectedStartingPitcher::externalGameId)
                .containsOnly("20260812LTSK0");
        assertThat(result.pitchers()).hasSize(2);
        verify(source).fetchPlayerDetail("67539");
        verify(source).fetchPlayerDetail("56840");
        verify(source, never()).fetchPlayerDetail("56724");
        verify(source, never()).fetchPlayerDetail("52043");
    }
}
