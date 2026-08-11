package com.playball.kbopredictor.prediction.generation;

import com.playball.kbopredictor.prediction.history.PredictionStage;

public record SystemPredictionWriteResult(
        SystemPredictionGenerationResponse response,
        Long featureSnapshotId,
        PredictionStage stage
) {
    public boolean written() {
        return response.status() == SystemPredictionGenerationStatus.CREATED
                || response.status() == SystemPredictionGenerationStatus.UPDATED;
    }
}
