package com.playball.kbopredictor.ranking.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RankingQueryRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void totalRankingUsesPointCorrectCountAndUserIdTieBreakers() {
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                any(RowMapper.class)
        )).thenReturn(List.of());
        RankingQueryRepository repository = new RankingQueryRepository(
                jdbcTemplate
        );

        repository.findTotalTop(20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                any(SqlParameterSource.class),
                any(RowMapper.class)
        );
        assertThat(normalize(sql.getValue())).contains(
                "ORDER BY current_point DESC, correct_count DESC, user_id ASC",
                "LEFT JOIN user_predictions",
                "LEFT JOIN game_settlements settlement ON settlement.id = up.settlement_id AND settlement.state = 'SETTLED'",
                "LIMIT :limit"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void periodQueryUsesSettlementTimeAndExcludesRefundOnlyParticipants() {
        when(jdbcTemplate.query(
                anyString(),
                any(SqlParameterSource.class),
                any(RowMapper.class)
        )).thenReturn(List.of());
        RankingQueryRepository repository = new RankingQueryRepository(
                jdbcTemplate
        );
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 0, 0);

        repository.findPeriodTop(start, end, 20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                parameters.capture(),
                any(RowMapper.class)
        );
        String normalized = normalize(sql.getValue());
        assertThat(normalized).contains(
                "up.settled = TRUE",
                "up.settlement_status IN ('WON', 'LOST', 'REFUNDED')",
                "up.settled_at >= :periodStart",
                "up.settled_at < :periodEndExclusive",
                "JOIN game_settlements settlement ON settlement.id = up.settlement_id AND settlement.state = 'SETTLED'",
                "WHEN up.settlement_status = 'WON' THEN reward.point_change - up.point_amount",
                "WHEN up.settlement_status = 'LOST' THEN -up.point_amount",
                "WHEN up.settlement_status = 'REFUNDED' THEN 0",
                "reward.type = 'PREDICTION_REWARD'",
                "reward.settlement_id = settlement.id",
                "reward.settlement_revision = settlement.revision",
                "HAVING SUM(CASE WHEN up.settlement_status IN ('WON', 'LOST') THEN 1 ELSE 0 END) > 0",
                "ORDER BY period_profit DESC, correct_count DESC, prediction_count DESC, user_id ASC"
        ).doesNotContain("SIGNUP_BONUS", "up.updated_at");
        assertThat(parameters.getValue().getValue("periodStart"))
                .isEqualTo(start);
        assertThat(parameters.getValue().getValue("periodEndExclusive"))
                .isEqualTo(end);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
