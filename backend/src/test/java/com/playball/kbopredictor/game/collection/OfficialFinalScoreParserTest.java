package com.playball.kbopredictor.game.collection;

import com.playball.kbopredictor.game.entity.GameResult;
import com.playball.kbopredictor.game.entity.GameStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialFinalScoreParserTest {

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 12);

    private OfficialFinalScoreParser parser;

    @BeforeEach
    void setUp() {
        parser = new OfficialFinalScoreParser(new ObjectMapper());
    }

    @Test
    void parsesAllFiveOfficialResultsAndTheirOutcomes() {
        OfficialFinalScoreBatch batch = parser.parse("""
                {"code":"100","game":[
                  {"G_DT":"20260812","G_ID":"20260812HHOB0","AWAY_ID":"HH","HOME_ID":"OB","GAME_STATE_SC":"3","GAME_RESULT_CK":1,"SCORE_CK":"1","CANCEL_SC_ID":"0","T_SCORE_CN":4,"B_SCORE_CN":3},
                  {"G_DT":"20260812","G_ID":"20260812LTSK0","AWAY_ID":"LT","HOME_ID":"SK","GAME_STATE_SC":"3","GAME_RESULT_CK":1,"SCORE_CK":"1","CANCEL_SC_ID":"0","T_SCORE_CN":1,"B_SCORE_CN":9},
                  {"G_DT":"20260812","G_ID":"20260812SSHT0","AWAY_ID":"SS","HOME_ID":"HT","GAME_STATE_SC":"3","GAME_RESULT_CK":1,"SCORE_CK":"1","CANCEL_SC_ID":"0","T_SCORE_CN":2,"B_SCORE_CN":7},
                  {"G_DT":"20260812","G_ID":"20260812KTNC0","AWAY_ID":"KT","HOME_ID":"NC","GAME_STATE_SC":"3","GAME_RESULT_CK":1,"SCORE_CK":"1","CANCEL_SC_ID":"0","T_SCORE_CN":0,"B_SCORE_CN":3},
                  {"G_DT":"20260812","G_ID":"20260812LGWO0","AWAY_ID":"LG","HOME_ID":"WO","GAME_STATE_SC":"3","GAME_RESULT_CK":1,"SCORE_CK":"1","CANCEL_SC_ID":"0","T_SCORE_CN":3,"B_SCORE_CN":4}
                ]}
                """, TARGET_DATE);

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.scoresByExternalGameId()).hasSize(5);
        assertScore(batch, "20260812HHOB0", 4, 3, GameResult.AWAY_WIN);
        assertScore(batch, "20260812LTSK0", 1, 9, GameResult.HOME_WIN);
        assertScore(batch, "20260812SSHT0", 2, 7, GameResult.HOME_WIN);
        assertScore(batch, "20260812KTNC0", 0, 3, GameResult.HOME_WIN);
        assertScore(batch, "20260812LGWO0", 3, 4, GameResult.HOME_WIN);
    }

    @Test
    void validZeroZeroIsConfirmedAsDraw() {
        OfficialFinalScoreBatch batch = parser.parse(gameJson(
                "1", "1", 0, 0
        ), TARGET_DATE);

        assertScore(batch, "20260812LGKT0", 0, 0, GameResult.DRAW);
    }

    @Test
    void missingOrMalformedScoreIsNeverConvertedToZero() {
        OfficialFinalScoreBatch missing = parser.parse(gameJson(
                "1", "1", null, 3
        ), TARGET_DATE);
        OfficialFinalScoreBatch malformed = parser.parse(gameJson(
                "1", "1", "not-a-score", 3
        ), TARGET_DATE);

        assertThat(missing.scoresByExternalGameId()).isEmpty();
        assertThat(missing.errors()).singleElement().asString()
                .contains("T_SCORE_CN is missing");
        assertThat(malformed.scoresByExternalGameId()).isEmpty();
        assertThat(malformed.errors()).singleElement().asString()
                .contains("T_SCORE_CN is not an integer");
    }

    @Test
    void finishedStatusWithoutResultAndScoreChecksIsNotConfirmed() {
        OfficialFinalScoreBatch batch = parser.parse(gameJson(
                "0", "0", 0, 0
        ), TARGET_DATE);

        assertThat(batch.scoresByExternalGameId()).isEmpty();
        assertThat(batch.errors()).isEmpty();
    }

    @Test
    void pregameScoreCheckDoesNotOverrideOfficialScheduledState() {
        LocalDate date = LocalDate.of(2026, 8, 13);
        OfficialFinalScoreBatch batch = parser.parse("""
                {"code":"100","game":[
                  {"G_DT":"20260813","G_ID":"20260813HHOB0","AWAY_ID":"HH","HOME_ID":"OB","GAME_STATE_SC":"1","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":1,"GAME_TB_SC":"T","T_SCORE_CN":"0","B_SCORE_CN":"0"},
                  {"G_DT":"20260813","G_ID":"20260813LTSK0","AWAY_ID":"LT","HOME_ID":"SK","GAME_STATE_SC":"1","GAME_RESULT_CK":0,"SCORE_CK":"0","CANCEL_SC_ID":"0","GAME_INN_NO":null,"GAME_TB_SC":null,"T_SCORE_CN":"0","B_SCORE_CN":"0"},
                  {"G_DT":"20260813","G_ID":"20260813SSHT0","AWAY_ID":"SS","HOME_ID":"HT","GAME_STATE_SC":"1","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":1,"GAME_TB_SC":"T","T_SCORE_CN":"0","B_SCORE_CN":"0"},
                  {"G_DT":"20260813","G_ID":"20260813KTNC0","AWAY_ID":"KT","HOME_ID":"NC","GAME_STATE_SC":"1","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":1,"GAME_TB_SC":"T","T_SCORE_CN":"0","B_SCORE_CN":"0"},
                  {"G_DT":"20260813","G_ID":"20260813LGWO0","AWAY_ID":"LG","HOME_ID":"WO","GAME_STATE_SC":"1","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":1,"GAME_TB_SC":"T","T_SCORE_CN":"0","B_SCORE_CN":"0"}
                ]}
                """, date);

        assertThat(batch.errors()).isEmpty();
        assertThat(batch.scoresByExternalGameId()).isEmpty();
        assertThat(batch.statesByExternalGameId()).hasSize(5);
        assertThat(batch.statesByExternalGameId().values())
                .allMatch(state -> state.status() == GameStatus.SCHEDULED);
        assertThat(batch.statesByExternalGameId().values())
                .allMatch(state -> state.awayScore() == null
                        && state.homeScore() == null);
    }

    @Test
    void liveStateProvidesZeroZeroAndActualScores() {
        LocalDate date = LocalDate.of(2026, 8, 13);
        OfficialFinalScoreBatch batch = parser.parse("""
                {"code":"100","game":[
                  {"G_DT":"20260813","G_ID":"20260813HHOB0","AWAY_ID":"HH","HOME_ID":"OB","GAME_STATE_SC":"2","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":3,"GAME_TB_SC":"T","T_SCORE_CN":"0","B_SCORE_CN":"2"},
                  {"G_DT":"20260813","G_ID":"20260813LTSK0","AWAY_ID":"LT","HOME_ID":"SK","GAME_STATE_SC":"2","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":3,"GAME_TB_SC":"B","T_SCORE_CN":"3","B_SCORE_CN":"0"},
                  {"G_DT":"20260813","G_ID":"20260813SSHT0","AWAY_ID":"SS","HOME_ID":"HT","GAME_STATE_SC":"2","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":4,"GAME_TB_SC":"T","T_SCORE_CN":"0","B_SCORE_CN":"0"},
                  {"G_DT":"20260813","G_ID":"20260813KTNC0","AWAY_ID":"KT","HOME_ID":"NC","GAME_STATE_SC":"2","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":3,"GAME_TB_SC":"B","T_SCORE_CN":"3","B_SCORE_CN":"0"},
                  {"G_DT":"20260813","G_ID":"20260813LGWO0","AWAY_ID":"LG","HOME_ID":"WO","GAME_STATE_SC":"2","GAME_RESULT_CK":0,"SCORE_CK":"1","CANCEL_SC_ID":"0","GAME_INN_NO":3,"GAME_TB_SC":"B","T_SCORE_CN":"1","B_SCORE_CN":"3"}
                ]}
                """, date);

        OfficialGameState hanwhaDoosan = batch.statesByExternalGameId()
                .get("20260813HHOB0");
        OfficialGameState lotteSsg = batch.statesByExternalGameId()
                .get("20260813LTSK0");
        OfficialGameState zeroZero = batch.statesByExternalGameId()
                .get("20260813SSHT0");
        OfficialGameState scored = batch.statesByExternalGameId()
                .get("20260813KTNC0");
        OfficialGameState lgKiwoom = batch.statesByExternalGameId()
                .get("20260813LGWO0");

        assertThat(batch.statesByExternalGameId()).hasSize(5);
        assertThat(hanwhaDoosan.awayScore()).isZero();
        assertThat(hanwhaDoosan.homeScore()).isEqualTo(2);
        assertThat(lotteSsg.awayScore()).isEqualTo(3);
        assertThat(lotteSsg.homeScore()).isZero();
        assertThat(zeroZero.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(zeroZero.awayScore()).isZero();
        assertThat(zeroZero.homeScore()).isZero();
        assertThat(scored.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(scored.awayScore()).isEqualTo(3);
        assertThat(scored.homeScore()).isZero();
        assertThat(lgKiwoom.awayScore()).isEqualTo(1);
        assertThat(lgKiwoom.homeScore()).isEqualTo(3);
        assertThat(batch.statesByExternalGameId().values())
                .allMatch(state -> state.status() == GameStatus.IN_PROGRESS);
        assertThat(batch.scoresByExternalGameId()).isEmpty();
    }

    @Test
    void responseWithoutGameArrayFailsAsAWhole() {
        assertThatThrownBy(() -> parser.parse("{}", TARGET_DATE))
                .isInstanceOf(GameDataCollectionException.class)
                .hasMessageContaining("game array");
    }

    private String gameJson(
            String resultCheck,
            String scoreCheck,
            Object awayScore,
            Object homeScore
    ) {
        return """
                {"code":"100","game":[{
                  "G_DT":"20260812","G_ID":"20260812LGKT0",
                  "AWAY_ID":"LG","HOME_ID":"KT","GAME_STATE_SC":"3",
                  "GAME_RESULT_CK":"%s","SCORE_CK":"%s","CANCEL_SC_ID":"0",
                  "T_SCORE_CN":%s,"B_SCORE_CN":%s
                }]}
                """.formatted(
                resultCheck,
                scoreCheck,
                jsonValue(awayScore),
                jsonValue(homeScore)
        );
    }

    private String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof Number ? value.toString() : "\"" + value + "\"";
    }

    private void assertScore(
            OfficialFinalScoreBatch batch,
            String gameId,
            int awayScore,
            int homeScore,
            GameResult result
    ) {
        assertThat(batch.scoresByExternalGameId().get(gameId))
                .isEqualTo(new OfficialFinalScore(
                        gameId,
                        batch.scoresByExternalGameId().get(gameId).awayTeamCode(),
                        batch.scoresByExternalGameId().get(gameId).homeTeamCode(),
                        awayScore,
                        homeScore,
                        result
                ));
    }
}
