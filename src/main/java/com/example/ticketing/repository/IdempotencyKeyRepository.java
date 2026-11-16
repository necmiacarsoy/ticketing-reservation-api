package com.example.ticketing.repository;

import java.time.Instant;
import java.util.Optional;

import com.example.ticketing.entity.IdempotencyKey;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndKeyValue(Long userId, String keyValue);

    long deleteByExpiresAtBefore(Instant instant);
}
