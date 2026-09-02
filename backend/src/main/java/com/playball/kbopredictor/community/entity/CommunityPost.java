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
@Table(name = "community_posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityContentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static CommunityPost create(
            User user,
            String title,
            String content,
            LocalDateTime now
    ) {
        CommunityPost post = new CommunityPost();
        post.user = user;
        post.title = title.trim();
        post.content = content.trim();
        post.viewCount = 0;
        post.status = CommunityContentStatus.ACTIVE;
        post.createdAt = now;
        post.updatedAt = now;
        return post;
    }

    public void update(String title, String content, LocalDateTime now) {
        this.title = title.trim();
        this.content = content.trim();
        this.updatedAt = now;
    }

    public void incrementViewCount() {
        this.viewCount = Math.addExact(this.viewCount, 1L);
    }

    public void delete(LocalDateTime now) {
        this.status = CommunityContentStatus.DELETED;
        this.updatedAt = now;
    }
}
