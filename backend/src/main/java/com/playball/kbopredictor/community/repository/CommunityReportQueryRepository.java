package com.playball.kbopredictor.community.repository;

import com.playball.kbopredictor.community.dto.AdminCommunityReportResponse;
import com.playball.kbopredictor.community.entity.CommunityReportReason;
import com.playball.kbopredictor.community.entity.CommunityReportStatus;
import com.playball.kbopredictor.community.entity.CommunityReportTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class CommunityReportQueryRepository {

    private static final String UNION_QUERY = """
            SELECT 'POST' AS report_type,
                   report.id AS report_id,
                   post.id AS target_id,
                   CASE WHEN post.status = 'DELETED'
                        THEN '삭제된 콘텐츠'
                        ELSE CONCAT(post.title, ' · ', LEFT(post.content, 160))
                   END AS target_content,
                   CASE WHEN post.status = 'DELETED' THEN 1 ELSE 0 END
                        AS content_deleted,
                   reporter.id AS reporter_id,
                   reporter.nickname AS reporter_nickname,
                   author.id AS author_id,
                   author.nickname AS author_nickname,
                   report.reason AS report_reason,
                   report.detail AS report_detail,
                   report.status AS report_status,
                   report.created_at AS created_at,
                   report.processed_at AS processed_at,
                   processor.id AS processed_by_id,
                   processor.nickname AS processed_by_nickname
            FROM community_post_reports report
            JOIN community_posts post ON post.id = report.post_id
            JOIN users reporter ON reporter.id = report.reporter_id
            JOIN users author ON author.id = post.user_id
            LEFT JOIN users processor ON processor.id = report.processed_by
            UNION ALL
            SELECT 'COMMENT' AS report_type,
                   report.id AS report_id,
                   comment.id AS target_id,
                   CASE WHEN comment.status = 'DELETED'
                        THEN '삭제된 콘텐츠'
                        ELSE LEFT(comment.content, 160)
                   END AS target_content,
                   CASE WHEN comment.status = 'DELETED' THEN 1 ELSE 0 END
                        AS content_deleted,
                   reporter.id AS reporter_id,
                   reporter.nickname AS reporter_nickname,
                   author.id AS author_id,
                   author.nickname AS author_nickname,
                   report.reason AS report_reason,
                   report.detail AS report_detail,
                   report.status AS report_status,
                   report.created_at AS created_at,
                   report.processed_at AS processed_at,
                   processor.id AS processed_by_id,
                   processor.nickname AS processed_by_nickname
            FROM community_comment_reports report
            JOIN community_comments comment ON comment.id = report.comment_id
            JOIN users reporter ON reporter.id = report.reporter_id
            JOIN users author ON author.id = comment.user_id
            LEFT JOIN users processor ON processor.id = report.processed_by
            """;

    private static final String PAGE_QUERY = """
            SELECT *
            FROM (
            """ + UNION_QUERY + """
            ) combined_reports
            WHERE (:status IS NULL OR report_status = :status)
            ORDER BY created_at DESC, report_type, report_id DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_QUERY = """
            SELECT COUNT(*)
            FROM (
            """ + UNION_QUERY + """
            ) combined_reports
            WHERE (:status IS NULL OR report_status = :status)
            """;

    private static final RowMapper<AdminCommunityReportResponse> ROW_MAPPER =
            CommunityReportQueryRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<AdminCommunityReportResponse> findPage(
            CommunityReportStatus status,
            int page,
            int size
    ) {
        MapSqlParameterSource parameters = parameters(status)
                .addValue("limit", size)
                .addValue("offset", Math.multiplyExact(page, size));
        return jdbcTemplate.query(PAGE_QUERY, parameters, ROW_MAPPER);
    }

    public long count(CommunityReportStatus status) {
        Long count = jdbcTemplate.queryForObject(
                COUNT_QUERY,
                parameters(status),
                Long.class
        );
        return count == null ? 0 : count;
    }

    private MapSqlParameterSource parameters(CommunityReportStatus status) {
        return new MapSqlParameterSource()
                .addValue("status", status == null ? null : status.name());
    }

    private static AdminCommunityReportResponse mapRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new AdminCommunityReportResponse(
                CommunityReportTargetType.valueOf(
                        resultSet.getString("report_type")
                ),
                resultSet.getLong("report_id"),
                resultSet.getLong("target_id"),
                resultSet.getString("target_content"),
                resultSet.getBoolean("content_deleted"),
                resultSet.getLong("reporter_id"),
                resultSet.getString("reporter_nickname"),
                resultSet.getLong("author_id"),
                resultSet.getString("author_nickname"),
                CommunityReportReason.valueOf(
                        resultSet.getString("report_reason")
                ),
                resultSet.getString("report_detail"),
                CommunityReportStatus.valueOf(
                        resultSet.getString("report_status")
                ),
                timestamp(resultSet, "created_at"),
                timestamp(resultSet, "processed_at"),
                nullableLong(resultSet, "processed_by_id"),
                resultSet.getString("processed_by_nickname")
        );
    }

    private static LocalDateTime timestamp(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Long nullableLong(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
}
