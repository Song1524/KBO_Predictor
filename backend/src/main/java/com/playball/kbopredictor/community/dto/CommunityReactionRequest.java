package com.playball.kbopredictor.community.dto;

import com.playball.kbopredictor.community.entity.CommunityReactionType;
import jakarta.validation.constraints.NotNull;

public record CommunityReactionRequest(
        @NotNull(message = "추천 또는 비추천을 선택해 주세요.")
        CommunityReactionType reaction
) {
}
