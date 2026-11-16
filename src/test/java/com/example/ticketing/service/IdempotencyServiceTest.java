package com.example.ticketing.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.example.ticketing.config.AppProperties;
import com.example.ticketing.entity.IdempotencyKey;
import com.example.ticketing.enums.IdempotencyStatus;
import com.example.ticketing.repository.IdempotencyKeyRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration IN_PROGRESS = Duration.ofSeconds(60);

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(null, null, new AppProperties.Idempotency(TTL, IN_PROGRESS), null);
        service = new IdempotencyService(repository, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static IdempotencyKey record(String endpoint, String hash, Instant expiresAt) {
        return new IdempotencyKey(1L, "k", endpoint, hash, NOW.minusSeconds(5), expiresAt);
    }

    @Test
    void unknownKeyIsClaimedWithInProgressTimeout() {
        when(repository.findByUserIdAndKeyValue(1L, "k")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(IdempotencyKey.class))).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyService.Claim claim = service.claim(1L, "k", "ep", "h");

        assertEquals(IdempotencyService.Outcome.NEW, claim.outcome());
        ArgumentCaptor<IdempotencyKey> saved = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals(IdempotencyStatus.IN_PROGRESS, saved.getValue().getStatus());
        assertEquals(NOW.plus(IN_PROGRESS), saved.getValue().getExpiresAt());
    }

    @Test
    void completedRecordWithSameRequestIsReplayed() {
        IdempotencyKey existing = record("ep", "h", NOW.plusSeconds(100));
        existing.complete(42L, "resp", NOW.plus(TTL));
        when(repository.findByUserIdAndKeyValue(1L, "k")).thenReturn(Optional.of(existing));

        IdempotencyService.Claim claim = service.claim(1L, "k", "ep", "h");

        assertEquals(IdempotencyService.Outcome.REPLAY, claim.outcome());
        assertEquals(42L, claim.resourceId());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void pendingRecordWithSameRequestIsInProgress() {
        when(repository.findByUserIdAndKeyValue(1L, "k"))
                .thenReturn(Optional.of(record("ep", "h", NOW.plusSeconds(30))));

        IdempotencyService.Claim claim = service.claim(1L, "k", "ep", "h");

        assertEquals(IdempotencyService.Outcome.IN_PROGRESS, claim.outcome());
        assertNull(claim.resourceId());
    }

    @Test
    void differentPayloadOrEndpointIsMismatch() {
        when(repository.findByUserIdAndKeyValue(1L, "k"))
                .thenReturn(Optional.of(record("ep", "h", NOW.plusSeconds(30))));

        assertEquals(IdempotencyService.Outcome.MISMATCH, service.claim(1L, "k", "ep", "other").outcome());
        assertEquals(IdempotencyService.Outcome.MISMATCH, service.claim(1L, "k", "other", "h").outcome());
    }

    @Test
    void expiredRecordIsDeletedAndKeyClaimedAgain() {
        IdempotencyKey stale = record("ep", "h", NOW.minusSeconds(1));
        when(repository.findByUserIdAndKeyValue(1L, "k")).thenReturn(Optional.of(stale));
        when(repository.saveAndFlush(any(IdempotencyKey.class))).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyService.Claim claim = service.claim(1L, "k", "ep", "different");

        assertEquals(IdempotencyService.Outcome.NEW, claim.outcome());
        verify(repository).delete(stale);
    }

    @Test
    void completeMarksRecordCompletedAndExtendsTtl() {
        IdempotencyKey pending = record("ep", "h", NOW.plusSeconds(30));
        when(repository.findById(7L)).thenReturn(Optional.of(pending));

        service.complete(7L, 99L, "resp");

        assertEquals(IdempotencyStatus.COMPLETED, pending.getStatus());
        assertEquals(99L, pending.getResourceId());
        assertEquals(NOW.plus(TTL), pending.getExpiresAt());
    }

    @Test
    void releaseDeletesOnlyInProgressRecords() {
        IdempotencyKey pending = record("ep", "h", NOW.plusSeconds(30));
        IdempotencyKey done = record("ep", "h", NOW.plusSeconds(30));
        done.complete(1L, "r", NOW.plus(TTL));
        when(repository.findById(1L)).thenReturn(Optional.of(pending));
        when(repository.findById(2L)).thenReturn(Optional.of(done));

        service.release(1L);
        service.release(2L);

        verify(repository).delete(pending);
        verify(repository, never()).delete(done);
    }

    @Test
    void purgeDelegatesToRepositoryWithCurrentTime() {
        when(repository.deleteByExpiresAtBefore(NOW)).thenReturn(3L);
        assertEquals(3L, service.purgeExpired());
    }
}
