package com.playball.kbopredictor.prediction.entity;

import com.playball.kbopredictor.game.entity.Game;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "game_odds",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_game_odds_game",
                columnNames = "game_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameOdds {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false, unique = true)
    private Game game;

    @Column(name = "home_win_points", nullable = false)
    private long homeWinPoints;

    @Column(name = "draw_points", nullable = false)
    private long drawPoints;

    @Column(name = "away_win_points", nullable = false)
    private long awayWinPoints;

    @Column(name = "final_home_win_odds", precision = 8, scale = 2)
    private BigDecimal finalHomeWinOdds;

    @Column(name = "final_draw_odds", precision = 8, scale = 2)
    private BigDecimal finalDrawOdds;

    @Column(name = "final_away_win_odds", precision = 8, scale = 2)
    private BigDecimal finalAwayWinOdds;

    @Column(nullable = false)
    private boolean finalized;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static GameOdds create(Game game, LocalDateTime now) {
        GameOdds odds = new GameOdds();
        odds.game = game;
        odds.createdAt = now;
        odds.updatedAt = now;
        return odds;
    }

    public void addBet(
            PredictionOutcome outcome,
            long pointAmount,
            LocalDateTime now
    ) {
        if (finalized) {
            throw new IllegalStateException("이미 최종 배당이 확정되었습니다.");
        }

        switch (outcome) {
            case HOME_WIN -> homeWinPoints += pointAmount;
            case DRAW -> drawPoints += pointAmount;
            case AWAY_WIN -> awayWinPoints += pointAmount;
        }
        updatedAt = now;
    }

    public long getTotalBetPoints() {
        return homeWinPoints + drawPoints + awayWinPoints;
    }

    public long getBetPoints(PredictionOutcome outcome) {
        return switch (outcome) {
            case HOME_WIN -> homeWinPoints;
            case DRAW -> drawPoints;
            case AWAY_WIN -> awayWinPoints;
        };
    }

    public void finalizeOdds(
            BigDecimal homeWinOdds,
            BigDecimal drawOdds,
            BigDecimal awayWinOdds,
            LocalDateTime now
    ) {
        if (finalized) {
            return;
        }

        finalHomeWinOdds = homeWinOdds;
        finalDrawOdds = drawOdds;
        finalAwayWinOdds = awayWinOdds;
        finalized = true;
        finalizedAt = now;
        updatedAt = now;
    }

    public BigDecimal getFinalOdds(PredictionOutcome outcome) {
        if (!finalized) {
            throw new IllegalStateException("최종 배당이 아직 확정되지 않았습니다.");
        }

        return switch (outcome) {
            case HOME_WIN -> finalHomeWinOdds;
            case DRAW -> finalDrawOdds;
            case AWAY_WIN -> finalAwayWinOdds;
        };
    }
}
