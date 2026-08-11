package com.playball.kbopredictor.stats.repository;

import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StartingPitcherRepository
        extends JpaRepository<StartingPitcher, Long> {

    Optional<StartingPitcher> findByGameIdAndSide(
            Long gameId,
            StartingPitcherSide side
    );
}
