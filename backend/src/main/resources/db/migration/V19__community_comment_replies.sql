ALTER TABLE community_comments
    ADD COLUMN parent_comment_id BIGINT NULL AFTER user_id;

CREATE INDEX idx_community_comments_parent_status_created_id
    ON community_comments (parent_comment_id, status, created_at, id);

ALTER TABLE community_comments
    ADD CONSTRAINT fk_community_comments_parent
        FOREIGN KEY (parent_comment_id) REFERENCES community_comments (id);
