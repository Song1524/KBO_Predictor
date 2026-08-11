package com.playball.kbopredictor.point.repository;

import com.playball.kbopredictor.point.entity.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointHistoryRepository
        extends JpaRepository<PointHistory, Long> {

    List<PointHistory> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
