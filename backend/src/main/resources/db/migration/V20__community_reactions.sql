CREATE TABLE community_post_reactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reaction_type VARCHAR(10) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_community_post_reactions_post_user
        UNIQUE (post_id, user_id),
    CONSTRAINT fk_community_post_reactions_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id),
    CONSTRAINT fk_community_post_reactions_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_community_post_reactions_post_type
    ON community_post_reactions (post_id, reaction_type);

CREATE TABLE community_comment_reactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    comment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reaction_type VARCHAR(10) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_community_comment_reactions_comment_user
        UNIQUE (comment_id, user_id),
    CONSTRAINT fk_community_comment_reactions_comment
        FOREIGN KEY (comment_id) REFERENCES community_comments (id),
    CONSTRAINT fk_community_comment_reactions_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_community_comment_reactions_comment_type
    ON community_comment_reactions (comment_id, reaction_type);
