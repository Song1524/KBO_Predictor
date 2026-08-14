package com.playball.kbopredictor.stats.service;

import com.playball.kbopredictor.stats.dto.TeamStandingResponse;
import com.playball.kbopredictor.stats.entity.TeamStat;
import com.playball.kbopredictor.stats.repository.TeamStatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StandingService {

    static final int KBO_TEAM_COUNT = 10;

    private final TeamStatRepository teamStatRepository;

    public List<TeamStandingResponse> getCurrentStandings() {
        List<LocalDate> snapshotDates =
                teamStatRepository.findCompleteStandingSnapshotDates(
                        KBO_TEAM_COUNT,
                        PageRequest.of(0, 1)
                );
        if (snapshotDates.isEmpty()) {
            return List.of();
        }

        List<TeamStat> snapshot = teamStatRepository
                .findByStatDateAndOfficialRankIsNotNullOrderByOfficialRankAsc(
                        snapshotDates.getFirst()
                ).stream()
                .sorted(Comparator.comparingInt(TeamStat::getOfficialRank))
                .toList();
        validateCompleteSnapshot(snapshot);
        return snapshot.stream()
                .map(TeamStandingResponse::from)
                .toList();
    }

    private void validateCompleteSnapshot(List<TeamStat> snapshot) {
        if (snapshot.size() != KBO_TEAM_COUNT) {
            throw new IllegalStateException(
                    "완전한 KBO 순위 snapshot을 조회하지 못했습니다."
            );
        }
        int previousRank = 0;
        for (TeamStat stat : snapshot) {
            if (stat.getOfficialRank() == null
                    || stat.getOfficialRank() < 1
                    || stat.getOfficialRank() > KBO_TEAM_COUNT
                    || stat.getOfficialRank() < previousRank) {
                throw new IllegalStateException(
                        "KBO 공식 순위가 유효한 오름차순으로 저장되지 않았습니다."
                );
            }
            previousRank = stat.getOfficialRank();
            if (stat.getGamesPlayed() == null
                    || stat.getWins() == null
                    || stat.getLosses() == null
                    || stat.getDraws() == null
                    || stat.getGamesPlayed()
                    != stat.getWins() + stat.getLosses() + stat.getDraws()) {
                throw new IllegalStateException(
                        "KBO 순위의 경기 수와 승/패/무 합계가 일치하지 않습니다."
                );
            }
        }
    }
}
