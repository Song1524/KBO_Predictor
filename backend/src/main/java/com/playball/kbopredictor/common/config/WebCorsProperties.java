package com.playball.kbopredictor.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.web.cors")
public record WebCorsProperties(List<String> allowedOrigins) {

    public WebCorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one explicit CORS origin must be configured."
            );
        }
        allowedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (allowedOrigins.isEmpty() || allowedOrigins.contains("*")) {
            throw new IllegalArgumentException(
                    "Wildcard CORS origins are not allowed with session credentials."
            );
        }
    }
}
