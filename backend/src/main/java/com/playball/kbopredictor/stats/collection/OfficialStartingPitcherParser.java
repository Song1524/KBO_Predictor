package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OfficialStartingPitcherParser {

    private static final DateTimeFormatter KBO_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern SEASON_PATTERN = Pattern.compile(
            "<h6[^>]*>\\s*(\\d{4})\\s*성적\\s*</h6>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "<table\\b[^>]*>(.*?)</table>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ROW_PATTERN = Pattern.compile(
            "<tr\\b[^>]*>(.*?)</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "<th\\b[^>]*>(.*?)</th>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern DATA_PATTERN = Pattern.compile(
            "<td\\b[^>]*>(.*?)</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final ObjectMapper objectMapper;

    public OfficialStartingPitcherParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedStartingPitcherList parseGameList(
            String json,
            LocalDate targetDate
    ) {
        try {
            JsonNode games = objectMapper.readTree(json).path("game");
            if (!games.isArray()) {
                throw new PregameDataCollectionException(
                        "KBO 게임센터 응답에 game 배열이 없습니다."
                );
            }

            int sourceGameCount = 0;
            List<StartingPitcherCandidate> candidates = new ArrayList<>();
            for (JsonNode game : games) {
                if (!targetDate.format(KBO_DATE).equals(text(game, "G_DT"))) {
                    continue;
                }
                sourceGameCount++;
                if (integer(game, "START_PIT_CK") != 1) {
                    continue;
                }

                addCandidate(
                        candidates,
                        game,
                        StartingPitcherSide.AWAY,
                        "AWAY_ID",
                        "T_PIT_P_ID",
                        "T_PIT_P_NM"
                );
                addCandidate(
                        candidates,
                        game,
                        StartingPitcherSide.HOME,
                        "HOME_ID",
                        "B_PIT_P_ID",
                        "B_PIT_P_NM"
                );
            }
            return new ParsedStartingPitcherList(
                    sourceGameCount,
                    List.copyOf(candidates)
            );
        } catch (PregameDataCollectionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PregameDataCollectionException(
                    "KBO 게임센터 선발투수 JSON을 해석할 수 없습니다.",
                    exception
            );
        }
    }

    public Optional<CollectedPitcherSeasonStat> parsePlayerDetail(
            String html,
            int expectedSeason
    ) {
        Matcher seasonMatcher = SEASON_PATTERN.matcher(html == null ? "" : html);
        if (!seasonMatcher.find()
                || Integer.parseInt(seasonMatcher.group(1)) != expectedSeason) {
            return Optional.empty();
        }

        String basicTable = findTable(html, "title=\"이닝\"");
        String secondaryTable = findTable(html, "title=\"이닝당 출루허용률\"");
        if (basicTable == null || secondaryTable == null) {
            return Optional.empty();
        }

        TableData basic = readTable(basicTable);
        TableData secondary = readTable(secondaryTable);
        if (basic.values().isEmpty() || secondary.values().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new CollectedPitcherSeasonStat(
                expectedSeason,
                decimal(value(basic, "ERA")),
                integer(value(basic, "W")),
                integer(value(basic, "L")),
                nullable(value(basic, "IP")),
                decimal(value(secondary, "WHIP"))
        ));
    }

    private void addCandidate(
            List<StartingPitcherCandidate> candidates,
            JsonNode game,
            StartingPitcherSide side,
            String teamField,
            String playerIdField,
            String playerNameField
    ) {
        String gameId = text(game, "G_ID");
        String teamCode = text(game, teamField);
        String playerId = text(game, playerIdField);
        String playerName = text(game, playerNameField).trim();
        Integer season = integerOrNull(game, "SEASON_ID");
        if (gameId.isBlank()
                || teamCode.isBlank()
                || playerId.isBlank()
                || playerName.isBlank()
                || season == null) {
            return;
        }
        candidates.add(new StartingPitcherCandidate(
                gameId,
                teamCode,
                side,
                playerId,
                playerName,
                season
        ));
    }

    private String findTable(String html, String marker) {
        Matcher matcher = TABLE_PATTERN.matcher(html);
        while (matcher.find()) {
            String table = matcher.group();
            if (table.contains(marker)) {
                return table;
            }
        }
        return null;
    }

    private TableData readTable(String table) {
        List<String> headers = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Matcher rowMatcher = ROW_PATTERN.matcher(table);
        while (rowMatcher.find()) {
            String row = rowMatcher.group(1);
            if (headers.isEmpty()) {
                headers.addAll(extract(row, HEADER_PATTERN));
            }
            List<String> data = extract(row, DATA_PATTERN);
            if (!data.isEmpty() && values.isEmpty()) {
                values.addAll(data);
            }
        }
        if (headers.size() != values.size()) {
            return new TableData(Map.of());
        }
        Map<String, String> mapped = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            mapped.put(headers.get(index), values.get(index));
        }
        return new TableData(Map.copyOf(mapped));
    }

    private List<String> extract(String html, Pattern pattern) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            values.add(normalize(matcher.group(1)));
        }
        return values;
    }

    private String value(TableData table, String key) {
        return table.values().get(key);
    }

    private String normalize(String value) {
        return TAG_PATTERN.matcher(value).replaceAll("")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .trim();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() || "-".equals(value)
                ? null
                : value;
    }

    private BigDecimal decimal(String value) {
        String normalized = nullable(value);
        return normalized == null ? null : new BigDecimal(normalized);
    }

    private Integer integer(String value) {
        String normalized = nullable(value);
        return normalized == null ? null : Integer.valueOf(normalized);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isString() ? value.stringValue("") : value.toString();
    }

    private int integer(JsonNode node, String field) {
        Integer value = integerOrNull(node, field);
        return value == null ? 0 : value;
    }

    private Integer integerOrNull(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : Integer.valueOf(value.replace("\"", ""));
    }

    public record ParsedStartingPitcherList(
            int sourceGameCount,
            List<StartingPitcherCandidate> candidates
    ) {
    }

    private record TableData(Map<String, String> values) {
    }
}
