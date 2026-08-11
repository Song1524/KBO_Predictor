package com.playball.kbopredictor.stats.entity;

import com.playball.kbopredictor.player.entity.Player;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "pitcher_stats",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pitcher_stats_player_date",
                columnNames = {"player_id", "season", "stat_date"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PitcherStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(nullable = false)
    private Integer season;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(precision = 5, scale = 2)
    private BigDecimal era;

    @Column(name = "win")
    private Integer wins;

    @Column(name = "lose")
    private Integer losses;

    @Column(length = 20)
    private String innings;

    @Column(precision = 5, scale = 2)
    private BigDecimal whip;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    public static PitcherStat create(
            Player player,
            Integer season,
            LocalDate statDate
    ) {
        PitcherStat stat = new PitcherStat();
        stat.player = player;
        stat.season = season;
        stat.statDate = statDate;
        return stat;
    }

    public void update(
            BigDecimal era,
            Integer wins,
            Integer losses,
            String innings,
            BigDecimal whip,
            LocalDateTime collectedAt
    ) {
        this.era = era;
        this.wins = wins;
        this.losses = losses;
        this.innings = innings;
        this.whip = whip;
        this.collectedAt = collectedAt;
    }
}
