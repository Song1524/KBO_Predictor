package com.playball.kbopredictor.stats.repository;

import com.playball.kbopredictor.stats.entity.PitcherStat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pitcher-stat-repository;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false",
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class PitcherStatRepositoryIntegrationTest {

    private static final LocalDate GAME_DATE = LocalDate.of(2026, 8, 12);
    private static final LocalDateTime GAME_START = LocalDateTime.of(
            2026, 8, 12, 18, 30
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PitcherStatRepository pitcherStatRepository;

    @Test
    void selectsLatestSnapshotOnOrBeforeGameDateAndExcludesFutureDate() {
        long playerId = insertPlayer();
        insertStat(playerId, GAME_DATE.minusDays(2), GAME_START.minusDays(2), "4.50");
        insertStat(playerId, GAME_DATE, GAME_START.minusHours(2), "3.25");
        insertStat(playerId, GAME_DATE.plusDays(1), GAME_START.minusHours(1), "2.10");

        Optional<PitcherStat> result = pitcherStatRepository
                .findTopByPlayerIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                        playerId, GAME_DATE, GAME_START
                );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getStatDate()).isEqualTo(GAME_DATE);
        assertThat(result.orElseThrow().getEra()).isEqualByComparingTo("3.25");
    }

    @Test
    void excludesSnapshotCollectedAfterGameStartAndFallsBackToOlderAvailableData() {
        long playerId = insertPlayer();
        insertStat(playerId, GAME_DATE.minusDays(2), GAME_START.minusDays(2), "4.50");
        insertStat(playerId, GAME_DATE.minusDays(1), GAME_START.plusMinutes(1), "3.10");

        Optional<PitcherStat> result = pitcherStatRepository
                .findTopByPlayerIdAndStatDateLessThanEqualAndCollectedAtBeforeOrderByStatDateDescCollectedAtDesc(
                        playerId, GAME_DATE, GAME_START
                );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getStatDate())
                .isEqualTo(GAME_DATE.minusDays(2));
        assertThat(result.orElseThrow().getEra()).isEqualByComparingTo("4.50");
    }

    private long insertPlayer() {
        String kboPlayerId = "T" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 19);
        LocalDateTime now = GAME_START.minusDays(3);
        jdbcTemplate.update("""
                INSERT INTO players (
                    kbo_player_id, team_id, name, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                kboPlayerId, null, "저장소 테스트 투수", now, now
        );
        Long playerId = jdbcTemplate.queryForObject(
                "SELECT id FROM players WHERE kbo_player_id = ?",
                Long.class,
                kboPlayerId
        );
        return playerId == null ? 0L : playerId;
    }

    private void insertStat(
            long playerId,
            LocalDate statDate,
            LocalDateTime collectedAt,
            String era
    ) {
        jdbcTemplate.update("""
                INSERT INTO pitcher_stats (
                    player_id, season, stat_date, era, win, lose,
                    innings, whip, collected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                playerId, 2026, statDate, era, 8, 4,
                "100 1/3", "1.20", collectedAt
        );
    }
}
