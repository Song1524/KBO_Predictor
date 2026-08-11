package com.playball.kbopredictor.prediction.service;

import com.playball.kbopredictor.prediction.dto.SystemPredictionResponse;
import com.playball.kbopredictor.prediction.entity.SystemPrediction;
import com.playball.kbopredictor.prediction.repository.SystemPredictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemPredictionService {

    private final SystemPredictionRepository systemPredictionRepository;

    public SystemPredictionResponse getPredictionByGameId(Long gameId) {

        SystemPrediction prediction = systemPredictionRepository
                .findByGameId(gameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "해당 경기의 시스템 예측 결과가 없습니다."
                ));

        return SystemPredictionResponse.from(prediction);
    }

    public Map<Long, SystemPredictionResponse> getPredictionsByGameIds(
            Collection<Long> gameIds
    ) {
        if (gameIds.isEmpty()) {
            return Map.of();
        }

        return systemPredictionRepository.findByGameIdIn(gameIds)
                .stream()
                .map(SystemPredictionResponse::from)
                .collect(Collectors.toMap(
                        SystemPredictionResponse::gameId,
                        Function.identity()
                ));
    }
}
