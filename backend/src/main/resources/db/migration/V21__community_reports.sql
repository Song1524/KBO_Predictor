CREATE TABLE community_post_reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL,
    reason VARCHAR(20) NOT NULL,
    detail VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    processed_by BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_community_post_reports_post_reporter
        UNIQUE (post_id, reporter_id),
    CONSTRAINT fk_community_post_reports_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id),
    CONSTRAINT fk_community_post_reports_reporter
        FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT fk_community_post_reports_processed_by
        FOREIGN KEY (processed_by) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_community_post_reports_status_created_id
    ON community_post_reports (status, created_at DESC, id DESC);

CREATE TABLE community_comment_reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    comment_id BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL,
    reason VARCHAR(20) NOT NULL,
    detail VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    processed_by BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_community_comment_reports_comment_reporter
        UNIQUE (comment_id, reporter_id),
    CONSTRAINT fk_community_comment_reports_comment
        FOREIGN KEY (comment_id) REFERENCES community_comments (id),
    CONSTRAINT fk_community_comment_reports_reporter
        FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT fk_community_comment_reports_processed_by
        FOREIGN KEY (processed_by) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_community_comment_reports_status_created_id
    ON community_comment_reports (status, created_at DESC, id DESC);
