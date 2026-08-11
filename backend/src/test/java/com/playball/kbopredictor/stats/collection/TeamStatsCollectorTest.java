package com.playball.kbopredictor.stats.collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamStatsCollectorTest {

    @Test
    void collectsAllTenTeamsOnlyAfterAllOfficialPagesSucceed() {
        OfficialTeamStatsSource source = mock(OfficialTeamStatsSource.class);
        when(source.fetchStandings()).thenReturn(
                OfficialTeamStatsParserTest.standingsFixture()
        );
        when(source.fetchTeamBatting()).thenReturn(
                OfficialTeamStatsParserTest.metricFixture("HRA_RT", "0.270")
        );
        when(source.fetchTeamPitching()).thenReturn(
                OfficialTeamStatsParserTest.metricFixture("ERA_RT", "4.90")
        );
        TeamStatsCollector collector = new TeamStatsCollector(
                source,
                new OfficialTeamStatsParser()
        );

        assertThat(collector.collect()).hasSize(10);
    }

    @Test
    void externalFailureIsPropagatedBeforeAnyDatabaseWork() {
        OfficialTeamStatsSource source = mock(OfficialTeamStatsSource.class);
        when(source.fetchStandings()).thenThrow(
                new PregameDataCollectionException("network")
        );
        TeamStatsCollector collector = new TeamStatsCollector(
                source,
                new OfficialTeamStatsParser()
        );

        assertThatThrownBy(collector::collect)
                .isInstanceOf(PregameDataCollectionException.class)
                .hasMessageContaining("network");
    }
}
