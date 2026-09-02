package com.playball.kbopredictor.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CommunityCommentRequest(
        @NotBlank(message = "댓글을 입력해 주세요.")
        @Size(max = 1000, message = "댓글은 1,000자 이하로 입력해 주세요.")
        String content,

        @Positive(message = "부모 댓글 ID는 양수여야 합니다.")
        Long parentCommentId
) {
    public CommunityCommentRequest(String content) {
        this(content, null);
    }
}
