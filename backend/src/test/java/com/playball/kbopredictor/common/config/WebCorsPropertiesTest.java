package com.playball.kbopredictor.common.config;

import org.junit.jupiter.api.Test;

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
}
