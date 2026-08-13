package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Slf4j
public class OfficialFinalScoreParser {

    private static final DateTimeFormatter KBO_DATE =
            DateTimeFormatter.BASIC_ISO_DATE;

    private final ObjectMapper objectMapper;

    public OfficialFinalScoreParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OfficialFinalScoreBatch parse(String json, LocalDate targetDate) {
        JsonNode games = readGames(json);
        Map<String, OfficialFinalScore> scores = new LinkedHashMap<>();
        Map<String, OfficialGameState> states = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (JsonNode game : games) {
            if (!targetDate.format(KBO_DATE).equals(text(game, "G_DT"))) {
                continue;
            }

            String externalGameId = text(game, "G_ID")
                    .toUpperCase(Locale.ROOT);
            if (externalGameId.isBlank()) {
                errors.add("KBO GameCenter final score row has no G_ID");
                continue;
            }

            try {
                OfficialGameState state = parseOfficialState(
                        game,
                        externalGameId
                );
                if (state != null) {
                    states.put(externalGameId, state);
                }
                log.debug(
                        "KBO GameCenter state: gameId={}, gameState={}, resultCheck={}, scoreCheck={}, inning={}, topBottom={}, awayScore={}, homeScore={}, cancelCode={}, detail={}",
                        externalGameId,
                        text(game, "GAME_STATE_SC"),
                        text(game, "GAME_RESULT_CK"),
                        text(game, "SCORE_CK"),
                        text(game, "GAME_INN_NO"),
                        text(game, "GAME_TB_SC"),
                        text(game, "T_SCORE_CN"),
                        text(game, "B_SCORE_CN"),
                        text(game, "CANCEL_SC_ID"),
                        text(game, "DETAIL_SC")
                );
            } catch (RuntimeException exception) {
                errors.add(externalGameId + ": " + exception.getMessage());
                continue;
            }

            if (!isConfirmedFinalResult(game)) {
                continue;
            }

            try {
                OfficialFinalScore score = parseConfirmedScore(
                        game,
                        externalGameId
                );
                scores.put(externalGameId, score);
            } catch (RuntimeException exception) {
                errors.add(externalGameId + ": " + exception.getMessage());
            }
        }

        return new OfficialFinalScoreBatch(
                Map.copyOf(scores),
                Map.copyOf(states),
                List.copyOf(errors)
        );
    }

    private OfficialGameState parseOfficialState(
            JsonNode game,
            String externalGameId
    ) {
        GameStatus status = mapStatus(game);
        if (status == null) {
            return null;
        }
        Integer awayScore = null;
        Integer homeScore = null;
        if (status == GameStatus.IN_PROGRESS
                && "1".equals(text(game, "SCORE_CK"))) {
            awayScore = optionalNonNegativeInteger(game, "T_SCORE_CN");
            homeScore = optionalNonNegativeInteger(game, "B_SCORE_CN");
        }
        return new OfficialGameState(
                externalGameId,
                requiredText(game, "AWAY_ID"),
                requiredText(game, "HOME_ID"),
                status,
                awayScore,
                homeScore
        );
    }

    private GameStatus mapStatus(JsonNode game) {
        if (!isNormalGame(game)) {
            return GameStatus.CANCELLED;
        }
        return switch (text(game, "GAME_STATE_SC")) {
            case "1" -> GameStatus.SCHEDULED;
            case "2" -> GameStatus.IN_PROGRESS;
            case "3" -> GameStatus.FINISHED;
            default -> null;
        };
    }

    private JsonNode readGames(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String responseCode = text(root, "code");
            if (!responseCode.isBlank() && !"100".equals(responseCode)) {
                throw new GameDataCollectionException(
                        "KBO GameCenter request failed with code " + responseCode
                );
            }
            JsonNode games = root.path("game");
            if (!games.isArray()) {
                throw new GameDataCollectionException(
                        "KBO GameCenter response has no game array"
                );
            }
            return games;
        } catch (GameDataCollectionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GameDataCollectionException(
                    "Unable to parse KBO GameCenter final score response",
                    exception
            );
        }
    }

    private boolean isConfirmedFinalResult(JsonNode game) {
        return "3".equals(text(game, "GAME_STATE_SC"))
                && "1".equals(text(game, "GAME_RESULT_CK"))
                && "1".equals(text(game, "SCORE_CK"))
                && isNormalGame(game);
    }

    private boolean isNormalGame(JsonNode game) {
        String cancellationCode = text(game, "CANCEL_SC_ID");
        return cancellationCode.isBlank() || "0".equals(cancellationCode);
    }

    private OfficialFinalScore parseConfirmedScore(
            JsonNode game,
            String externalGameId
    ) {
        String awayTeamCode = requiredText(game, "AWAY_ID");
        String homeTeamCode = requiredText(game, "HOME_ID");
        int awayScore = nonNegativeInteger(game, "T_SCORE_CN");
        int homeScore = nonNegativeInteger(game, "B_SCORE_CN");

        return new OfficialFinalScore(
                externalGameId,
                awayTeamCode,
                homeTeamCode,
                awayScore,
                homeScore,
                determineResult(homeScore, awayScore)
        );
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value;
    }

    private int nonNegativeInteger(JsonNode node, String field) {
        String value = requiredText(node, field);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(field + " is negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " is not an integer");
        }
    }

    private Integer optionalNonNegativeInteger(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private GameResult determineResult(int homeScore, int awayScore) {
        if (homeScore > awayScore) {
            return GameResult.HOME_WIN;
        }
        if (homeScore < awayScore) {
            return GameResult.AWAY_WIN;
        }
        return GameResult.DRAW;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isString()
                ? value.stringValue("").trim()
                : value.toString().replace("\"", "").trim();
    }
}
