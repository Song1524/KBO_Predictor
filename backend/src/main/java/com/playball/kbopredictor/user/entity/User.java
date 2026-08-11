package com.playball.kbopredictor.user.entity;

import com.playball.kbopredictor.team.entity.Team;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(length = 100)
    private String nickname;

    @Column(length = 50)
    private String provider;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "favorite_team_id")
    private Team favoriteTeam;

    private Integer point;

    @Column(length = 50)
    private String role;

    @Column(length = 50)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static User createLocal(
            String email,
            String encodedPassword,
            String nickname,
            Team favoriteTeam,
            LocalDateTime now
    ) {
        User user = new User();
        user.email = email;
        user.password = encodedPassword;
        user.nickname = nickname;
        user.provider = "LOCAL";
        user.favoriteTeam = favoriteTeam;
        user.point = 0;
        user.role = "USER";
        user.status = "ACTIVE";
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public void changePoint(int pointChange) {
        this.point = Math.addExact(this.point, pointChange);
        this.updatedAt = LocalDateTime.now();
    }

}
