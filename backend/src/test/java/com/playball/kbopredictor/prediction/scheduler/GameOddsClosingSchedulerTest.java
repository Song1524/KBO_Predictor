package com.playball.kbopredictor.prediction.scheduler;

import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.service.GameOddsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameOddsClosingSchedulerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 8, 10, 12, 0);

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameOddsService gameOddsService;

    private GameOddsClosingScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        scheduler = new GameOddsClosingScheduler(
                gameRepository,
                gameOddsService,
                clock
        );
        ReflectionTestUtils.setField(scheduler, "batchSize", 50);
    }

    @Test
    void processesExpiredUnfinalizedGamesAfterServerRestart() {
        when(gameRepository.findIdsPendingOddsFinalization(
                NOW,
                PageRequest.of(0, 50)
        )).thenReturn(List.of(10L, 20L));
        when(gameOddsService.finalizeExpiredGame(10L)).thenReturn(true);
        when(gameOddsService.finalizeExpiredGame(20L)).thenReturn(true);

        scheduler.finalizeExpiredOdds();

        verify(gameOddsService).finalizeExpiredGame(10L);
        verify(gameOddsService).finalizeExpiredGame(20L);
    }

    @Test
    void delegatesRepeatedCandidateSafelyToIdempotentFinalizer() {
        when(gameRepository.findIdsPendingOddsFinalization(
                NOW,
                PageRequest.of(0, 50)
        )).thenReturn(List.of(10L));
        when(gameOddsService.finalizeExpiredGame(10L))
                .thenReturn(true, false);

        scheduler.finalizeExpiredOdds();
        scheduler.finalizeExpiredOdds();

        verify(gameOddsService, org.mockito.Mockito.times(2))
                .finalizeExpiredGame(10L);
    }
}
