package com.playball.kbopredictor.prediction.engine;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogisticModelArtifactLoaderHashTest {

    @Test
    void rejectsArtifactWhenPinnedSha256DoesNotMatch() {
        assertThatThrownBy(() -> new LogisticModelArtifactLoader(
                new ObjectMapper(),
                new FileSystemResource("ml/artifacts/logistic-v1.json"),
                "0000000000000000000000000000000000000000000000000000000000000000"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256 mismatch");
    }
}
