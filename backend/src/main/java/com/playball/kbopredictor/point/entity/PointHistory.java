package com.playball.kbopredictor.point.entity;

import com.playball.kbopredictor.game.entity.Game;
import com.playball.kbopredictor.prediction.entity.UserPrediction;
import com.playball.kbopredictor.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "point_histories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_point_histories_prediction_type",
                columnNames = {"user_prediction_id", "type"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id")
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_prediction_id")
    private UserPrediction userPrediction;

    @Column(name = "point_change", nullable = false)
    private int pointChange;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PointHistoryType type;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static PointHistory create(
            User user,
            Game game,
            UserPrediction userPrediction,
            int pointChange,
            int balanceAfter,
            PointHistoryType type,
            String description,
            LocalDateTime createdAt
    ) {
        PointHistory history = new PointHistory();
        history.user = user;
        history.game = game;
        history.userPrediction = userPrediction;
        history.pointChange = pointChange;
        history.balanceAfter = balanceAfter;
        history.type = type;
        history.description = description;
        history.createdAt = createdAt;
        return history;
    }
}
