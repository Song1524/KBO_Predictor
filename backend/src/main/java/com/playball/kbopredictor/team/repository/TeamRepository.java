package com.playball.kbopredictor.team.repository;

import com.playball.kbopredictor.team.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findAllByOrderByIdAsc();

    Optional<Team> findByKboTeamCode(String kboTeamCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select team from Team team where team.kboTeamCode = :teamCode")
    Optional<Team> findByKboTeamCodeForUpdate(
            @Param("teamCode") String teamCode
    );
}
