package com.playball.kbopredictor.user.dto;

import com.playball.kbopredictor.user.entity.User;

public record UserResponse(
        Long id,
        String email,
        String nickname,

        Long favoriteTeamId,
        String favoriteTeamName,

        Integer point,
        String role,
        String status
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),

                user.getFavoriteTeam() == null
                        ? null
                        : user.getFavoriteTeam().getId(),

                user.getFavoriteTeam() == null
                        ? null
                        : user.getFavoriteTeam().getName(),

                user.getPoint(),
                user.getRole(),
                user.getStatus()
        );
    }
}