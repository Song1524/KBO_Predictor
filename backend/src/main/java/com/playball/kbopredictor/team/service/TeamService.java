package com.playball.kbopredictor.team.service;

import com.playball.kbopredictor.team.dto.TeamResponse;
import com.playball.kbopredictor.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamService {

    private final TeamRepository teamRepository;

    public List<TeamResponse> getTeams() {
        return teamRepository.findAllByOrderByIdAsc()
                .stream()
                .map(TeamResponse::from)
                .toList();
    }
}