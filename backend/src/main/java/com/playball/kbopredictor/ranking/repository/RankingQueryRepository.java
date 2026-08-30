package com.playball.kbopredictor.ranking.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RankingQueryRepository {

    static final String TOTAL_RANKING_CTE = """
            WITH user_totals AS (
                SELECT
                    u.id AS user_id,
                    u.nickname,
                    COALESCE(u.point, 0) AS current_point,
                    COUNT(up.id) AS prediction_count,
                    SUM(CASE
                        WHEN up.settled = TRUE
                         AND settlement.id IS NOT NULL
                         AND up.settlement_status = 'WON' THEN 1
                        ELSE 0
                    END) AS correct_count,
                    SUM(CASE
                        WHEN up.settled = TRUE
                         AND settlement.id IS NOT NULL
                         AND up.settlement_status IN ('WON', 'LOST') THEN 1
                        ELSE 0
                    END) AS graded_prediction_count
                FROM users u
                LEFT JOIN user_predictions up ON up.user_id = u.id
                LEFT JOIN game_settlements settlement
                  ON settlement.id = up.settlement_id
                 AND settlement.state = 'SETTLED'
                WHERE u.status = 'ACTIVE'
                  AND u.nickname IS NOT NULL
                  AND u.nickname <> ''
                GROUP BY u.id, u.nickname, u.point
            ), ranked AS (
                SELECT
                    ROW_NUMBER() OVER (
                        ORDER BY current_point DESC, correct_count DESC, user_id ASC
                    ) AS rank_no,
                    user_id,
                    nickname,
                    current_point AS score,
                    prediction_count,
                    correct_count,
                    graded_prediction_count
                FROM user_totals
            )
            """;

    static final String PERIOD_RANKING_CTE = """
            WITH period_totals AS (
                SELECT
                    u.id AS user_id,
                    u.nickname,
                    SUM(CASE
                        WHEN up.settlement_status = 'WON'
                            THEN reward.point_change - up.point_amount
                        WHEN up.settlement_status = 'LOST'
                            THEN -up.point_amount
                        WHEN up.settlement_status = 'REFUNDED'
                            THEN 0
                    END) AS period_profit,
                    COUNT(up.id) AS prediction_count,
                    SUM(CASE WHEN up.settlement_status = 'WON' THEN 1 ELSE 0 END)
                        AS correct_count,
                    SUM(CASE
                        WHEN up.settlement_status IN ('WON', 'LOST') THEN 1
                        ELSE 0
                    END) AS graded_prediction_count
                FROM users u
                JOIN user_predictions up
                  ON up.user_id = u.id
                 AND up.settled = TRUE
                 AND up.settlement_status IN ('WON', 'LOST', 'REFUNDED')
                 AND up.settled_at >= :periodStart
                 AND up.settled_at < :periodEndExclusive
                JOIN game_settlements settlement
                  ON settlement.id = up.settlement_id
                 AND settlement.state = 'SETTLED'
                LEFT JOIN point_histories reward
                  ON reward.user_prediction_id = up.id
                 AND reward.settlement_id = settlement.id
                 AND reward.settlement_revision = settlement.revision
                 AND reward.type = 'PREDICTION_REWARD'
                WHERE u.status = 'ACTIVE'
                  AND u.nickname IS NOT NULL
                  AND u.nickname <> ''
                GROUP BY u.id, u.nickname
                HAVING SUM(CASE
                    WHEN up.settlement_status IN ('WON', 'LOST') THEN 1
                    ELSE 0
                END) > 0
            ), ranked AS (
                SELECT
                    ROW_NUMBER() OVER (
                        ORDER BY period_profit DESC,
                                 correct_count DESC,
                                 prediction_count DESC,
                                 user_id ASC
                    ) AS rank_no,
                    user_id,
                    nickname,
                    period_profit AS score,
                    prediction_count,
                    correct_count,
                    graded_prediction_count
                FROM period_totals
            )
            """;

    private static final String SELECT_COLUMNS = """
            SELECT rank_no,
                   user_id,
                   nickname,
                   score,
                   prediction_count,
                   correct_count,
                   graded_prediction_count
            FROM ranked
            """;

    private static final RowMapper<RankingQueryRow> ROW_MAPPER =
            RankingQueryRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<RankingQueryRow> findTotalTop(int limit) {
        return jdbcTemplate.query(
                TOTAL_RANKING_CTE + SELECT_COLUMNS
                        + " ORDER BY rank_no LIMIT :limit",
                new MapSqlParameterSource("limit", limit),
                ROW_MAPPER
        );
    }

    public Optional<RankingQueryRow> findTotalByUserId(long userId) {
        return findOne(
                TOTAL_RANKING_CTE + SELECT_COLUMNS
                        + " WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId)
        );
    }

    public List<RankingQueryRow> findPeriodTop(
            LocalDateTime periodStart,
            LocalDateTime periodEndExclusive,
            int limit
    ) {
        return jdbcTemplate.query(
                PERIOD_RANKING_CTE + SELECT_COLUMNS
                        + " ORDER BY rank_no LIMIT :limit",
                periodParameters(periodStart, periodEndExclusive)
                        .addValue("limit", limit),
                ROW_MAPPER
        );
    }

    public Optional<RankingQueryRow> findPeriodByUserId(
            LocalDateTime periodStart,
            LocalDateTime periodEndExclusive,
            long userId
    ) {
        return findOne(
                PERIOD_RANKING_CTE + SELECT_COLUMNS
                        + " WHERE user_id = :userId",
                periodParameters(periodStart, periodEndExclusive)
                        .addValue("userId", userId)
        );
    }

    private Optional<RankingQueryRow> findOne(
            String sql,
            MapSqlParameterSource parameters
    ) {
        return jdbcTemplate.query(sql, parameters, ROW_MAPPER)
                .stream()
                .findFirst();
    }

    private MapSqlParameterSource periodParameters(
            LocalDateTime periodStart,
            LocalDateTime periodEndExclusive
    ) {
        return new MapSqlParameterSource()
                .addValue("periodStart", periodStart)
                .addValue("periodEndExclusive", periodEndExclusive);
    }

    private static RankingQueryRow mapRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new RankingQueryRow(
                resultSet.getLong("rank_no"),
                resultSet.getLong("user_id"),
                resultSet.getString("nickname"),
                resultSet.getLong("score"),
                resultSet.getLong("prediction_count"),
                resultSet.getLong("correct_count"),
                resultSet.getLong("graded_prediction_count")
        );
    }
}
