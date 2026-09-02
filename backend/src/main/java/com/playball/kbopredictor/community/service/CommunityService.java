package com.playball.kbopredictor.community.service;

import com.playball.kbopredictor.community.dto.CommunityCommentRequest;
import com.playball.kbopredictor.community.dto.CommunityCommentResponse;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.dto.CommunityPostListItemResponse;
import com.playball.kbopredictor.community.dto.CommunityPostRequest;
import com.playball.kbopredictor.community.dto.CommunityPostResponse;
import com.playball.kbopredictor.community.entity.CommunityComment;
import com.playball.kbopredictor.community.entity.CommunityContentStatus;
import com.playball.kbopredictor.community.entity.CommunityPost;
import com.playball.kbopredictor.community.repository.CommunityCommentRepository;
import com.playball.kbopredictor.community.repository.CommunityPostRepository;
import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityService {

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public CommunityPageResponse<CommunityPostListItemResponse> getPosts(
            int page,
            int size
    ) {
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );
        Page<CommunityPost> posts = postRepository.findByStatus(
                CommunityContentStatus.ACTIVE,
                pageable
        );

        List<Long> postIds = posts.getContent().stream()
                .map(CommunityPost::getId)
                .toList();
        Map<Long, Long> commentCounts = postIds.isEmpty()
                ? Map.of()
                : commentRepository.countByPostIdsAndStatus(
                                postIds,
                                CommunityContentStatus.ACTIVE
                        ).stream()
                        .collect(Collectors.toMap(
                                CommunityCommentRepository.CommentCount::getPostId,
                                CommunityCommentRepository.CommentCount::getCommentCount
                        ));

        return CommunityPageResponse.from(posts.map(post ->
                CommunityPostListItemResponse.from(
                        post,
                        commentCounts.getOrDefault(post.getId(), 0L)
                )
        ));
    }

    @Transactional
    public CommunityPostResponse getPost(Long postId) {
        CommunityPost post = activePost(postId);
        post.incrementViewCount();
        return postResponse(post);
    }

    @Transactional
    public CommunityPostResponse createPost(
            Long authenticatedUserId,
            CommunityPostRequest request
    ) {
        User author = user(authenticatedUserId);
        CommunityPost post = postRepository.save(CommunityPost.create(
                author,
                request.title(),
                request.content(),
                now()
        ));
        return CommunityPostResponse.from(post, 0);
    }

    @Transactional
    public CommunityPostResponse updatePost(
            Long authenticatedUserId,
            Long postId,
            CommunityPostRequest request
    ) {
        CommunityPost post = activePost(postId);
        if (!post.getUser().getId().equals(authenticatedUserId)) {
            throw forbidden("본인이 작성한 게시글만 수정할 수 있습니다.");
        }
        post.update(request.title(), request.content(), now());
        return postResponse(post);
    }

    @Transactional
    public void deletePost(Long authenticatedUserId, Long postId) {
        CommunityPost post = activePost(postId);
        User actor = user(authenticatedUserId);
        requireOwnerOrAdmin(post.getUser(), actor, "게시글");
        post.delete(now());
    }

    public List<CommunityCommentResponse> getComments(Long postId) {
        activePost(postId);
        return commentRepository
                .findByPostIdAndStatusOrderByCreatedAtAscIdAsc(
                        postId,
                        CommunityContentStatus.ACTIVE
                )
                .stream()
                .map(CommunityCommentResponse::from)
                .toList();
    }

    @Transactional
    public CommunityCommentResponse createComment(
            Long authenticatedUserId,
            Long postId,
            CommunityCommentRequest request
    ) {
        CommunityPost post = activePost(postId);
        User author = user(authenticatedUserId);
        CommunityComment comment = commentRepository.save(
                CommunityComment.create(
                        post,
                        author,
                        request.content(),
                        now()
                )
        );
        return CommunityCommentResponse.from(comment);
    }

    @Transactional
    public void deleteComment(Long authenticatedUserId, Long commentId) {
        CommunityComment comment = commentRepository
                .findByIdAndStatus(
                        commentId,
                        CommunityContentStatus.ACTIVE
                )
                .orElseThrow(() -> notFound("댓글을 찾을 수 없습니다."));
        User actor = user(authenticatedUserId);
        requireOwnerOrAdmin(comment.getUser(), actor, "댓글");
        comment.delete(now());
    }

    private CommunityPostResponse postResponse(CommunityPost post) {
        long commentCount = commentRepository.countByPostIdAndStatus(
                post.getId(),
                CommunityContentStatus.ACTIVE
        );
        return CommunityPostResponse.from(post, commentCount);
    }

    private CommunityPost activePost(Long postId) {
        return postRepository.findByIdAndStatus(
                postId,
                CommunityContentStatus.ACTIVE
        ).orElseThrow(() -> notFound("게시글을 찾을 수 없습니다."));
    }

    private User user(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> notFound("사용자를 찾을 수 없습니다."));
    }

    private void requireOwnerOrAdmin(
            User owner,
            User actor,
            String resourceName
    ) {
        if (owner.getId().equals(actor.getId()) || isAdmin(actor)) {
            return;
        }
        throw forbidden(
                "본인이 작성한 " + resourceName + "만 삭제할 수 있습니다."
        );
    }

    private boolean isAdmin(User user) {
        String role = user.getRole();
        return "ADMIN".equalsIgnoreCase(role)
                || "ROLE_ADMIN".equalsIgnoreCase(role);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
