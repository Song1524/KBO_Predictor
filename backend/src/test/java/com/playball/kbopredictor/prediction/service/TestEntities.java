package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import com.playball.kbopredictor.team.entity.Team;
import com.playball.kbopredictor.user.entity.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalTime;

public final class TestEntities {

    private TestEntities() {
    }

    public static Game game(
            Long id,
            GameStatus status,
            LocalDate date,
            LocalTime time
    ) {
        Game game = instantiate(Game.class);
        ReflectionTestUtils.setField(game, "id", id);
        ReflectionTestUtils.setField(game, "status", status);
        ReflectionTestUtils.setField(game, "gameDate", date);
        ReflectionTestUtils.setField(game, "gameTime", time);
        ReflectionTestUtils.setField(game, "homeTeam", team(1L, "홈팀"));
        ReflectionTestUtils.setField(game, "awayTeam", team(2L, "원정팀"));
        return game;
    }

    public static Team team(Long id, String name) {
        Team team = instantiate(Team.class);
        ReflectionTestUtils.setField(team, "id", id);
        ReflectionTestUtils.setField(team, "name", name);
        return team;
    }

    public static User user(Long id, int point) {
        User user = instantiate(User.class);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "nickname", "테스트 사용자");
        ReflectionTestUtils.setField(user, "point", point);
        return user;
    }

    public static void setResult(Game game, GameResult result) {
        ReflectionTestUtils.setField(game, "result", result);
        Team winner = switch (result) {
            case HOME_WIN -> game.getHomeTeam();
            case DRAW -> null;
            case AWAY_WIN -> game.getAwayTeam();
        };
        ReflectionTestUtils.setField(game, "winnerTeam", winner);
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
