package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KboScheduleParser {

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern SPAN_PATTERN = Pattern.compile(
            "<span\\b[^>]*>(.*?)</span>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SCORE_PATTERN = Pattern.compile(
            "<span\\b([^>]*)>(\\d+)</span>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2})"
    );
    private static final Pattern GAME_ID_PATTERN = Pattern.compile(
            "gameId=([0-9A-Za-z]+)",
            Pattern.CASE_INSENSITIVE
    );

    private final ObjectMapper objectMapper;

    public KboScheduleParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GameCollectionBatch parse(String json, LocalDate targetDate) {
        JsonNode rows = readRows(json);
        List<ParsedRow> parsedRows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        LocalDate currentDate = null;
        int sourceRowCount = 0;
        int rowIndex = 0;

        for (JsonNode rowNode : rows) {
            rowIndex++;
            List<Cell> cells = readCells(rowNode.path("row"));
            if (cells.isEmpty()) {
                continue;
            }

            int offset = 0;
            if ("day".equals(cells.getFirst().cssClass())) {
                try {
                    currentDate = parseDate(
                            cells.getFirst().text(),
                            targetDate.getYear()
                    );
                    offset = 1;
                } catch (RuntimeException exception) {
                    errors.add("행 " + rowIndex + ": 경기 날짜 파싱 실패");
                    currentDate = null;
                    continue;
                }
            }

            if (!targetDate.equals(currentDate)) {
                continue;
            }

            sourceRowCount++;
            try {
                parsedRows.add(parseRow(cells, offset, currentDate));
            } catch (RuntimeException exception) {
                errors.add("행 " + rowIndex + ": " + exception.getMessage());
            }
        }

        return new GameCollectionBatch(
                sourceRowCount,
                assignExternalIds(parsedRows),
                errors
        );
    }

    private JsonNode readRows(String json) {
        try {
            JsonNode rows = objectMapper.readTree(json).path("rows");
            if (!rows.isArray()) {
                throw new GameDataCollectionException(
                        "KBO 일정 응답에 rows 배열이 없습니다."
                );
            }
            return rows;
        } catch (GameDataCollectionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GameDataCollectionException(
                    "KBO 일정 JSON을 파싱할 수 없습니다.",
                    exception
            );
        }
    }

    private List<Cell> readCells(JsonNode cellNodes) {
        if (!cellNodes.isArray()) {
            return List.of();
        }

        List<Cell> cells = new ArrayList<>();
        for (JsonNode cellNode : cellNodes) {
            cells.add(new Cell(
                    nodeText(cellNode, "Text"),
                    nodeText(cellNode, "Class")
            ));
        }
        return cells;
    }

    private ParsedRow parseRow(
            List<Cell> cells,
            int offset,
            LocalDate gameDate
    ) {
        if (cells.size() < offset + 8) {
            throw new IllegalArgumentException("일정 셀 개수가 부족합니다.");
        }

        LocalTime gameTime = parseTime(cells.get(offset).text());
        String playHtml = cells.get(offset + 1).text();
        String relayHtml = cells.get(offset + 2).text();
        String stadium = normalizeText(cells.get(offset + 6).text());
        String note = normalizeText(cells.get(offset + 7).text());

        List<String> spanTexts = extractSpanTexts(playHtml);
        if (spanTexts.size() < 3) {
            throw new IllegalArgumentException("홈/원정 팀을 찾을 수 없습니다.");
        }

        String awayTeamCode = KboTeamCatalog.codeOf(spanTexts.getFirst());
        String homeTeamCode = KboTeamCatalog.codeOf(spanTexts.getLast());
        ScoreInfo scoreInfo = extractScores(playHtml);
        GameStatus status = mapStatus(relayHtml, note, scoreInfo);

        Integer awayScore = scoreInfo.awayScore();
        Integer homeScore = scoreInfo.homeScore();
        GameResult result = null;
        String cancelReason = null;

        if (status == GameStatus.CANCELLED) {
            awayScore = null;
            homeScore = null;
            cancelReason = note;
        } else if (status == GameStatus.FINISHED) {
            if (awayScore == null || homeScore == null) {
                throw new IllegalArgumentException(
                        "종료 경기의 점수를 찾을 수 없습니다."
                );
            }
            result = determineResult(homeScore, awayScore);
        }

        return new ParsedRow(
                extractOfficialGameId(relayHtml),
                gameDate,
                gameTime,
                awayTeamCode,
                homeTeamCode,
                emptyToNull(stadium),
                status,
                awayScore,
                homeScore,
                result,
                emptyToNull(cancelReason)
        );
    }

    private List<CollectedGame> assignExternalIds(List<ParsedRow> rows) {
        Map<MatchupKey, Long> totals = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        row -> new MatchupKey(
                                row.gameDate(),
                                row.awayTeamCode(),
                                row.homeTeamCode()
                        ),
                        java.util.stream.Collectors.counting()
                ));
        Map<MatchupKey, Integer> occurrences = new HashMap<>();
        List<CollectedGame> games = new ArrayList<>();

        for (ParsedRow row : rows) {
            MatchupKey key = new MatchupKey(
                    row.gameDate(),
                    row.awayTeamCode(),
                    row.homeTeamCode()
            );
            int occurrence = occurrences.merge(key, 1, Integer::sum);
            int gameSequence = totals.get(key) > 1 ? occurrence : 0;
            String externalGameId = row.officialGameId();
            if (externalGameId == null) {
                externalGameId = "%s%s%s%d".formatted(
                        row.gameDate().toString().replace("-", ""),
                        row.awayTeamCode(),
                        row.homeTeamCode(),
                        gameSequence
                );
            }

            games.add(new CollectedGame(
                    externalGameId.toUpperCase(Locale.ROOT),
                    row.gameDate().getYear(),
                    row.gameDate(),
                    row.gameTime(),
                    row.awayTeamCode(),
                    row.homeTeamCode(),
                    row.stadium(),
                    row.status(),
                    row.awayScore(),
                    row.homeScore(),
                    row.result(),
                    row.cancelReason()
            ));
        }
        return games;
    }

    private GameStatus mapStatus(
            String relayHtml,
            String note,
            ScoreInfo scoreInfo
    ) {
        if (isCancelled(note)) {
            return GameStatus.CANCELLED;
        }

        String relay = relayHtml.toUpperCase(Locale.ROOT);
        if (relay.contains("SECTION=REVIEW")) {
            return GameStatus.FINISHED;
        }
        if (scoreInfo.hasFinalMarker()) {
            return GameStatus.FINISHED;
        }
        if (scoreInfo.awayScore() != null && scoreInfo.homeScore() != null) {
            return GameStatus.IN_PROGRESS;
        }
        return GameStatus.SCHEDULED;
    }

    private boolean isCancelled(String note) {
        return note.contains("취소")
                || note.contains("노게임")
                || note.equals("그라운드사정")
                || note.equals("기타");
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

    private LocalDate parseDate(String dayText, int year) {
        Matcher matcher = Pattern.compile("(\\d{2})\\.(\\d{2})")
                .matcher(dayText);
        if (!matcher.find()) {
            throw new IllegalArgumentException("잘못된 경기 날짜입니다.");
        }
        return LocalDate.of(
                year,
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2))
        );
    }

    private LocalTime parseTime(String html) {
        Matcher matcher = TIME_PATTERN.matcher(normalizeText(html));
        if (!matcher.find()) {
            return null;
        }
        return LocalTime.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2))
        );
    }

    private List<String> extractSpanTexts(String playHtml) {
        List<String> values = new ArrayList<>();
        Matcher matcher = SPAN_PATTERN.matcher(playHtml);
        while (matcher.find()) {
            values.add(normalizeText(matcher.group(1)));
        }
        return values;
    }

    private ScoreInfo extractScores(String playHtml) {
        int emStart = playHtml.toLowerCase(Locale.ROOT).indexOf("<em");
        int emEnd = playHtml.toLowerCase(Locale.ROOT).indexOf("</em>");
        if (emStart < 0 || emEnd < 0 || emEnd <= emStart) {
            return new ScoreInfo(null, null, false);
        }

        String scoreHtml = playHtml.substring(emStart, emEnd);
        List<Integer> scores = new ArrayList<>();
        boolean finalMarker = false;
        Matcher matcher = SCORE_PATTERN.matcher(scoreHtml);
        while (matcher.find()) {
            scores.add(Integer.parseInt(matcher.group(2)));
            String attributes = matcher.group(1).toLowerCase(Locale.ROOT);
            finalMarker = finalMarker
                    || attributes.contains("win")
                    || attributes.contains("lose")
                    || attributes.contains("same");
        }

        if (scores.size() != 2) {
            return new ScoreInfo(null, null, finalMarker);
        }
        return new ScoreInfo(scores.get(0), scores.get(1), finalMarker);
    }

    private String extractOfficialGameId(String relayHtml) {
        Matcher matcher = GAME_ID_PATTERN.matcher(relayHtml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalizeText(String value) {
        String withoutTags = TAG_PATTERN.matcher(value == null ? "" : value)
                .replaceAll("");
        return withoutTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() || "-".equals(value)
                ? null
                : value;
    }

    private String nodeText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? "" : value.stringValue("");
    }

    private record Cell(String text, String cssClass) {
    }

    private record ScoreInfo(
            Integer awayScore,
            Integer homeScore,
            boolean hasFinalMarker
    ) {
    }

    private record MatchupKey(
            LocalDate gameDate,
            String awayTeamCode,
            String homeTeamCode
    ) {
    }

    private record ParsedRow(
            String officialGameId,
            LocalDate gameDate,
            LocalTime gameTime,
            String awayTeamCode,
            String homeTeamCode,
            String stadium,
            GameStatus status,
            Integer awayScore,
            Integer homeScore,
            GameResult result,
            String cancelReason
    ) {
    }
}
