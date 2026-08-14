package com.playball.kbopredictor.stats.entity;

import com.playball.kbopredictor.team.entity.Team;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "team_stats",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_team_stats_team_date",
                columnNames = {"team_id", "season", "stat_date"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    private Integer season;

    @Column(name = "stat_date")
    private LocalDate statDate;

    @Column(name = "official_rank")
    private Integer officialRank;

    @Column(name = "games_played")
    private Integer gamesPlayed;

    @Column(name = "win")
    private Integer wins;

    @Column(name = "lose")
    private Integer losses;

    @Column(name = "draw")
    private Integer draws;

    @Column(name = "win_rate", precision = 5, scale = 3)
    private BigDecimal winRate;

    @Column(name = "games_behind", precision = 6, scale = 1)
    private BigDecimal gamesBehind;

    @Column(length = 20)
    private String streak;

    @Column(name = "recent_10_win")
    private Integer recent10Wins;

    @Column(name = "recent_10_lose")
    private Integer recent10Losses;

    @Column(name = "recent_10_draw")
    private Integer recent10Draws;

    @Column(name = "home_win")
    private Integer homeWins;

    @Column(name = "home_lose")
    private Integer homeLosses;

    @Column(name = "home_draw")
    private Integer homeDraws;

    @Column(name = "away_win")
    private Integer awayWins;

    @Column(name = "away_lose")
    private Integer awayLosses;

    @Column(name = "away_draw")
    private Integer awayDraws;

    @Column(name = "recent_5_win_rate", precision = 5, scale = 3)
    private BigDecimal recent5WinRate;

    @Column(name = "recent_10_win_rate", precision = 5, scale = 3)
    private BigDecimal recent10WinRate;

    @Column(name = "recent_5_avg_runs", precision = 6, scale = 2)
    private BigDecimal recent5AvgRuns;

    @Column(name = "recent_5_avg_runs_allowed", precision = 6, scale = 2)
    private BigDecimal recent5AvgRunsAllowed;

    @Column(name = "recent_10_avg_runs", precision = 6, scale = 2)
    private BigDecimal recent10AvgRuns;

    @Column(name = "recent_10_avg_runs_allowed", precision = 6, scale = 2)
    private BigDecimal recent10AvgRunsAllowed;

    @Column(name = "batting_avg", precision = 5, scale = 3)
    private BigDecimal battingAverage;

    @Column(name = "era", precision = 5, scale = 2)
    private BigDecimal era;

    @Column(name = "collected_at")
    private LocalDateTime collectedAt;

    public static TeamStat create(
            Team team,
            Integer season,
            LocalDate statDate
    ) {
        TeamStat teamStat = new TeamStat();
        teamStat.team = team;
        teamStat.season = season;
        teamStat.statDate = statDate;
        return teamStat;
    }

    public void updateOfficialStanding(
            Integer officialRank,
            Integer gamesPlayed,
            BigDecimal gamesBehind,
            String streak
    ) {
        this.officialRank = officialRank;
        this.gamesPlayed = gamesPlayed;
        this.gamesBehind = gamesBehind;
        this.streak = streak;
    }

    public void update(
            Integer wins,
            Integer losses,
            Integer draws,
            BigDecimal winRate,
            Integer recent10Wins,
            Integer recent10Losses,
            Integer recent10Draws,
            Integer homeWins,
            Integer homeLosses,
            Integer homeDraws,
            Integer awayWins,
            Integer awayLosses,
            Integer awayDraws,
            BigDecimal battingAverage,
            BigDecimal era,
            TeamRecentFormValues form,
            LocalDateTime collectedAt
    ) {
        this.wins = wins;
        this.losses = losses;
        this.draws = draws;
        this.winRate = winRate;
        this.recent10Wins = recent10Wins;
        this.recent10Losses = recent10Losses;
        this.recent10Draws = recent10Draws;
        this.homeWins = homeWins;
        this.homeLosses = homeLosses;
        this.homeDraws = homeDraws;
        this.awayWins = awayWins;
        this.awayLosses = awayLosses;
        this.awayDraws = awayDraws;
        this.battingAverage = battingAverage;
        this.era = era;
        this.recent5WinRate = form.recent5WinRate();
        this.recent10WinRate = form.recent10WinRate();
        this.recent5AvgRuns = form.recent5AvgRuns();
        this.recent5AvgRunsAllowed = form.recent5AvgRunsAllowed();
        this.recent10AvgRuns = form.recent10AvgRuns();
        this.recent10AvgRunsAllowed = form.recent10AvgRunsAllowed();
        this.collectedAt = collectedAt;
    }
}
