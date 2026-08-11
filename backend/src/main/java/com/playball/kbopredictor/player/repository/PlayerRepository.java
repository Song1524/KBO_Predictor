package com.playball.kbopredictor.player.repository;

import com.playball.kbopredictor.player.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByKboPlayerId(String kboPlayerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select player from Player player where player.kboPlayerId = :playerId")
    Optional<Player> findByKboPlayerIdForUpdate(
            @Param("playerId") String playerId
    );
}
