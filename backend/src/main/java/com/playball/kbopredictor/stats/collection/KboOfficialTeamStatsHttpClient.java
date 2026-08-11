package com.playball.kbopredictor.stats.collection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class KboOfficialTeamStatsHttpClient implements OfficialTeamStatsSource {

    private final HttpClient httpClient;
    private final URI standingsUri;
    private final URI battingUri;
    private final URI pitchingUri;
    private final String userAgent;
    private final Duration requestTimeout;

    public KboOfficialTeamStatsHttpClient(
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
        this.standingsUri = URI.create(
                base + "/Record/TeamRank/TeamRankDaily.aspx"
        );
        this.battingUri = URI.create(
                base + "/Record/Team/Hitter/Basic1.aspx"
        );
        this.pitchingUri = URI.create(
                base + "/Record/Team/Pitcher/Basic1.aspx"
        );
        this.userAgent = userAgent;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String fetchStandings() {
        return get(standingsUri, "팀 순위");
    }

    @Override
    public String fetchTeamBatting() {
        return get(battingUri, "팀 타격");
    }

    @Override
    public String fetchTeamPitching() {
        return get(pitchingUri, "팀 투수");
    }

    private String get(URI uri, String dataName) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .GET()
                .build();
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
}
