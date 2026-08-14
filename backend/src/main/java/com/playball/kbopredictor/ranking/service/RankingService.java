package com.playball.kbopredictor.ranking.service;

import com.playball.kbopredictor.ranking.RankingType;
import com.playball.kbopredictor.ranking.dto.RankingEntryResponse;
import com.playball.kbopredictor.ranking.dto.RankingResponse;
import com.playball.kbopredictor.ranking.repository.RankingQueryRepository;
import com.playball.kbopredictor.ranking.repository.RankingQueryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RankingService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final RankingQueryRepository rankingQueryRepository;
    private final RankingPeriodCalculator periodCalculator;

    public RankingResponse getRankings(
            RankingType type,
            int limit,
            Long authenticatedUserId
    ) {
        validateLimit(limit);

        RankingPeriod period = type.isPeriodRanking()
                ? periodCalculator.current(type)
                : null;
        List<RankingQueryRow> rows = findTop(type, period, limit);
        List<RankingEntryResponse> rankings = rows.stream()
                .map(row -> RankingEntryResponse.from(row, type))
                .toList();
        RankingEntryResponse myRanking = findMyRanking(
                type,
                period,
                authenticatedUserId,
                rows
        ).map(row -> RankingEntryResponse.from(row, type)).orElse(null);

        return new RankingResponse(
                type,
                period == null ? null : period.start(),
                period == null ? null : period.endExclusive(),
                rankings,
                myRanking
        );
    }

    private List<RankingQueryRow> findTop(
            RankingType type,
            RankingPeriod period,
            int limit
    ) {
        if (type == RankingType.TOTAL_POINT) {
            return rankingQueryRepository.findTotalTop(limit);
        }
        return rankingQueryRepository.findPeriodTop(
                local(period.start()),
                local(period.endExclusive()),
                limit
        );
    }

    private Optional<RankingQueryRow> findMyRanking(
            RankingType type,
            RankingPeriod period,
            Long authenticatedUserId,
            List<RankingQueryRow> topRows
    ) {
        if (authenticatedUserId == null) return Optional.empty();

        Optional<RankingQueryRow> inTop = topRows.stream()
                .filter(row -> row.userId() == authenticatedUserId)
                .findFirst();
        if (inTop.isPresent()) return inTop;

        if (type == RankingType.TOTAL_POINT) {
            return rankingQueryRepository.findTotalByUserId(
                    authenticatedUserId
            );
        }
        return rankingQueryRepository.findPeriodByUserId(
                local(period.start()),
                local(period.endExclusive()),
                authenticatedUserId
        );
    }

    private LocalDateTime local(java.time.ZonedDateTime value) {
        return value.toLocalDateTime();
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit은 1 이상 100 이하여야 합니다."
            );
        }
    }
}
