package com.playball.kbopredictor.stats.repository;

import com.playball.kbopredictor.stats.entity.StartingPitcher;
import com.playball.kbopredictor.stats.entity.StartingPitcherSide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StartingPitcherRepository
        extends JpaRepository<StartingPitcher, Long> {

    Optional<StartingPitcher> findByGameIdAndSide(
            Long gameId,
            StartingPitcherSide side
    );

    @Query("""
            select startingPitcher
            from StartingPitcher startingPitcher
            join fetch startingPitcher.player
            where startingPitcher.game.id in :gameIds
            order by startingPitcher.game.id asc, startingPitcher.side asc
            """)
    List<StartingPitcher> findByGameIdInWithPlayer(
            @Param("gameIds") Collection<Long> gameIds
    );
}
