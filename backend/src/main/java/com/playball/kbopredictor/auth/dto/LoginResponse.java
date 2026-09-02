package com.playball.kbopredictor.auth.dto;

import com.playball.kbopredictor.user.dto.UserResponse;

public record LoginResponse(
        Long id,
        String email,
        String nickname,
        Long favoriteTeamId,
        String favoriteTeamName,
        Integer point,
        String role,
        String status,
        boolean dailyLoginBonusGranted,
        int dailyLoginBonusPoints
) {

    public static LoginResponse from(
            UserResponse user,
            DailyLoginBonusResult bonus
    ) {
        return new LoginResponse(
                user.id(),
                user.email(),
                user.nickname(),
                user.favoriteTeamId(),
                user.favoriteTeamName(),
                user.point(),
                user.role(),
                user.status(),
                bonus.granted(),
                bonus.points()
        );
    }
}
