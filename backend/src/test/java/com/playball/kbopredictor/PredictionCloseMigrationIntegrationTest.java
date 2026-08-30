package com.playball.kbopredictor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false",
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PredictionCloseMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void v16CorrectsOnlyFutureScheduledGamesAndReopensEarlyFinalOdds() {
        LocalDateTime futureStart = LocalDateTime.now()
                .plusDays(2)
                .withNano(0);
        LocalDateTime pastStart = LocalDateTime.now()
                .minusDays(2)
                .withNano(0);
        long futureGameId = insertGame(
                "V16-FUTURE-SCHEDULED",
                "SCHEDULED",
                futureStart,
                futureStart.minusMinutes(30)
        );
        long finishedGameId = insertGame(
                "V16-PAST-FINISHED",
                "FINISHED",
                pastStart,
                pastStart.minusMinutes(30)
        );
        long startedScheduledGameId = insertGame(
                "V16-PAST-SCHEDULED",
                "SCHEDULED",
                pastStart,
                pastStart.minusMinutes(30)
        );
        insertFinalizedOdds(futureGameId, futureStart.minusMinutes(30));
        insertFinalizedOdds(finishedGameId, pastStart.minusMinutes(30));

        executeV16Migration();
        executeV16Migration();

        assertThat(predictionCloseAt(futureGameId))
                .isEqualTo(futureStart.minusMinutes(10));
        assertThat(predictionCloseAt(finishedGameId))
                .isEqualTo(pastStart.minusMinutes(30));
        assertThat(predictionCloseAt(startedScheduledGameId))
                .isEqualTo(pastStart.minusMinutes(30));
        assertThat(finalized(futureGameId)).isFalse();
        assertThat(finalizedAt(futureGameId)).isNull();
        assertThat(finalHomeWinOdds(futureGameId)).isNull();
        assertThat(finalized(finishedGameId)).isTrue();
        assertThat(finalizedAt(finishedGameId))
                .isEqualTo(pastStart.minusMinutes(30));
    }

    private long insertGame(
            String externalGameId,
            String status,
            LocalDateTime startAt,
            LocalDateTime closeAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO games (
                    external_game_id,
                    season,
                    game_date,
                    game_time,
                    home_team_id,
                    away_team_id,
                    stadium,
                    status,
                    prediction_close_at,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                externalGameId,
                startAt.getYear(),
                startAt.toLocalDate(),
                startAt.toLocalTime(),
                1L,
                2L,
                "V16 migration test",
                status,
                closeAt,
                startAt.minusDays(1),
                startAt.minusDays(1)
        );
        Long gameId = jdbcTemplate.queryForObject(
                "SELECT id FROM games WHERE external_game_id = ?",
                Long.class,
                externalGameId
        );
        return gameId == null ? 0L : gameId;
    }

    private void insertFinalizedOdds(long gameId, LocalDateTime finalizedAt) {
        jdbcTemplate.update("""
                INSERT INTO game_odds (
                    game_id,
                    home_win_points,
                    draw_points,
                    away_win_points,
                    final_home_win_odds,
                    final_draw_odds,
                    final_away_win_odds,
                    finalized,
                    finalized_at,
                    created_at,
                    updated_at
                ) VALUES (?, 100, 0, 0, 1.00, 10.00, 10.00, 1, ?, ?, ?)
                """,
                gameId,
                finalizedAt,
                finalizedAt,
                finalizedAt
        );
    }

    private void executeV16Migration() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(
                        "db/migration/V16__prediction_close_ten_minutes.sql"
                )
        );
        DatabasePopulatorUtils.execute(
                populator,
                jdbcTemplate.getDataSource()
        );
    }

    private LocalDateTime predictionCloseAt(long gameId) {
        return jdbcTemplate.queryForObject(
                "SELECT prediction_close_at FROM games WHERE id = ?",
                LocalDateTime.class,
                gameId
        );
    }

    private boolean finalized(long gameId) {
        Boolean result = jdbcTemplate.queryForObject(
                "SELECT finalized FROM game_odds WHERE game_id = ?",
                Boolean.class,
                gameId
        );
        return Boolean.TRUE.equals(result);
    }

    private LocalDateTime finalizedAt(long gameId) {
        return jdbcTemplate.queryForObject(
                "SELECT finalized_at FROM game_odds WHERE game_id = ?",
                LocalDateTime.class,
                gameId
        );
    }

    private BigDecimal finalHomeWinOdds(long gameId) {
        return jdbcTemplate.queryForObject(
                "SELECT final_home_win_odds FROM game_odds WHERE game_id = ?",
                BigDecimal.class,
                gameId
        );
    }
}
