package com.playball.kbopredictor.stats.entity;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.player.entity.Player;
import com.playball.kbopredictor.team.entity.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDateTime;

@Entity
@Table(
        name = "starting_pitchers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_starting_pitchers_game_side",
                columnNames = {"game_id", "side"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StartingPitcher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StartingPitcherSide side;

    @Column(name = "first_collected_at", nullable = false)
    private LocalDateTime firstCollectedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static StartingPitcher create(
            Game game,
            Team team,
            Player player,
            StartingPitcherSide side,
            LocalDateTime now
    ) {
        StartingPitcher startingPitcher = new StartingPitcher();
        startingPitcher.game = game;
        startingPitcher.side = side;
        startingPitcher.firstCollectedAt = now;
        startingPitcher.update(team, player, now);
        return startingPitcher;
    }

    public void update(Team team, Player player, LocalDateTime now) {
        this.team = team;
        this.player = player;
        this.updatedAt = now;
    }
}
