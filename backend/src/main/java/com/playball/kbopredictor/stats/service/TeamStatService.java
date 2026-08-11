package com.playball.kbopredictor.stats.service;

import com.playball.kbopredictor.stats.dto.TeamStatResponse;
import com.playball.kbopredictor.stats.entity.TeamStat;
import com.playball.kbopredictor.stats.repository.TeamStatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamStatService {

    private final TeamStatRepository teamStatRepository;

    public TeamStatResponse getLatestTeamStat(Long teamId) {
        TeamStat teamStat = teamStatRepository
                .findTopByTeamIdOrderByStatDateDesc(teamId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "해당 팀의 기록을 찾을 수 없습니다."
                ));

        return TeamStatResponse.from(teamStat);
    }
}