package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.collection.KboTeamCatalog;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OfficialTeamStatsParser {

    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "<table\\b[^>]*>(.*?)</table>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ROW_PATTERN = Pattern.compile(
            "<tr\\b[^>]*>(.*?)</tr>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern CELL_PATTERN = Pattern.compile(
            "<t[dh]\\b[^>]*>(.*?)</t[dh]>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern RECENT_PATTERN = Pattern.compile(
            "(\\d+)승(\\d+)무(\\d+)패"
    );
    private static final Pattern VENUE_PATTERN = Pattern.compile(
            "(\\d+)-(\\d+)-(\\d+)"
    );

    public List<OfficialTeamStanding> parseStandings(String html) {
        String table = findTable(html, "최근10경기");
        List<OfficialTeamStanding> standings = new ArrayList<>();
        for (List<String> cells : rows(table)) {
            if (cells.size() < 12 || !isInteger(cells.get(0))) {
                continue;
            }
            String code = KboTeamCatalog.codeOf(cells.get(1));
            int[] recent = parseRecord(cells.get(8), RECENT_PATTERN);
            int[] home = parseRecord(cells.get(10), VENUE_PATTERN);
            int[] away = parseRecord(cells.get(11), VENUE_PATTERN);
            standings.add(new OfficialTeamStanding(
                    code,
                    integer(cells.get(3)),
                    integer(cells.get(4)),
                    integer(cells.get(5)),
                    decimal(cells.get(6)),
                    recent[0],
                    recent[2],
                    recent[1],
                    home[0],
                    home[2],
                    home[1],
                    away[0],
                    away[2],
                    away[1]
            ));
        }
        return List.copyOf(standings);
    }

    public Map<String, BigDecimal> parseBattingAverages(String html) {
        return parseMetricTable(html, "data-id=\"HRA_RT\"");
    }

    public Map<String, BigDecimal> parseEras(String html) {
        return parseMetricTable(html, "data-id=\"ERA_RT\"");
    }

    private Map<String, BigDecimal> parseMetricTable(
            String html,
            String marker
    ) {
        String table = findTable(html, marker);
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (List<String> cells : rows(table)) {
            if (cells.size() < 3 || !isInteger(cells.get(0))) {
                continue;
            }
            values.put(
                    KboTeamCatalog.codeOf(cells.get(1)),
                    decimal(cells.get(2))
            );
        }
        return Map.copyOf(values);
    }

    private String findTable(String html, String marker) {
        Matcher matcher = TABLE_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String table = matcher.group();
            if (table.contains(marker)) {
                return table;
            }
        }
        throw new PregameDataCollectionException(
                "KBO 통계 응답에서 표를 찾을 수 없습니다: " + marker
        );
    }

    private List<List<String>> rows(String table) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = ROW_PATTERN.matcher(table);
        while (rowMatcher.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = CELL_PATTERN.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                cells.add(normalize(cellMatcher.group(1)));
            }
            rows.add(cells);
        }
        return rows;
    }

    private int[] parseRecord(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw new PregameDataCollectionException(
                    "KBO 승무패 기록 형식을 해석할 수 없습니다: " + value
            );
        }
        return new int[]{
                integer(matcher.group(1)),
                integer(matcher.group(2)),
                integer(matcher.group(3))
        };
    }

    private String normalize(String value) {
        return TAG_PATTERN.matcher(value).replaceAll("")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .trim();
    }

    private boolean isInteger(String value) {
        return value.matches("\\d+");
    }

    private int integer(String value) {
        return Integer.parseInt(value.trim());
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value.trim());
    }
}
