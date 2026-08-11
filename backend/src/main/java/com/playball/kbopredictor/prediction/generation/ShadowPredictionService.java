package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.prediction.engine.LogisticModelArtifactLoader;
import com.playball.kbopredictor.prediction.engine.PredictionEngine;
import com.playball.kbopredictor.prediction.feature.PredictionFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ShadowPredictionService {

    private final PredictionEngine logisticEngine;
    private final LogisticModelArtifactLoader artifactLoader;
    private final ShadowPredictionWriter writer;

    public ShadowPredictionService(
            @Qualifier("logisticRegressionPredictionEngine")
            PredictionEngine logisticEngine,
            LogisticModelArtifactLoader artifactLoader,
            ShadowPredictionWriter writer
    ) {
        this.logisticEngine = logisticEngine;
        this.artifactLoader = artifactLoader;
        this.writer = writer;
    }

    public boolean generate(
            PredictionFeatures features,
            SystemPredictionWriteResult operationalWrite
    ) {
        if (!operationalWrite.written()) return false;
        var result = logisticEngine.predict(features);
        boolean stored = writer.write(
                features.gameId(),
                operationalWrite.featureSnapshotId(),
                operationalWrite.stage(),
                result,
                artifactLoader.artifactSha256(),
                operationalWrite.response().generatedAt()
        );
        if (stored) {
            log.info(
                    "Shadow prediction stored: gameId={}, model={}, snapshotId={}, stage={}, artifactSha256={}",
                    features.gameId(), result.modelVersion(),
                    operationalWrite.featureSnapshotId(),
                    operationalWrite.stage(), artifactLoader.artifactSha256()
            );
        }
        return stored;
    }
}
