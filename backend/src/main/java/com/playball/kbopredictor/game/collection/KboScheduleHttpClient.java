package com.playball.kbopredictor.game.collection;

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
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KboScheduleHttpClient implements KboScheduleClient {

    private final HttpClient httpClient;
    private final URI scheduleEndpoint;
    private final String referer;
    private final String userAgent;
    private final String regularSeriesIds;
    private final Duration requestTimeout;

    public KboScheduleHttpClient(
            @Value("${app.kbo-data.base-url:https://www.koreabaseball.com}")
            String baseUrl,
            @Value("${app.kbo-data.connect-timeout-ms:3000}")
            long connectTimeoutMillis,
            @Value("${app.kbo-data.request-timeout-ms:7000}")
            long requestTimeoutMillis,
            @Value("${app.kbo-data.user-agent:KBO-Predictor/1.0}")
            String userAgent,
            @Value("${app.kbo-data.regular-series-ids:0,9,6}")
            String regularSeriesIds
    ) {
        String normalizedBaseUrl = baseUrl.replaceAll("/+$", "");
        this.scheduleEndpoint = URI.create(
                normalizedBaseUrl + "/ws/Schedule.asmx/GetScheduleList"
        );
        this.referer = normalizedBaseUrl
                + "/Schedule/Schedule.aspx?seriesId=" + regularSeriesIds;
        this.userAgent = userAgent;
        this.regularSeriesIds = regularSeriesIds;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String fetchSchedule(YearMonth yearMonth) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("leId", "1");
        form.put("srIdList", regularSeriesIds);
        form.put("seasonId", String.valueOf(yearMonth.getYear()));
        form.put("gameMonth", "%02d".formatted(yearMonth.getMonthValue()));
        form.put("teamId", "");

        HttpRequest request = HttpRequest.newBuilder(scheduleEndpoint)
                .timeout(requestTimeout)
                .header(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                )
                .header("Referer", referer)
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GameDataCollectionException(
                        "KBO 일정 요청이 실패했습니다. HTTP "
                                + response.statusCode()
                );
            }
            if (response.body() == null || response.body().isBlank()) {
                throw new GameDataCollectionException(
                        "KBO 일정 응답이 비어 있습니다."
                );
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GameDataCollectionException(
                    "KBO 일정 요청이 중단되었습니다.",
                    exception
            );
        } catch (IOException exception) {
            throw new GameDataCollectionException(
                    "KBO 일정 요청 중 네트워크 오류가 발생했습니다.",
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
