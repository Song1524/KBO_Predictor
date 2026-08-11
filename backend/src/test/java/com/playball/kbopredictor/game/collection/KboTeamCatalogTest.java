package com.playball.kbopredictor.game.collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KboTeamCatalogTest {

    @Test
    void historicalAliasesMapToTheCurrentFranchiseCode() {
        assertThat(KboTeamCatalog.codeOf("SSG 랜더스")).isEqualTo("SK");
        assertThat(KboTeamCatalog.codeOf("SK")).isEqualTo("SK");
        assertThat(KboTeamCatalog.codeOf("키움 히어로즈")).isEqualTo("WO");
        assertThat(KboTeamCatalog.codeOf("넥센")).isEqualTo("WO");
        assertThat(KboTeamCatalog.supportedTeamCodes()).hasSize(10);
    }

    @Test
    void eventTeamsAreNotSilentlyCreatedAsRegularTeams() {
        assertThatThrownBy(() -> KboTeamCatalog.codeOf("나눔"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
