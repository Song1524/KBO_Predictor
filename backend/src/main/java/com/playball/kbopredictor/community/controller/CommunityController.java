package com.playball.kbopredictor.community.controller;

import com.playball.kbopredictor.auth.security.AuthenticatedUser;
import com.playball.kbopredictor.community.dto.CommunityCommentRequest;
import com.playball.kbopredictor.community.dto.CommunityCommentResponse;
import com.playball.kbopredictor.community.dto.CommunityCommentUpdateRequest;
import com.playball.kbopredictor.community.dto.CommunityPageResponse;
import com.playball.kbopredictor.community.dto.CommunityPostListItemResponse;
import com.playball.kbopredictor.community.dto.CommunityPostRequest;
import com.playball.kbopredictor.community.dto.CommunityPostResponse;
import com.playball.kbopredictor.community.service.CommunityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/community")
public class CommunityController {

    private final CommunityService communityService;

    @GetMapping("/posts")
    public ResponseEntity<CommunityPageResponse<CommunityPostListItemResponse>>
    getPosts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "15") @Min(1) @Max(50) int size
    ) {
        return ResponseEntity.ok(communityService.getPosts(page, size));
    }

    @GetMapping("/posts/{postId}")
    public ResponseEntity<CommunityPostResponse> getPost(
            @PathVariable @Positive Long postId
    ) {
        return ResponseEntity.ok(communityService.getPost(postId));
    }

    @PostMapping("/posts")
    public ResponseEntity<CommunityPostResponse> createPost(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody CommunityPostRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                communityService.createPost(
                        authenticatedUser.getUserId(),
                        request
                )
        );
    }

    @PutMapping("/posts/{postId}")
    public ResponseEntity<CommunityPostResponse> updatePost(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable @Positive Long postId,
            @Valid @RequestBody CommunityPostRequest request
    ) {
        return ResponseEntity.ok(communityService.updatePost(
                authenticatedUser.getUserId(),
                postId,
                request
        ));
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<Void> deletePost(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable @Positive Long postId
    ) {
        communityService.deletePost(
                authenticatedUser.getUserId(),
                postId
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<List<CommunityCommentResponse>> getComments(
            @PathVariable @Positive Long postId
    ) {
        return ResponseEntity.ok(communityService.getComments(postId));
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommunityCommentResponse> createComment(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable @Positive Long postId,
            @Valid @RequestBody CommunityCommentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                communityService.createComment(
                        authenticatedUser.getUserId(),
                        postId,
                        request
                )
        );
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable @Positive Long commentId
    ) {
        communityService.deleteComment(
                authenticatedUser.getUserId(),
                commentId
        );
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/comments/{commentId}")
    public ResponseEntity<CommunityCommentResponse> updateComment(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable @Positive Long commentId,
            @Valid @RequestBody CommunityCommentUpdateRequest request
    ) {
        return ResponseEntity.ok(communityService.updateComment(
                authenticatedUser.getUserId(),
                commentId,
                request
        ));
    }
}
