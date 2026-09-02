package com.playball.kbopredictor.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommunityPostRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 120, message = "제목은 120자 이하로 입력해 주세요.")
        String title,

        @NotBlank(message = "본문을 입력해 주세요.")
        @Size(max = 5000, message = "본문은 5,000자 이하로 입력해 주세요.")
        String content
) {
}
