package com.playball.kbopredictor.stats.service;

import com.playball.kbopredictor.stats.entity.TeamRecentFormValues;

public record TeamRecentForm(
        int recent10Wins,
        int recent10Losses,
        int recent10Draws,
        TeamRecentFormValues values
) {
}
