package com.playball.kbopredictor.prediction.feature;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class PredictionInputFingerprintTest {
    @Test
    void equivalentDecimalScalesHaveStableHash() {
        assertThat(PredictionInputFingerprint.of(input("0.50", null)))
                .isEqualTo(PredictionInputFingerprint.of(input("0.500", null)));
    }

    @Test
    void sameDateValueCorrectionChangesHash() {
        assertThat(PredictionInputFingerprint.of(input("0.50", null)))
                .isNotEqualTo(PredictionInputFingerprint.of(input("0.60", null)));
    }

    @Test
    void absentInputAndZeroInputAreDistinct() {
        assertThat(PredictionInputFingerprint.of(input(null, null)))
                .isNotEqualTo(PredictionInputFingerprint.of(input("0", null)));
    }

    @Test
    void pitcherValuesOutsideSnapshotColumnsStillChangeHash() {
        LocalDate date = LocalDate.of(2026, 10, 7);
        var first = new StartingPitcherFeatures(1L, "123", "pitcher", true, true, date,
                new BigDecimal("3.20"), 1, 0, "20", new BigDecimal("1.20"));
        var changed = new StartingPitcherFeatures(1L, "123", "pitcher", true, true, date,
                new BigDecimal("3.20"), 2, 0, "20", new BigDecimal("1.20"));
        assertThat(PredictionInputFingerprint.of(input("0.50", first)))
                .isNotEqualTo(PredictionInputFingerprint.of(input("0.50", changed)));
    }

    private PredictionFeatures input(String rate, StartingPitcherFeatures pitcher) {
        LocalDate date = LocalDate.of(2026, 10, 7);
        var team = new TeamPredictionFeatures(1L, "team", true, date,
                rate == null ? null : new BigDecimal(rate), null, null, null, null,
                null, null, null, null, null, pitcher);
        return new PredictionFeatures(1L, date, date.atTime(18, 30), team, team);
    }
}
