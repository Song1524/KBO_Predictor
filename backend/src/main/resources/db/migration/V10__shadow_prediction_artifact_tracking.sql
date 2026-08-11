ALTER TABLE system_prediction_histories
    ADD COLUMN model_artifact_hash VARCHAR(64) NULL AFTER model_version;
