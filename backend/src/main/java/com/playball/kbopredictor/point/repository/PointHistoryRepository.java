package com.playball.kbopredictor.point.repository;

import com.playball.kbopredictor.point.entity.PointHistory;
import com.playball.kbopredictor.point.entity.PointHistoryType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PointHistoryRepository
        extends JpaRepository<PointHistory, Long> {

    List<PointHistory> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<PointHistory> findByUserPredictionIdAndType(
            Long userPredictionId,
            PointHistoryType type
    );

    Optional<PointHistory> findByUserPredictionIdAndSettlementIdAndType(
            Long userPredictionId,
            Long settlementId,
            PointHistoryType type
    );

    boolean existsByReversalOfId(Long pointHistoryId);
}
