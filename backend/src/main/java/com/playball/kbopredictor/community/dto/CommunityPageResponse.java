package com.playball.kbopredictor.community.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record CommunityPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> CommunityPageResponse<T> from(Page<T> page) {
        return new CommunityPageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
