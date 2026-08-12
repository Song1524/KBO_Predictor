package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KboScheduleParserTest {

    private KboScheduleParser parser;

    @BeforeEach
    void setUp() {
        parser = new KboScheduleParser(new ObjectMapper());
    }

    @Test
    void parsesScheduledFinishedDrawCancelledAndContinuesAfterBadRow()
            throws IOException {
        String fixture = new ClassPathResource(
                "fixtures/kbo-schedule-2026-08-12.json"
        ).getContentAsString(StandardCharsets.UTF_8);

        GameCollectionBatch batch = parser.parse(
                fixture,
                LocalDate.of(2026, 8, 12)
        );

        assertThat(batch.sourceRowCount()).isEqualTo(7);
        assertThat(batch.errors()).withFailMessage(batch.toString()).hasSize(1)
                .first()
                .asString()
                .contains("알 수 없는 KBO 팀명");
        assertThat(batch.games()).hasSize(6);

        CollectedGame scheduled = game(batch, "20260812LGOB0");
        assertThat(scheduled.status()).isEqualTo(GameStatus.SCHEDULED);
        assertThat(scheduled.awayTeamCode()).isEqualTo("LG");
        assertThat(scheduled.homeTeamCode()).isEqualTo("OB");

        CollectedGame homeWin = game(batch, "20260812HHKT0");
        assertThat(homeWin.status()).isEqualTo(GameStatus.FINISHED);
        assertThat(homeWin.awayScore()).isEqualTo(2);
        assertThat(homeWin.homeScore()).isEqualTo(5);
        assertThat(homeWin.result()).isEqualTo(GameResult.HOME_WIN);

        CollectedGame awayWin = game(batch, "20260812HTNC0");
        assertThat(awayWin.result()).isEqualTo(GameResult.AWAY_WIN);

        CollectedGame draw = game(batch, "20260812SKWO0");
        assertThat(draw.result()).isEqualTo(GameResult.DRAW);

        CollectedGame inProgress = game(batch, "20260812LGOB1");
        assertThat(inProgress.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(inProgress.awayScore()).isEqualTo(1);
        assertThat(inProgress.homeScore()).isEqualTo(3);
        assertThat(inProgress.result()).isNull();

        CollectedGame cancelled = game(batch, "20260812SSLT0");
        assertThat(cancelled.status()).isEqualTo(GameStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("우천취소");
        assertThat(cancelled.result()).isNull();
        assertThat(cancelled.homeScore()).isNull();
        assertThat(cancelled.awayScore()).isNull();
    }

    @Test
    void historicalOtherReasonIsTreatedAsCancelledNotScheduled() {
        String json = """
                {"rows":[{"row":[
                  {"Text":"04.01(화)","Class":"day"},
                  {"Text":"<b>18:30</b>","Class":"time"},
                  {"Text":"<span>키움</span><em><span>vs</span></em><span>두산</span>","Class":"play"},
                  {"Text":"","Class":"relay"},
                  {"Text":"","Class":""},
                  {"Text":"","Class":""},
                  {"Text":"","Class":""},
                  {"Text":"잠실","Class":""},
                  {"Text":"기타","Class":""}
                ]}]}
                """;

        GameCollectionBatch batch = parser.parse(
                json,
                LocalDate.of(2025, 4, 1)
        );

        assertThat(batch.games()).hasSize(1);
        assertThat(batch.games().getFirst().status())
                .isEqualTo(GameStatus.CANCELLED);
        assertThat(batch.games().getFirst().cancelReason()).isEqualTo("기타");
    }

    @Test
    void dayCssClassCanContainAdditionalTokens() {
        String json = """
                {"rows":[{"row":[
                  {"Text":"08.12(수)","Class":"day first"},
                  {"Text":"<b>18:30</b>","Class":"time"},
                  {"Text":"<span>LG</span><em><span>vs</span></em><span>두산</span>","Class":"play"},
                  {"Text":"","Class":"relay"},
                  {"Text":"","Class":""},
                  {"Text":"","Class":""},
                  {"Text":"","Class":""},
                  {"Text":"잠실","Class":""},
                  {"Text":"","Class":""}
                ]}]}
                """;

        GameCollectionBatch batch = parser.parse(
                json,
                LocalDate.of(2026, 8, 12)
        );

        assertThat(batch.games()).hasSize(1);
        assertThat(batch.games().getFirst().externalGameId())
                .isEqualTo("20260812LGOB0");
    }

    @Test
    void malformedRowIsReportedAndEmptyRowsRemainEmpty() {
        GameCollectionBatch malformed = parser.parse(
                "{\"rows\":[{\"unexpected\":[]}]}",
                LocalDate.of(2026, 8, 12)
        );
        GameCollectionBatch empty = parser.parse(
                "{\"rows\":[]}",
                LocalDate.of(2026, 8, 12)
        );

        assertThat(malformed.games()).isEmpty();
        assertThat(malformed.errors()).singleElement()
                .asString()
                .contains("row 배열");
        assertThat(empty.games()).isEmpty();
        assertThat(empty.errors()).isEmpty();
    }

    @Test
    void missingRowsArrayFailsWithoutCreatingGames() {
        assertThatThrownBy(() -> parser.parse(
                "{}",
                LocalDate.of(2026, 8, 12)
        ))
                .isInstanceOf(GameDataCollectionException.class)
                .hasMessageContaining("rows 배열");
    }

    private CollectedGame game(GameCollectionBatch batch, String externalId) {
        return batch.games().stream()
                .filter(game -> game.externalGameId().equals(externalId))
                .findFirst()
                .orElseThrow();
    }
}
