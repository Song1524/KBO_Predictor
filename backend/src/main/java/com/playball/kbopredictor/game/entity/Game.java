package com.playball.kbopredictor.game.entity;

import com.playball.kbopredictor.team.entity.Team;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(name = "games")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_game_id", length = 30, unique = true)
    private String externalGameId;

    private Integer season;

    @Column(name = "game_date")
    private LocalDate gameDate;

    @Column(name = "game_time")
    private LocalTime gameTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_team_id")
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "away_team_id")
    private Team awayTeam;

    @Column(length = 100)
    private String stadium;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private GameStatus status;

    @Column(name = "home_score")
    private Integer homeScore;

    @Column(name = "away_score")
    private Integer awayScore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_team_id")
    private Team winnerTeam;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GameResult result;

    @Column(name = "prediction_close_at")
    private LocalDateTime predictionCloseAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public LocalDateTime getPredictionCloseAt() {
        if (predictionCloseAt != null) {
            return predictionCloseAt;
        }
        if (gameDate != null && gameTime != null) {
            return LocalDateTime.of(gameDate, gameTime).minusMinutes(30);
        }
        return null;
    }

    public static Game createCollected(
            String externalGameId,
            Integer season,
            LocalDate gameDate,
            LocalTime gameTime,
            Team homeTeam,
            Team awayTeam,
            String stadium,
            GameStatus status,
            Integer homeScore,
            Integer awayScore,
            Team winnerTeam,
            GameResult result,
            String cancelReason,
            LocalDateTime now
    ) {
        Game game = new Game();
        game.createdAt = now;
        game.updateCollected(
                externalGameId,
                season,
                gameDate,
                gameTime,
                homeTeam,
                awayTeam,
                stadium,
                status,
                homeScore,
                awayScore,
                winnerTeam,
                result,
                cancelReason,
                now
        );
        return game;
    }

    public void updateCollected(
            String externalGameId,
            Integer season,
            LocalDate gameDate,
            LocalTime gameTime,
            Team homeTeam,
            Team awayTeam,
            String stadium,
            GameStatus status,
            Integer homeScore,
            Integer awayScore,
            Team winnerTeam,
            GameResult result,
            String cancelReason,
            LocalDateTime now
    ) {
        boolean scheduleChanged = !Objects.equals(this.gameDate, gameDate)
                || !Objects.equals(this.gameTime, gameTime);

        this.externalGameId = externalGameId;
        this.season = season;
        this.gameDate = gameDate;
        this.gameTime = gameTime;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.stadium = stadium;
        this.status = status;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.winnerTeam = winnerTeam;
        this.result = result;
        if (this.predictionCloseAt == null || scheduleChanged) {
            this.predictionCloseAt = gameTime == null
                    ? null
                    : LocalDateTime.of(gameDate, gameTime).minusMinutes(30);
        }
        this.cancelReason = cancelReason;
        this.updatedAt = now;
    }

    public void correctTerminalResult(
            GameStatus correctedStatus,
            Integer correctedHomeScore,
            Integer correctedAwayScore,
            String correctedCancelReason,
            LocalDateTime now
    ) {
        if (correctedStatus == GameStatus.FINISHED) {
            if (correctedHomeScore == null || correctedAwayScore == null
                    || correctedHomeScore < 0 || correctedAwayScore < 0) {
                throw new IllegalArgumentException(
                        "Finished game scores must be non-negative"
                );
            }
            this.status = GameStatus.FINISHED;
            this.homeScore = correctedHomeScore;
            this.awayScore = correctedAwayScore;
            if (correctedHomeScore > correctedAwayScore) {
                this.result = GameResult.HOME_WIN;
                this.winnerTeam = homeTeam;
            } else if (correctedHomeScore < correctedAwayScore) {
                this.result = GameResult.AWAY_WIN;
                this.winnerTeam = awayTeam;
            } else {
                this.result = GameResult.DRAW;
                this.winnerTeam = null;
            }
            this.cancelReason = null;
        } else if (correctedStatus == GameStatus.CANCELLED) {
            this.status = GameStatus.CANCELLED;
            this.homeScore = null;
            this.awayScore = null;
            this.result = null;
            this.winnerTeam = null;
            this.cancelReason = correctedCancelReason;
        } else {
            throw new IllegalArgumentException(
                    "Only finished or cancelled results can be corrected"
            );
        }
        this.updatedAt = Objects.requireNonNull(now, "now");
    }
}
