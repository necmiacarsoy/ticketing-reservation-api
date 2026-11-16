package com.example.ticketing.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.example.ticketing.config.AppProperties;
import com.example.ticketing.entity.IdempotencyKey;
import com.example.ticketing.enums.IdempotencyStatus;
import com.example.ticketing.repository.IdempotencyKeyRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    public enum Outcome {
        NEW,
        REPLAY,
        IN_PROGRESS,
        MISMATCH
    }

    public record Claim(Outcome outcome, Long keyId, Long resourceId) {

        static Claim created(Long keyId) {
            return new Claim(Outcome.NEW, keyId, null);
        }

        static Claim replay(Long resourceId) {
            return new Claim(Outcome.REPLAY, null, resourceId);
        }

        static Claim inProgress() {
            return new Claim(Outcome.IN_PROGRESS, null, null);
        }

        static Claim mismatch() {
            return new Claim(Outcome.MISMATCH, null, null);
        }
    }

    private final IdempotencyKeyRepository repository;
    private final AppProperties.Idempotency properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository repository, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties.idempotency();
        this.clock = clock;
    }

    @Transactional
    public Claim claim(Long userId, String key, String endpoint, String requestHash) {
        Instant now = clock.instant();
        Optional<IdempotencyKey> existing = repository.findByUserIdAndKeyValue(userId, key);
        if (existing.isPresent()) {
            IdempotencyKey record = existing.get();
            if (record.isExpired(now)) {
                repository.delete(record);
                repository.flush();
            } else if (!record.matches(endpoint, requestHash)) {
                return Claim.mismatch();
            } else if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                return Claim.replay(record.getResourceId());
            } else {
                return Claim.inProgress();
            }
        }
        IdempotencyKey created = repository.saveAndFlush(new IdempotencyKey(
                userId, key, endpoint, requestHash, now, now.plus(properties.inProgressTimeout())));
        return Claim.created(created.getId());
    }

    @Transactional
    public void complete(Long keyId, Long resourceId, String responseHash) {
        IdempotencyKey record = repository.findById(keyId)
                .orElseThrow(() -> new IllegalStateException("Idempotency record " + keyId + " vanished"));
        record.complete(resourceId, responseHash, clock.instant().plus(properties.ttl()));
    }

    @Transactional
    public void release(Long keyId) {
        repository.findById(keyId)
                .filter(record -> record.getStatus() == IdempotencyStatus.IN_PROGRESS)
                .ifPresent(repository::delete);
    }

    @Transactional
    public long purgeExpired() {
        return repository.deleteByExpiresAtBefore(clock.instant());
    }
}
