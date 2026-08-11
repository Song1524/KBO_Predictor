package com.playball.kbopredictor.user.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.user")
@Validated
@Getter
@Setter
public class UserRegistrationProperties {

    @Min(1)
    private int initialPoints = 1000;
}
