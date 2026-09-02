package com.playball.kbopredictor.auth.dto;

public record DailyLoginBonusResult(
        boolean granted,
        int points
) {

    public static DailyLoginBonusResult granted(int points) {
        return new DailyLoginBonusResult(true, points);
    }

    public static DailyLoginBonusResult notGranted() {
        return new DailyLoginBonusResult(false, 0);
    }
}
