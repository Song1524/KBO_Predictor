package com.playball.kbopredictor.prediction.dataset;

import com.playball.kbopredictor.game.collection.KboTeamCatalog;
import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.game.repository.GameRepository;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshot;
import com.playball.kbopredictor.prediction.history.PredictionFeatureSnapshotRepository;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoricalDatasetQualityService {

    private final GameRepository gameRepository;
    private final PredictionFeatureSnapshotRepository snapshotRepository;
    private final TeamRepository teamRepository;
    private final HistoricalDatasetMapper mapper;

    public HistoricalDatasetQualityResponse inspect(
            LocalDate from,
            LocalDate to
    ) {
        validate(from, to);
        List<Game> games = gameRepository.findByGameDateBetweenWithTeams(
                from, to
        );
        List<PredictionFeatureSnapshot> snapshots =
                snapshotRepository.findEvaluationSnapshots(from, to);
        SortedSet<Integer> seasons = games.stream()
                .map(Game::getSeason)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));

        List<HistoricalDatasetQualityResponse.DatasetQualitySlice> bySeason =
                seasons.stream()
                        .map(season -> slice(
                                String.valueOf(season),
                                season,
                                games.stream()
                                        .filter(game -> season.equals(game.getSeason()))
                                        .toList(),
                                snapshots.stream()
                                        .filter(snapshot -> season.equals(
                                                snapshot.getGame().getSeason()
                                        ))
                                        .toList()
                        ))
                        .toList();

        return new HistoricalDatasetQualityResponse(
                from,
                to,
                "KBO official schedule/results + internal finished games",
                "Only FINISHED games in the same season with gameDate strictly before the target game",
                "Only the ten supported KBO clubs are accepted; All-Star, postseason and event teams are excluded by regular-series IDs and team-code validation",
                slice("ALL", null, games, snapshots),
                bySeason,
                teamMapping()
        );
    }

    private HistoricalDatasetQualityResponse.DatasetQualitySlice slice(
            String label,
            Integer season,
            List<Game> games,
            List<PredictionFeatureSnapshot> snapshots
    ) {
        List<Game> finished = games.stream()
                .filter(game -> game.getStatus() == GameStatus.FINISHED)
                .filter(game -> game.getResult() != null)
                .toList();
        List<HistoricalMlDatasetRow> rows = snapshots.stream()
                .map(mapper::toRow)
                .toList();
        int usable = count(rows, row -> row.availableFeatureCount() > 0);
        int recent5 = countSnapshots(
                snapshots,
                snapshot -> snapshot.getHomeHistoricalGameCount() >= 5
                        && snapshot.getAwayHistoricalGameCount() >= 5
        );
        int recent10 = countSnapshots(
                snapshots,
                snapshot -> snapshot.getHomeHistoricalGameCount() >= 10
                        && snapshot.getAwayHistoricalGameCount() >= 10
        );
        int noPrior = countSnapshots(
                snapshots,
                snapshot -> snapshot.getHomeHistoricalGameCount() == 0
                        || snapshot.getAwayHistoricalGameCount() == 0
        );
        int fewer5 = countSnapshots(
                snapshots,
                snapshot -> snapshot.getHomeHistoricalGameCount() < 5
                        || snapshot.getAwayHistoricalGameCount() < 5
        );
        int fewer10 = countSnapshots(
                snapshots,
                snapshot -> snapshot.getHomeHistoricalGameCount() < 10
                        || snapshot.getAwayHistoricalGameCount() < 10
        );
        return new HistoricalDatasetQualityResponse.DatasetQualitySlice(
                label,
                season,
                games.size(),
                finished.size(),
                (int) games.stream()
                        .filter(game -> game.getStatus() == GameStatus.CANCELLED)
                        .count(),
                snapshots.size(),
                usable,
                Math.max(0, finished.size() - snapshots.size()),
                resultCount(finished, GameResult.HOME_WIN),
                resultCount(finished, GameResult.DRAW),
                resultCount(finished, GameResult.AWAY_WIN),
                averageCoverage(rows),
                recent5,
                percentage(recent5, finished.size()),
                recent10,
                percentage(recent10, finished.size()),
                noPrior,
                fewer5,
                fewer10
        );
    }

    private HistoricalDatasetQualityResponse.TeamMappingQuality teamMapping() {
        Set<String> expected = new TreeSet<>(
                KboTeamCatalog.supportedTeamCodes()
        );
        List<Team> teams = teamRepository.findAll();
        Set<String> present = teams.stream()
                .map(Team::getKboTeamCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(present);
        Set<String> unexpected = new TreeSet<>(present);
        unexpected.removeAll(expected);
        int withoutCode = (int) teams.stream()
                .filter(team -> team.getKboTeamCode() == null)
                .count();
        return new HistoricalDatasetQualityResponse.TeamMappingQuality(
                missing.isEmpty() && unexpected.isEmpty() && withoutCode == 0,
                Collections.unmodifiableSet(expected),
                Collections.unmodifiableSet(present),
                Collections.unmodifiableSet(missing),
                Collections.unmodifiableSet(unexpected),
                withoutCode
        );
    }

    private int resultCount(List<Game> games, GameResult result) {
        return (int) games.stream()
                .filter(game -> game.getResult() == result)
                .count();
    }

    private int count(
            List<HistoricalMlDatasetRow> rows,
            Predicate<HistoricalMlDatasetRow> predicate
    ) {
        return (int) rows.stream().filter(predicate).count();
    }

    private int countSnapshots(
            List<PredictionFeatureSnapshot> snapshots,
            Predicate<PredictionFeatureSnapshot> predicate
    ) {
        return (int) snapshots.stream().filter(predicate).count();
    }

    private BigDecimal averageCoverage(List<HistoricalMlDatasetRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        BigDecimal total = rows.stream()
                .map(HistoricalMlDatasetRow::featureCoverage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(
                BigDecimal.valueOf(rows.size()),
                2,
                RoundingMode.HALF_UP
        );
    }

    private BigDecimal percentage(int count, int total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid quality report period.");
        }
    }
}
