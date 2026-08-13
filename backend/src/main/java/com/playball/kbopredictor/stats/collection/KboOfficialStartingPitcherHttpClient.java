package com.playball.kbopredictor.stats.collection;

import com.playball.kbopredictor.game.collection.OfficialGameResultSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KboOfficialStartingPitcherHttpClient
        implements OfficialStartingPitcherSource, OfficialGameResultSource {

    private static final DateTimeFormatter KBO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final HttpClient httpClient;
    private final URI gameListUri;
    private final String playerDetailBaseUrl;
    private final String referer;
    private final String userAgent;
    private final Duration requestTimeout;

    public KboOfficialStartingPitcherHttpClient(
            @Value("${app.kbo-data.base-url:https://www.koreabaseball.com}")
            String baseUrl,
            @Value("${app.kbo-data.connect-timeout-ms:3000}")
            long connectTimeoutMillis,
            @Value("${app.kbo-data.request-timeout-ms:7000}")
            long requestTimeoutMillis,
            @Value("${app.kbo-data.user-agent:KBO-Predictor/1.0}")
            String userAgent
    ) {
        String base = baseUrl.replaceAll("/+$", "");
        this.gameListUri = URI.create(base + "/ws/Main.asmx/GetKboGameList");
        this.playerDetailBaseUrl = base
                + "/Record/Player/PitcherDetail/Basic.aspx?playerId=";
        this.referer = base + "/Schedule/GameCenter/Main.aspx";
        this.userAgent = userAgent;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String fetchGameList(LocalDate date) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("leId", "1");
        form.put("srId", "0,1,3,4,5,6,7,8,9");
        form.put("date", date.format(KBO_DATE));

        HttpRequest request = HttpRequest.newBuilder(gameListUri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Referer", referer)
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();
        return send(request, "게임센터 선발투수 목록");
    }

    @Override
    public String fetchPlayerDetail(String kboPlayerId) {
        URI uri = URI.create(playerDetailBaseUrl + encode(kboPlayerId));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Referer", referer)
                .header("User-Agent", userAgent)
                .GET()
                .build();
        return send(request, "선수 투수 기록");
    }

    private String send(HttpRequest request, String dataName) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new PregameDataCollectionException(
                        "KBO " + dataName + " 요청 실패: HTTP "
                                + response.statusCode()
                );
            }
            if (response.body() == null || response.body().isBlank()) {
                throw new PregameDataCollectionException(
                        "KBO " + dataName + " 응답이 비어 있습니다."
                );
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PregameDataCollectionException(
                    "KBO " + dataName + " 요청이 중단되었습니다.",
                    exception
            );
        } catch (IOException exception) {
            throw new PregameDataCollectionException(
                    "KBO " + dataName + " 네트워크 요청에 실패했습니다.",
                    exception
            );
        }
    }

    private String encodeForm(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
