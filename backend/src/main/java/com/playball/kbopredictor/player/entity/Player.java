package com.playball.kbopredictor.player.entity;

import com.playball.kbopredictor.team.entity.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "players")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kbo_player_id", nullable = false, length = 20, unique = true)
    private String kboPlayerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static Player create(
            String kboPlayerId,
            Team team,
            String name,
            LocalDateTime now
    ) {
        Player player = new Player();
        player.kboPlayerId = kboPlayerId;
        player.createdAt = now;
        player.update(team, name, now);
        return player;
    }

    public void update(Team team, String name, LocalDateTime now) {
        this.team = team;
        this.name = name;
        this.updatedAt = now;
    }
}
