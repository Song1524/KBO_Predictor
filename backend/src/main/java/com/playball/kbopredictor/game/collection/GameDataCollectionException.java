package com.playball.kbopredictor.game.collection;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class GameDataCollectionException extends RuntimeException {

    public GameDataCollectionException(String message) {
        super(message);
    }

    public GameDataCollectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
