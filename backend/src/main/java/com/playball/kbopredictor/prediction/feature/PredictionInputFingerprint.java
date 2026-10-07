package com.playball.kbopredictor.prediction.feature;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned canonical record encoding; decimal scale and collection time do not affect identity. */
public final class PredictionInputFingerprint {
    private PredictionInputFingerprint() {}

    public static String of(PredictionFeatures features) {
        StringBuilder input = new StringBuilder("prediction-input-v1:");
        append(input, features);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("null;");
        } else if (value.getClass().isRecord()) {
            target.append(value.getClass().getSimpleName()).append('{');
            for (var component : value.getClass().getRecordComponents()) {
                target.append(component.getName()).append('=');
                try {
                    append(target, component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Cannot fingerprint prediction input", exception);
                }
            }
            target.append('}');
        } else {
            String text = value instanceof BigDecimal decimal
                    ? decimal.stripTrailingZeros().toPlainString() : value.toString();
            target.append(text.length()).append(':').append(text).append(';');
        }
    }
}
