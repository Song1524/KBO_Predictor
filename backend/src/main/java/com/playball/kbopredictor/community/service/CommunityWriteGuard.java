package com.playball.kbopredictor.community.service;

import com.playball.kbopredictor.community.entity.CommunityComment;
import com.playball.kbopredictor.community.entity.CommunityPost;
import com.playball.kbopredictor.community.exception.CommunityRateLimitException;
import com.playball.kbopredictor.community.repository.CommunityCommentRepository;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CommunityWriteGuard {

    static final Duration POST_COOLDOWN = Duration.ofSeconds(30);
    static final Duration COMMENT_COOLDOWN = Duration.ofSeconds(5);
    static final Duration POST_DUPLICATE_WINDOW = Duration.ofMinutes(10);
    static final Duration COMMENT_DUPLICATE_WINDOW = Duration.ofMinutes(1);

    private static final int MAX_RECENT_POSTS = 100;
    private static final int MAX_RECENT_COMMENTS = 100;
    private static final String RATE_LIMIT_MESSAGE =
            "너무 빠르게 작성하고 있습니다.";

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final boolean enabled;

    public CommunityWriteGuard(
            CommunityPostRepository postRepository,
            CommunityCommentRepository commentRepository,
            UserRepository userRepository,
            @Value("${app.community.write-rate-limit.enabled:true}")
            boolean enabled
    ) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.enabled = enabled;
    }

    public User lockAndValidatePost(
            Long userId,
            String title,
            String content,
            LocalDateTime now
    ) {
        User author = lockUser(userId);
        if (!enabled) {
            return author;
        }

        List<CommunityPost> recentPosts = postRepository.findRecentByUserId(
                userId,
                now.minus(POST_DUPLICATE_WINDOW),
                PageRequest.of(0, MAX_RECENT_POSTS)
        );
        if (!recentPosts.isEmpty()) {
            requireCooldownElapsed(
                    recentPosts.getFirst().getCreatedAt(),
                    POST_COOLDOWN,
                    now
            );
        }

        String normalizedTitle = normalize(title);
        String normalizedContent = normalize(content);
        recentPosts.stream()
                .filter(post -> normalize(post.getTitle()).equals(normalizedTitle))
                .filter(post -> normalize(post.getContent()).equals(normalizedContent))
                .findFirst()
                .ifPresent(post -> rejectUntil(
                        post.getCreatedAt().plus(POST_DUPLICATE_WINDOW),
                        now
                ));
        return author;
    }

    public User lockAndValidateComment(
            Long userId,
            String content,
            LocalDateTime now
    ) {
        User author = lockUser(userId);
        if (!enabled) {
            return author;
        }

        List<CommunityComment> recentComments =
                commentRepository.findRecentByUserId(
                        userId,
                        now.minus(COMMENT_DUPLICATE_WINDOW),
                        PageRequest.of(0, MAX_RECENT_COMMENTS)
                );
        if (!recentComments.isEmpty()) {
            requireCooldownElapsed(
                    recentComments.getFirst().getCreatedAt(),
                    COMMENT_COOLDOWN,
                    now
            );
        }

        String normalizedContent = normalize(content);
        recentComments.stream()
                .filter(comment -> normalize(comment.getContent())
                        .equals(normalizedContent))
                .findFirst()
                .ifPresent(comment -> rejectUntil(
                        comment.getCreatedAt().plus(COMMENT_DUPLICATE_WINDOW),
                        now
                ));
        return author;
    }

    private User lockUser(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));
    }

    private void requireCooldownElapsed(
            LocalDateTime createdAt,
            Duration cooldown,
            LocalDateTime now
    ) {
        rejectUntil(createdAt.plus(cooldown), now);
    }

    private void rejectUntil(
            LocalDateTime allowedAt,
            LocalDateTime now
    ) {
        if (!now.isBefore(allowedAt)) {
            return;
        }
        Duration remaining = Duration.between(now, allowedAt);
        long retryAfterSeconds = Math.max(
                1L,
                Math.ceilDiv(remaining.toNanos(), 1_000_000_000L)
        );
        throw new CommunityRateLimitException(
                RATE_LIMIT_MESSAGE,
                retryAfterSeconds
        );
    }

    private String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
