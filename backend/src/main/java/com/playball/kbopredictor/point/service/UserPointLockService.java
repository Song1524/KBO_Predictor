package com.playball.kbopredictor.point.service;

import com.playball.kbopredictor.user.entity.User;
import com.playball.kbopredictor.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserPointLockService {

    private final UserRepository userRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public User findByIdForUpdate(Long userId) {
        User reference = entityManager.getReference(User.class, userId);
        boolean alreadyLoaded = entityManager.getEntityManagerFactory()
                .getPersistenceUnitUtil()
                .isLoaded(reference);

        User lockedUser = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "사용자를 찾을 수 없습니다."
                ));

        if (alreadyLoaded) {
            // A locking query does not replace stale state already held in the
            // first-level cache, so reload it while keeping the write lock.
            entityManager.refresh(
                    lockedUser,
                    LockModeType.PESSIMISTIC_WRITE
            );
        }
        return lockedUser;
    }
}
