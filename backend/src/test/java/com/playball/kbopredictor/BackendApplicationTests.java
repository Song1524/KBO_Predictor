package com.playball.kbopredictor;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.odds.closing-scheduler.enabled=false",
        "app.prediction.history-finalization-scheduler.enabled=false",
        "app.kbo-data.sync-scheduler.enabled=false",
        "app.kbo-data.pregame-scheduler.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BackendApplicationTests {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void flywayIsAtLeastVersionTwentyAndHasNoPendingMigration() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion())
                .isGreaterThanOrEqualTo(MigrationVersion.fromVersion("20"));
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void repeatedMigrationIsIdempotent() {
        MigrateResult result = flyway.migrate();

        assertThat(result.migrationsExecuted).isZero();
    }

    @Test
    void testProfileDoesNotKeepLegacyLocalAccount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Integer.class,
                "test@test.com"
        );

        assertThat(count).isZero();
    }

    @Test
    void testBootstrapContainsReferenceTeamsButNoOperationalFixtureData() {
        assertThat(countRows("teams")).isEqualTo(10);
        assertThat(countRows("users")).isZero();
        assertThat(countRows("games")).isZero();
        assertThat(countRows("user_predictions")).isZero();
        assertThat(countRows("system_predictions")).isZero();
    }

    @Test
    void healthIsPublicButOtherActuatorEndpointsAreNotExposed()
            throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
    }

    @Test
    void credentialCorsAllowsConfiguredOriginAndRejectsWildcardBehavior()
            throws Exception {
        mockMvc.perform(options("/api/games")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://localhost:5173"
                ));

        mockMvc.perform(options("/api/games")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    private int countRows(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName,
                Integer.class
        );
        return count == null ? 0 : count;
    }

}
