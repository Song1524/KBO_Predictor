package com.playball.kbopredictor.prediction.repository;

import com.playball.kbopredictor.prediction.entity.GameSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameSettlementRepository
        extends JpaRepository<GameSettlement, Long> {

    Optional<GameSettlement> findFirstByGameIdOrderByRevisionDesc(Long gameId);

    Optional<GameSettlement> findByGameIdAndRevision(
            Long gameId,
            int revision
    );

    long countByGameId(Long gameId);
}
