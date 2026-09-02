package com.playball.kbopredictor.community.entity;

import com.playball.kbopredictor.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "community_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private CommunityPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private CommunityComment parent;

    @Column(nullable = false, length = 1000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityContentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static CommunityComment create(
            CommunityPost post,
            User user,
            CommunityComment parent,
            String content,
            LocalDateTime now
    ) {
        CommunityComment comment = new CommunityComment();
        comment.post = post;
        comment.user = user;
        comment.parent = parent;
        comment.content = content.trim();
        comment.status = CommunityContentStatus.ACTIVE;
        comment.createdAt = now;
        comment.updatedAt = now;
        return comment;
    }

    public boolean isReply() {
        return parent != null;
    }

    public boolean isDeleted() {
        return status == CommunityContentStatus.DELETED;
    }

    public void update(String content, LocalDateTime now) {
        this.content = content.trim();
        this.updatedAt = now;
    }

    public void delete(LocalDateTime now) {
        this.status = CommunityContentStatus.DELETED;
        this.updatedAt = now;
    }
}
