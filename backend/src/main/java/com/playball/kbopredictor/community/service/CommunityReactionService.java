package com.playball.kbopredictor.community.service;

import com.playball.kbopredictor.community.dto.CommunityReactionResponse;
import com.playball.kbopredictor.community.entity.CommunityComment;
import com.playball.kbopredictor.community.entity.CommunityCommentReaction;
import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import com.playball.kbopredictor.community.entity.CommunityPost;
import com.playball.kbopredictor.community.entity.CommunityPostReaction;
import com.playball.kbopredictor.community.entity.CommunityReactionType;
import com.playball.kbopredictor.community.repository.CommunityCommentReactionRepository;
import com.playball.kbopredictor.community.repository.CommunityCommentRepository;
import com.playball.kbopredictor.community.repository.CommunityPostReactionRepository;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityReactionService {

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityPostReactionRepository postReactionRepository;
    private final CommunityCommentReactionRepository commentReactionRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public CommunityReactionResponse togglePostReaction(
            Long authenticatedUserId,
            Long postId,
            CommunityReactionType requestedReaction
    ) {
        CommunityPost post = postRepository.findByIdAndStatusForUpdate(
                postId,
                CommunityContentStatus.ACTIVE
        ).orElseThrow(() -> notFound("게시글을 찾을 수 없습니다."));
        requireNotOwner(post.getUser(), authenticatedUserId, "게시글");

        User user = user(authenticatedUserId);
        postReactionRepository.findByPostIdAndUserId(postId, authenticatedUserId)
                .ifPresentOrElse(
                        existing -> {
                            if (existing.getReactionType() == requestedReaction) {
                                postReactionRepository.delete(existing);
                            } else {
                                existing.changeTo(requestedReaction, now());
                            }
                        },
                        () -> postReactionRepository.save(
                                CommunityPostReaction.create(
                                        post,
                                        user,
                                        requestedReaction,
                                        now()
                                )
                        )
                );
        postReactionRepository.flush();
        return getPostReaction(postId, authenticatedUserId);
    }

    @Transactional
    public CommunityReactionResponse toggleCommentReaction(
            Long authenticatedUserId,
            Long commentId,
            CommunityReactionType requestedReaction
    ) {
        CommunityComment comment = commentRepository.findByIdAndStatusForUpdate(
                commentId,
                CommunityContentStatus.ACTIVE
        ).orElseThrow(() -> notFound("댓글을 찾을 수 없습니다."));
        requireNotOwner(comment.getUser(), authenticatedUserId, "댓글");

        User user = user(authenticatedUserId);
        commentReactionRepository
                .findByCommentIdAndUserId(commentId, authenticatedUserId)
                .ifPresentOrElse(
                        existing -> {
                            if (existing.getReactionType() == requestedReaction) {
                                commentReactionRepository.delete(existing);
                            } else {
                                existing.changeTo(requestedReaction, now());
                            }
                        },
                        () -> commentReactionRepository.save(
                                CommunityCommentReaction.create(
                                        comment,
                                        user,
                                        requestedReaction,
                                        now()
                                )
                        )
                );
        commentReactionRepository.flush();
        return getCommentReaction(commentId, authenticatedUserId);
    }

    public CommunityReactionResponse getPostReaction(
            Long postId,
            Long viewerUserId
    ) {
        return getPostReactions(List.of(postId), viewerUserId)
                .getOrDefault(postId, CommunityReactionResponse.empty());
    }

    public Map<Long, CommunityReactionResponse> getPostReactions(
            Collection<Long> postIds,
            Long viewerUserId
    ) {
        if (postIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, ReactionCounts> counts = new LinkedHashMap<>();
        postReactionRepository.countByPostIds(postIds).forEach(row ->
                counts.computeIfAbsent(
                        row.getTargetId(),
                        ignored -> new ReactionCounts()
                ).add(row.getReactionType(), row.getReactionCount())
        );
        Map<Long, CommunityReactionType> mine = new LinkedHashMap<>();
        if (viewerUserId != null) {
            postReactionRepository
                    .findByPostIdInAndUserId(postIds, viewerUserId)
                    .forEach(reaction -> mine.put(
                            reaction.getPost().getId(),
                            reaction.getReactionType()
                    ));
        }
        return responses(postIds, counts, mine);
    }

    public CommunityReactionResponse getCommentReaction(
            Long commentId,
            Long viewerUserId
    ) {
        return getCommentReactions(List.of(commentId), viewerUserId)
                .getOrDefault(commentId, CommunityReactionResponse.empty());
    }

    public Map<Long, CommunityReactionResponse> getCommentReactions(
            Collection<Long> commentIds,
            Long viewerUserId
    ) {
        if (commentIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, ReactionCounts> counts = new LinkedHashMap<>();
        commentReactionRepository.countByCommentIds(commentIds).forEach(row ->
                counts.computeIfAbsent(
                        row.getTargetId(),
                        ignored -> new ReactionCounts()
                ).add(row.getReactionType(), row.getReactionCount())
        );
        Map<Long, CommunityReactionType> mine = new LinkedHashMap<>();
        if (viewerUserId != null) {
            commentReactionRepository
                    .findByCommentIdInAndUserId(commentIds, viewerUserId)
                    .forEach(reaction -> mine.put(
                            reaction.getComment().getId(),
                            reaction.getReactionType()
                    ));
        }
        return responses(commentIds, counts, mine);
    }

    private Map<Long, CommunityReactionResponse> responses(
            Collection<Long> targetIds,
            Map<Long, ReactionCounts> counts,
            Map<Long, CommunityReactionType> mine
    ) {
        Map<Long, CommunityReactionResponse> responses = new LinkedHashMap<>();
        targetIds.stream().distinct().forEach(targetId -> {
            ReactionCounts targetCounts = counts.getOrDefault(
                    targetId,
                    new ReactionCounts()
            );
            responses.put(targetId, new CommunityReactionResponse(
                    targetCounts.likeCount,
                    targetCounts.dislikeCount,
                    mine.get(targetId)
            ));
        });
        return Map.copyOf(responses);
    }

    private void requireNotOwner(
            User owner,
            Long authenticatedUserId,
            String resourceName
    ) {
        if (owner.getId().equals(authenticatedUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 " + resourceName + "에는 반응할 수 없습니다."
            );
        }
    }

    private User user(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> notFound("사용자를 찾을 수 없습니다."));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static final class ReactionCounts {
        private long likeCount;
        private long dislikeCount;

        private void add(CommunityReactionType type, long count) {
            if (type == CommunityReactionType.LIKE) {
                likeCount = count;
            } else {
                dislikeCount = count;
            }
        }
    }
}
