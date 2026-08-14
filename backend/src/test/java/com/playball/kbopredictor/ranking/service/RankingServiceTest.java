package com.playball.kbopredictor.ranking.service;

import com.playball.kbopredictor.ranking.RankingType;
import com.playball.kbopredictor.ranking.dto.RankingResponse;
import com.playball.kbopredictor.ranking.repository.RankingQueryRepository;
import com.playball.kbopredictor.ranking.repository.RankingQueryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final RankingPeriod AUGUST = new RankingPeriod(
            ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, KOREA),
            ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, KOREA)
    );

    @Mock
    private RankingQueryRepository repository;
    @Mock
    private RankingPeriodCalculator periodCalculator;

    private RankingService service;

    @BeforeEach
    void setUp() {
        service = new RankingService(repository, periodCalculator);
    }

    @Test
    void totalRankingReturnsCurrentPointAndNullableHitRateForNoPredictions() {
        when(repository.findTotalTop(20)).thenReturn(List.of(
                row(1, 1, "선두", 2000, 8, 5, 7),
                row(2, 2, "신규", 1000, 0, 0, 0)
        ));

        RankingResponse response = service.getRankings(
                RankingType.TOTAL_POINT, 20, null
        );

        assertThat(response.rankings()).hasSize(2);
        assertThat(response.rankings().getFirst().currentPoint())
                .isEqualTo(2000);
        assertThat(response.rankings().getFirst().periodProfit()).isNull();
        assertThat(response.rankings().getFirst().hitRate())
                .isEqualByComparingTo("71.4");
        assertThat(response.rankings().get(1).hitRate()).isNull();
        assertThat(response.periodStart()).isNull();
    }

    @Test
    void userInsideTopUsesSameRowWithoutAnotherDatabaseQuery() {
        RankingQueryRow mine = row(7, 2, "내닉네임", 1300, 6, 3, 6);
        when(repository.findTotalTop(20)).thenReturn(List.of(mine));

        RankingResponse response = service.getRankings(
                RankingType.TOTAL_POINT, 20, 2L
        );

        assertThat(response.myRanking().rank()).isEqualTo(7);
        verify(repository, never()).findTotalByUserId(2L);
    }

    @Test
    void userOutsideTopIsFetchedWithItsGlobalRank() {
        when(repository.findTotalTop(20)).thenReturn(List.of(
                row(1, 1, "선두", 2000, 8, 5, 7)
        ));
        when(repository.findTotalByUserId(37L)).thenReturn(Optional.of(
                row(37, 37, "내닉네임", 700, 2, 1, 2)
        ));

        RankingResponse response = service.getRankings(
                RankingType.TOTAL_POINT, 20, 37L
        );

        assertThat(response.myRanking().rank()).isEqualTo(37);
        assertThat(response.myRanking().userId()).isEqualTo(37);
    }

    @Test
    void anonymousRequestDoesNotQueryMyRanking() {
        when(repository.findTotalTop(20)).thenReturn(List.of());

        RankingResponse response = service.getRankings(
                RankingType.TOTAL_POINT, 20, null
        );

        assertThat(response.myRanking()).isNull();
        verify(repository, never()).findTotalByUserId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void monthlyRankingUsesSeoulPeriodAndPeriodProfit() {
        when(periodCalculator.current(RankingType.MONTHLY_PROFIT))
                .thenReturn(AUGUST);
        when(repository.findPeriodTop(
                AUGUST.start().toLocalDateTime(),
                AUGUST.endExclusive().toLocalDateTime(),
                20
        )).thenReturn(List.of(
                row(1, 5, "월간왕", 300, 2, 1, 2)
        ));

        RankingResponse response = service.getRankings(
                RankingType.MONTHLY_PROFIT, 20, null
        );

        assertThat(response.rankings().getFirst().currentPoint()).isNull();
        assertThat(response.rankings().getFirst().periodProfit())
                .isEqualTo(300);
        assertThat(response.periodStart()).isEqualTo(AUGUST.start());
        assertThat(response.periodEndExclusive())
                .isEqualTo(AUGUST.endExclusive());
    }

    @Test
    void rejectsAnUnboundedLimit() {
        assertThatThrownBy(() -> service.getRankings(
                RankingType.TOTAL_POINT, 101, null
        )).hasMessageContaining("limit은 1 이상 100 이하");
    }

    private RankingQueryRow row(
            long rank,
            long userId,
            String nickname,
            long score,
            long predictionCount,
            long correctCount,
            long gradedCount
    ) {
        return new RankingQueryRow(
                rank,
                userId,
                nickname,
                score,
                predictionCount,
                correctCount,
                gradedCount
        );
    }
}
