CREATE INDEX idx_community_posts_user_created_id
    ON community_posts (user_id, created_at DESC, id DESC);

CREATE INDEX idx_community_comments_user_created_id
    ON community_comments (user_id, created_at DESC, id DESC);
