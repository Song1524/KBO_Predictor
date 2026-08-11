package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;

public record GameUpsertResult(
        GameUpsertAction action,
        Long gameId,
        GameStatus previousStatus,
        GameStatus currentStatus,
        GameResult previousResult,
        GameResult currentResult,
        boolean terminalDataChanged
) {

    public boolean inserted() {
        return action == GameUpsertAction.INSERTED;
    }

    public boolean statusChanged() {
        return action == GameUpsertAction.UPDATED
                && previousStatus != currentStatus;
    }

    public boolean reachedFinished() {
        return currentStatus == GameStatus.FINISHED
                && previousStatus != GameStatus.FINISHED;
    }

    public boolean reachedCancelled() {
        return currentStatus == GameStatus.CANCELLED
                && previousStatus != GameStatus.CANCELLED;
    }

    public boolean reachedTerminalStatus() {
        return reachedFinished() || reachedCancelled();
    }

    public boolean currentlyTerminal() {
        return currentStatus == GameStatus.FINISHED
                || currentStatus == GameStatus.CANCELLED;
    }
}
