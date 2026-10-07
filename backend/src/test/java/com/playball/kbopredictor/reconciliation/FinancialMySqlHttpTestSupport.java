package com.playball.kbopredictor.reconciliation;

import com.playball.kbopredictor.game.collection.KboScheduleClient;
import com.playball.kbopredictor.stats.collection.KboOfficialStartingPitcherHttpClient;
import com.playball.kbopredictor.game.repository.GameRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FinancialMySqlHttpTestSupport.FixedClockConfiguration.class)
abstract class FinancialMySqlHttpTestSupport {
    // A fixed future month avoids the collector's completed-month cache between fixture phases.
    static final LocalDate DATE = LocalDate.of(2099, 6, 12);
    static final String EXTERNAL_ID = "20990612LGOB0";
    static final String PASSWORD = "password123!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired FinancialReconciliationService reconciliation;
    @Autowired GameRepository games;
    @Autowired TransactionTemplate transactions;
    @MockitoBean KboScheduleClient scheduleSource;
    @MockitoBean KboOfficialStartingPitcherHttpClient resultSource;
    final List<Long> ownUsers = new ArrayList<>();
    final List<Long> ownGames = new ArrayList<>();
    Actor admin, user, other;
    long gameId;

    @BeforeEach void prepareIndependentScenario() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
            assertThat(connection.getMetaData().getDatabaseMinorVersion()).isEqualTo(4);
        }
        admin = register("admin", true);
        user = register("player", false);
        other = register("opponent", false);
        scheduledFixture(1);
        sync();
        gameId = jdbc.queryForObject("select id from games where external_game_id = ?", Long.class, EXTERNAL_ID);
        ownGames.add(gameId);
        assertUserPoint(user, 1050);
    }

    @AfterEach void removeOnlyOwnScenario() {
        for (Long id : ownUsers) jdbc.update("delete from point_histories where user_id = ? and reversal_of_id is not null", id);
        for (Long id : ownUsers) jdbc.update("delete from point_histories where user_id = ?", id);
        for (Long id : ownGames) {
            jdbc.update("delete from user_predictions where game_id = ?", id);
            jdbc.update("delete from game_settlements where game_id = ?", id);
            jdbc.update("delete from game_odds where game_id = ?", id);
            jdbc.update("delete from games where id = ?", id);
        }
        for (Long id : ownUsers) jdbc.update("delete from users where id = ?", id);
        ownUsers.clear(); ownGames.clear();
    }

    Actor register(String prefix, boolean administrator) throws Exception {
        Actor actor = new Actor();
        actor.email = prefix + "-" + UUID.randomUUID().toString().substring(0, 10) + "@example.com";
        actor.csrf = api(null, HttpMethod.GET, "/api/auth/csrf", null, 204).getResponse().getCookie("XSRF-TOKEN");
        assertThat(actor.csrf).isNotNull();
        var signup = api(actor, HttpMethod.POST, "/api/auth/signup", Map.of("email", actor.email,
                "password", PASSWORD, "nickname", actor.email.substring(0, actor.email.indexOf('@'))), 201);
        actor.id = body(signup).path("id").asLong(); ownUsers.add(actor.id);
        actor.session = (MockHttpSession) signup.getRequest().getSession(false);
        assertThat(body(signup).path("point").asInt()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("select point_change from point_histories where user_id = ? and type = 'SIGNUP_BONUS'", Integer.class, actor.id)).isEqualTo(1000);
        api(actor, HttpMethod.POST, "/api/auth/logout", null, 204);
        actor.session = null;
        if (administrator) jdbc.update("update users set role = 'ADMIN' where id = ?", actor.id);
        var login = api(actor, HttpMethod.POST, "/api/auth/login", Map.of("email", actor.email, "password", PASSWORD), 200);
        actor.session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(body(login).path("dailyLoginBonusPoints").asInt()).isEqualTo(50);
        assertThat(body(login).path("point").asInt()).isEqualTo(1050);
        return actor;
    }

    MvcResult api(Actor actor, HttpMethod method, String path, Object payload, int expectedStatus) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path);
        if (actor != null) {
            if (actor.session != null) request.session(actor.session);
            if (actor.csrf != null) request.cookie(actor.csrf).header("X-XSRF-TOKEN", actor.csrf.getValue());
        }
        if (payload != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    JsonNode get(Actor actor, String path) throws Exception { return body(api(actor, HttpMethod.GET, path, null, 200)); }
    long predict(Actor actor, long targetGame, String outcome, int stake) throws Exception {
        return body(api(actor, HttpMethod.POST, "/api/user-predictions", Map.of("gameId", targetGame,
                "selectedOutcome", outcome, "pointAmount", stake), 201)).path("id").asLong();
    }
    void betBoth() throws Exception { predict(user, gameId, "HOME_WIN", 100); predict(other, gameId, "AWAY_WIN", 100); }
    void assertUserPoint(Actor actor, int expected) throws Exception { assertThat(get(actor, "/api/auth/me").path("point").asInt()).isEqualTo(expected); }
    void assertNormal() throws Exception {
        assertThat(get(admin, "/api/admin/reconciliation/users/" + user.id).path("status").asText()).isEqualTo("NORMAL");
        assertThat(get(admin, "/api/admin/reconciliation/users/" + other.id).path("status").asText()).isEqualTo("NORMAL");
        var game = get(admin, "/api/admin/reconciliation/games/" + gameId);
        assertThat(game.path("status").asText()).withFailMessage(game.toString()).isEqualTo("NORMAL");
    }
    void assertPrediction(Actor actor, String expected) throws Exception {
        var predictions = get(actor, "/api/user-predictions/me");
        assertThat(predictions.isArray()).isTrue(); assertThat(predictions.size()).isEqualTo(1);
        assertThat(predictions.get(0).path("settlementStatus").asText()).isEqualTo(expected);
    }
    void assertPeriodProfit(Actor actor, long expected) throws Exception {
        for (String type : List.of("WEEKLY_PROFIT", "MONTHLY_PROFIT"))
            assertThat(get(actor, "/api/rankings?type=" + type).path("myRanking").path("periodProfit").asLong()).isEqualTo(expected);
    }
    void rollback() throws Exception {
        api(admin, HttpMethod.POST, "/api/admin/games/" + gameId + "/settlement/rollback",
                Map.of("settlementRevision", 1, "reason", "E2E result correction"), 200);
    }
    void correctAndResettle() throws Exception {
        api(admin, HttpMethod.PUT, "/api/admin/games/" + gameId + "/result", Map.of("settlementRevision", 1,
                "status", "FINISHED", "homeScore", 1, "awayScore", 3, "reason", "E2E official correction"), 200);
        api(admin, HttpMethod.POST, "/api/admin/games/" + gameId + "/settlement?rollbackRevision=1", null, 200);
    }
    void sync() throws Exception {
        var result = body(api(admin, HttpMethod.POST, "/api/admin/data/games/sync?date=" + DATE, null, 200));
        assertThat(result.path("failedCount").asInt()).withFailMessage(result.toString()).isZero();
        assertThat(result.path("errors").size()).withFailMessage(result.toString()).isZero();
    }
    void scheduledFixture(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(scheduleRow("20990612LGOB" + i, "START_PIT", false, "-"));
        when(scheduleSource.fetchSchedule(any())).thenReturn(json.writeValueAsString(Map.of("rows", rows)));
        when(resultSource.fetchGameList(DATE)).thenReturn("{\"code\":\"100\",\"game\":[]}");
    }
    void finishedFixture(boolean homeWins) {
        when(scheduleSource.fetchSchedule(any())).thenReturn(json.writeValueAsString(Map.of("rows",
                List.of(scheduleRow(EXTERNAL_ID, "REVIEW", true, "-")))));
        when(resultSource.fetchGameList(DATE)).thenReturn(json.writeValueAsString(Map.of("code", "100", "game", List.of(Map.ofEntries(
                Map.entry("G_DT", "20990612"), Map.entry("G_ID", EXTERNAL_ID), Map.entry("AWAY_ID", "LG"), Map.entry("HOME_ID", "OB"),
                Map.entry("GAME_STATE_SC", "3"), Map.entry("GAME_RESULT_CK", 1), Map.entry("SCORE_CK", "1"), Map.entry("CANCEL_SC_ID", "0"),
                Map.entry("T_SCORE_CN", homeWins ? 2 : 3), Map.entry("B_SCORE_CN", homeWins ? 4 : 1))))));
    }
    void cancelledFixture() {
        when(scheduleSource.fetchSchedule(any())).thenReturn(json.writeValueAsString(Map.of("rows",
                List.of(scheduleRow(EXTERNAL_ID, "START_PIT", false, "우천취소")))));
    }
    private Map<String, Object> scheduleRow(String external, String section, boolean finished, String note) {
        String play = finished ? "<span>LG</span><em><span class='same'>0</span><span>vs</span><span class='same'>0</span></em><span>두산</span>"
                : "<span>LG</span><em><span>vs</span></em><span>두산</span>";
        return Map.of("row", List.of(cell("06.12(금)", "day"), cell(external.endsWith("1") ? "21:00" : "18:30", "time"), cell(play, "play"),
                cell("<a href='/Schedule/GameCenter/Main.aspx?gameDate=20990612&gameId=" + external + "&section=" + section + "'>중계</a>", "relay"),
                cell("", ""), cell("", ""), cell("", ""), cell("잠실", ""), cell(note, "")));
    }
    private Map<String, String> cell(String value, String css) { return Map.of("Text", value, "Class", css); }
    static class Actor { long id; String email; MockHttpSession session; Cookie csrf; }
    @TestConfiguration static class FixedClockConfiguration {
        @Bean @Primary Clock financialScenarioClock() {
            return Clock.fixed(Instant.parse("2099-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}
