package com.playball.kbopredictor.prediction.engine;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.prediction")
@Getter
@Setter
public class ActivePredictionModelProperties {

    private String activeModel = "baseline-v1";
}
