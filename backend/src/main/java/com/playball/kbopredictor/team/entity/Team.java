package com.playball.kbopredictor.team.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "teams")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kbo_team_code", length = 10, unique = true)
    private String kboTeamCode;

    @Column(length = 100)
    private String name;

    @Column(name = "short_name", length = 50)
    private String shortName;

    @Column(name = "primary_color", length = 50)
    private String primaryColor;

    @Column(name = "secondary_color", length = 50)
    private String secondaryColor;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
