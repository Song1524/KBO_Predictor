package com.playball.kbopredictor.prediction.history;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import com.playball.kbopredictor.prediction.feature.StartingPitcherFeatures;
import com.playball.kbopredictor.prediction.feature.TeamPredictionFeatures;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(
        name = "prediction_feature_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_prediction_feature_snapshot_point",
                columnNames = {"game_id", "feature_as_of", "generation_method"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionFeatureSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(name = "feature_as_of", nullable = false)
    private LocalDateTime featureAsOf;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_method", nullable = false, length = 40)
    private PredictionGenerationMethod generationMethod;

    @Column(name = "data_source", nullable = false, length = 500)
    private String dataSource;

    @Lob
    @Column(name = "missing_features", columnDefinition = "TEXT")
    private String missingFeatures;

    @Column(name = "home_historical_game_count", nullable = false)
    private int homeHistoricalGameCount;

    @Column(name = "away_historical_game_count", nullable = false)
    private int awayHistoricalGameCount;

    @Column(name = "home_season_win", nullable = false)
    private int homeSeasonWins;

    @Column(name = "home_season_lose", nullable = false)
    private int homeSeasonLosses;

    @Column(name = "home_season_draw", nullable = false)
    private int homeSeasonDraws;

    @Column(name = "away_season_win", nullable = false)
    private int awaySeasonWins;

    @Column(name = "away_season_lose", nullable = false)
    private int awaySeasonLosses;

    @Column(name = "away_season_draw", nullable = false)
    private int awaySeasonDraws;

    @Column(name = "home_season_win_rate", precision = 5, scale = 3)
    private BigDecimal homeSeasonWinRate;

    @Column(name = "away_season_win_rate", precision = 5, scale = 3)
    private BigDecimal awaySeasonWinRate;

    @Column(name = "home_recent_5_win_rate", precision = 5, scale = 3)
    private BigDecimal homeRecent5WinRate;

    @Column(name = "away_recent_5_win_rate", precision = 5, scale = 3)
    private BigDecimal awayRecent5WinRate;

    @Column(name = "home_recent_10_win_rate", precision = 5, scale = 3)
    private BigDecimal homeRecent10WinRate;

    @Column(name = "away_recent_10_win_rate", precision = 5, scale = 3)
    private BigDecimal awayRecent10WinRate;

    @Column(name = "home_recent_5_avg_runs", precision = 6, scale = 2)
    private BigDecimal homeRecent5AvgRuns;

    @Column(name = "away_recent_5_avg_runs", precision = 6, scale = 2)
    private BigDecimal awayRecent5AvgRuns;

    @Column(name = "home_recent_5_avg_runs_allowed", precision = 6, scale = 2)
    private BigDecimal homeRecent5AvgRunsAllowed;

    @Column(name = "away_recent_5_avg_runs_allowed", precision = 6, scale = 2)
    private BigDecimal awayRecent5AvgRunsAllowed;

    @Column(name = "home_recent_10_avg_runs", precision = 6, scale = 2)
    private BigDecimal homeRecent10AvgRuns;

    @Column(name = "away_recent_10_avg_runs", precision = 6, scale = 2)
    private BigDecimal awayRecent10AvgRuns;

    @Column(name = "home_recent_10_avg_runs_allowed", precision = 6, scale = 2)
    private BigDecimal homeRecent10AvgRunsAllowed;

    @Column(name = "away_recent_10_avg_runs_allowed", precision = 6, scale = 2)
    private BigDecimal awayRecent10AvgRunsAllowed;

    @Column(name = "home_batting_average", precision = 5, scale = 3)
    private BigDecimal homeBattingAverage;

    @Column(name = "away_batting_average", precision = 5, scale = 3)
    private BigDecimal awayBattingAverage;

    @Column(name = "home_era", precision = 5, scale = 2)
    private BigDecimal homeEra;

    @Column(name = "away_era", precision = 5, scale = 2)
    private BigDecimal awayEra;

    @Column(name = "home_venue_win_rate", precision = 5, scale = 3)
    private BigDecimal homeVenueWinRate;

    @Column(name = "away_venue_win_rate", precision = 5, scale = 3)
    private BigDecimal awayVenueWinRate;

    @Column(name = "home_starting_pitcher_name", length = 100)
    private String homeStartingPitcherName;

    @Column(name = "away_starting_pitcher_name", length = 100)
    private String awayStartingPitcherName;

    @Column(name = "home_starting_pitcher_stat_date")
    private LocalDate homeStartingPitcherStatDate;

    @Column(name = "away_starting_pitcher_stat_date")
    private LocalDate awayStartingPitcherStatDate;

    @Column(name = "home_starting_pitcher_era", precision = 5, scale = 2)
    private BigDecimal homeStartingPitcherEra;

    @Column(name = "away_starting_pitcher_era", precision = 5, scale = 2)
    private BigDecimal awayStartingPitcherEra;

    @Column(name = "home_starting_pitcher_whip", precision = 5, scale = 2)
    private BigDecimal homeStartingPitcherWhip;

    @Column(name = "away_starting_pitcher_whip", precision = 5, scale = 2)
    private BigDecimal awayStartingPitcherWhip;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static PredictionFeatureSnapshot create(
            Game game,
            HistoricalPredictionFeatures historical,
            LocalDateTime createdAt
    ) {
        PredictionFeatureSnapshot snapshot = new PredictionFeatureSnapshot();
        PredictionFeatures features = historical.features();
        TeamPredictionFeatures home = features.home();
        TeamPredictionFeatures away = features.away();
        snapshot.game = game;
        snapshot.featureAsOf = features.gameStartAt().minusSeconds(1);
        snapshot.generationMethod = historical.generationMethod();
        snapshot.dataSource = historical.dataSource();
        snapshot.missingFeatures = String.join(",", historical.missingFeatures());
        snapshot.homeHistoricalGameCount = historical.homeHistoricalGameCount();
        snapshot.awayHistoricalGameCount = historical.awayHistoricalGameCount();
        snapshot.homeSeasonWins = historical.homeSeasonWins();
        snapshot.homeSeasonLosses = historical.homeSeasonLosses();
        snapshot.homeSeasonDraws = historical.homeSeasonDraws();
        snapshot.awaySeasonWins = historical.awaySeasonWins();
        snapshot.awaySeasonLosses = historical.awaySeasonLosses();
        snapshot.awaySeasonDraws = historical.awaySeasonDraws();
        snapshot.copyHome(home);
        snapshot.copyAway(away);
        snapshot.createdAt = createdAt;
        return snapshot;
    }

    public static PredictionFeatureSnapshot createOperational(
            Game game,
            PredictionFeatures features,
            LocalDateTime featureAsOf,
            LocalDateTime createdAt
    ) {
        PredictionFeatureSnapshot snapshot = new PredictionFeatureSnapshot();
        snapshot.game = game;
        snapshot.featureAsOf = featureAsOf;
        snapshot.generationMethod = PredictionGenerationMethod.OPERATIONAL_PREGAME;
        snapshot.dataSource = "PredictionFeatureService pregame snapshot";
        snapshot.missingFeatures = String.join(",", missingFeatures(features));
        snapshot.homeHistoricalGameCount = features.home().teamStatsAvailable() ? 1 : 0;
        snapshot.awayHistoricalGameCount = features.away().teamStatsAvailable() ? 1 : 0;
        snapshot.homeSeasonWins = 0;
        snapshot.homeSeasonLosses = 0;
        snapshot.homeSeasonDraws = 0;
        snapshot.awaySeasonWins = 0;
        snapshot.awaySeasonLosses = 0;
        snapshot.awaySeasonDraws = 0;
        snapshot.copyHome(features.home());
        snapshot.copyAway(features.away());
        snapshot.createdAt = createdAt;
        return snapshot;
    }

    public PredictionFeatures toPredictionFeatures() {
        return new PredictionFeatures(
                game.getId(),
                game.getGameDate(),
                featureAsOf.plusSeconds(1),
                new TeamPredictionFeatures(
                        game.getHomeTeam().getId(),
                        game.getHomeTeam().getName(),
                        homeHistoricalGameCount > 0,
                        game.getGameDate().minusDays(1),
                        homeSeasonWinRate,
                        homeRecent5WinRate,
                        homeRecent10WinRate,
                        homeRecent5AvgRuns,
                        homeRecent5AvgRunsAllowed,
                        homeRecent10AvgRuns,
                        homeRecent10AvgRunsAllowed,
                        homeBattingAverage,
                        homeEra,
                        homeVenueWinRate,
                        pitcher(
                                homeStartingPitcherName,
                                homeStartingPitcherStatDate,
                                homeStartingPitcherEra,
                                homeStartingPitcherWhip
                        )
                ),
                new TeamPredictionFeatures(
                        game.getAwayTeam().getId(),
                        game.getAwayTeam().getName(),
                        awayHistoricalGameCount > 0,
                        game.getGameDate().minusDays(1),
                        awaySeasonWinRate,
                        awayRecent5WinRate,
                        awayRecent10WinRate,
                        awayRecent5AvgRuns,
                        awayRecent5AvgRunsAllowed,
                        awayRecent10AvgRuns,
                        awayRecent10AvgRunsAllowed,
                        awayBattingAverage,
                        awayEra,
                        awayVenueWinRate,
                        pitcher(
                                awayStartingPitcherName,
                                awayStartingPitcherStatDate,
                                awayStartingPitcherEra,
                                awayStartingPitcherWhip
                        )
                )
        );
    }

    public List<String> missingFeatureList() {
        if (missingFeatures == null || missingFeatures.isBlank()) {
            return List.of();
        }
        return Arrays.stream(missingFeatures.split(","))
                .filter(value -> !value.isBlank())
                .toList();
    }

    public boolean hasStartingPitcherData() {
        return homeStartingPitcherEra != null
                && homeStartingPitcherWhip != null
                && awayStartingPitcherEra != null
                && awayStartingPitcherWhip != null;
    }

    private void copyHome(TeamPredictionFeatures value) {
        homeSeasonWinRate = value.seasonWinRate();
        homeRecent5WinRate = value.recent5WinRate();
        homeRecent10WinRate = value.recent10WinRate();
        homeRecent5AvgRuns = value.recent5AvgRuns();
        homeRecent5AvgRunsAllowed = value.recent5AvgRunsAllowed();
        homeRecent10AvgRuns = value.recent10AvgRuns();
        homeRecent10AvgRunsAllowed = value.recent10AvgRunsAllowed();
        homeBattingAverage = value.battingAverage();
        homeEra = value.era();
        homeVenueWinRate = value.venueWinRate();
        if (value.startingPitcher() != null) {
            homeStartingPitcherName = value.startingPitcher().playerName();
            homeStartingPitcherStatDate = value.startingPitcher().statDate();
            homeStartingPitcherEra = value.startingPitcher().era();
            homeStartingPitcherWhip = value.startingPitcher().whip();
        }
    }

    private void copyAway(TeamPredictionFeatures value) {
        awaySeasonWinRate = value.seasonWinRate();
        awayRecent5WinRate = value.recent5WinRate();
        awayRecent10WinRate = value.recent10WinRate();
        awayRecent5AvgRuns = value.recent5AvgRuns();
        awayRecent5AvgRunsAllowed = value.recent5AvgRunsAllowed();
        awayRecent10AvgRuns = value.recent10AvgRuns();
        awayRecent10AvgRunsAllowed = value.recent10AvgRunsAllowed();
        awayBattingAverage = value.battingAverage();
        awayEra = value.era();
        awayVenueWinRate = value.venueWinRate();
        if (value.startingPitcher() != null) {
            awayStartingPitcherName = value.startingPitcher().playerName();
            awayStartingPitcherStatDate = value.startingPitcher().statDate();
            awayStartingPitcherEra = value.startingPitcher().era();
            awayStartingPitcherWhip = value.startingPitcher().whip();
        }
    }

    private StartingPitcherFeatures pitcher(
            String name,
            LocalDate statDate,
            BigDecimal era,
            BigDecimal whip
    ) {
        if (name == null) {
            return null;
        }
        return new StartingPitcherFeatures(
                null,
                null,
                name,
                true,
                era != null || whip != null,
                statDate,
                era,
                null,
                null,
                null,
                whip
        );
    }

    private static List<String> missingFeatures(PredictionFeatures features) {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        collectMissing("home", features.home(), missing);
        collectMissing("away", features.away(), missing);
        return List.copyOf(missing);
    }

    private static void collectMissing(
            String side,
            TeamPredictionFeatures team,
            java.util.List<String> missing
    ) {
        if (team.seasonWinRate() == null) missing.add(side + ".seasonWinRate");
        if (team.recent5WinRate() == null) missing.add(side + ".recent5WinRate");
        if (team.recent10WinRate() == null) missing.add(side + ".recent10WinRate");
        if (team.recent5AvgRuns() == null) missing.add(side + ".recent5AvgRuns");
        if (team.recent5AvgRunsAllowed() == null) missing.add(side + ".recent5AvgRunsAllowed");
        if (team.recent10AvgRuns() == null) missing.add(side + ".recent10AvgRuns");
        if (team.recent10AvgRunsAllowed() == null) missing.add(side + ".recent10AvgRunsAllowed");
        if (team.venueWinRate() == null) missing.add(side + ".venueWinRate");
        if (team.startingPitcher() == null) missing.add(side + ".startingPitcher");
    }
}
