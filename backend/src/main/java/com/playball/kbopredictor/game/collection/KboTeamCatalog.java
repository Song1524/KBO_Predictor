package com.playball.kbopredictor.game.collection;

import java.util.Map;
import java.util.Set;

public final class KboTeamCatalog {

    private static final Map<String, String> TEAM_CODES = Map.ofEntries(
            Map.entry("LG", "LG"),
            Map.entry("한화", "HH"),
            Map.entry("SSG", "SK"),
            Map.entry("삼성", "SS"),
            Map.entry("NC", "NC"),
            Map.entry("KT", "KT"),
            Map.entry("롯데", "LT"),
            Map.entry("KIA", "HT"),
            Map.entry("두산", "OB"),
            Map.entry("키움", "WO"),
            Map.entry("LG 트윈스", "LG"),
            Map.entry("한화 이글스", "HH"),
            Map.entry("SSG 랜더스", "SK"),
            Map.entry("SK", "SK"),
            Map.entry("삼성 라이온즈", "SS"),
            Map.entry("NC 다이노스", "NC"),
            Map.entry("KT 위즈", "KT"),
            Map.entry("롯데 자이언츠", "LT"),
            Map.entry("KIA 타이거즈", "HT"),
            Map.entry("두산 베어스", "OB"),
            Map.entry("키움 히어로즈", "WO"),
            Map.entry("넥센", "WO")
    );

    private KboTeamCatalog() {
    }

    public static String codeOf(String externalTeamName) {
        String code = TEAM_CODES.get(externalTeamName);
        if (code == null) {
            throw new IllegalArgumentException(
                    "알 수 없는 KBO 팀명입니다: " + externalTeamName
            );
        }
        return code;
    }

    public static Set<String> supportedTeamCodes() {
        return Set.copyOf(TEAM_CODES.values());
    }
}
