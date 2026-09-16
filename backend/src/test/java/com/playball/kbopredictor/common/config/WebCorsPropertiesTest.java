package com.playball.kbopredictor.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebCorsPropertiesTest {

    @Test
    void trimsExplicitOrigins() {
        WebCorsProperties properties = new WebCorsProperties(List.of(
                " https://playball.example "
        ));

        assertThat(properties.allowedOrigins())
                .containsExactly("https://playball.example");
    }

    @Test
    void rejectsWildcardOriginWithCredentials() {
        assertThatThrownBy(() -> new WebCorsProperties(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wildcard");
    }

    @Test
    void separatesFailClosedProductionSettingsFromLocalHttpSettings() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        PropertySource<?> production = loader.load(
                "application-prod.yaml",
                new ClassPathResource("application-prod.yaml")
        ).getFirst();
        PropertySource<?> local = loader.load(
                "application-local.yaml",
                new ClassPathResource("application-local.yaml")
        ).getFirst();

        assertThat(production.getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo(true);
        assertThat(production.getProperty("app.web.cors.allowed-origins"))
                .isEqualTo("${APP_FRONTEND_ORIGIN}");
        assertThat(local.getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo(false);
        assertThat(local.getProperty("app.web.cors.allowed-origins"))
                .isEqualTo("${APP_FRONTEND_ORIGIN:http://localhost:5173}");
    }
}
