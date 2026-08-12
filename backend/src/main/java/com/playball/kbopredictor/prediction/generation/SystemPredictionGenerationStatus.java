package com.playball.kbopredictor.prediction.generation;

public enum SystemPredictionGenerationStatus {
    CREATED,
    UPDATED,
    SKIPPED_UP_TO_DATE,
    SKIPPED_CLOSED,
    SKIPPED_NOT_SCHEDULED,
    FAILED
}
