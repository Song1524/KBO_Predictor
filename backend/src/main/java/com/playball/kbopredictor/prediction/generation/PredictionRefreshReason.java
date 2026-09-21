package com.playball.kbopredictor.prediction.generation;

public enum PredictionRefreshReason {
    DATA_REFRESH(null),
    STARTER_ACQUIRED("[선발 최초 확보] 공식 KBO 예고 선발을 예측에 반영했습니다."),
    STARTER_CHANGED("[선발 교체] 공식 KBO 예고 선발 변경을 예측에 반영했습니다.");

    private final String historyReason;

    PredictionRefreshReason(String historyReason) {
        this.historyReason = historyReason;
    }

    public String historyReason() {
        return historyReason;
    }
}
