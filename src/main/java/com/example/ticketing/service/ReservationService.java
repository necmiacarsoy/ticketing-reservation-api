package com.example.ticketing.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import com.example.ticketing.dto.ReservationDtos.ReservationResponse;
import com.example.ticketing.dto.ReservationDtos.ReservationResult;
import com.example.ticketing.entity.Event;
import com.example.ticketing.entity.Reservation;
import com.example.ticketing.enums.AuditAction;
import com.example.ticketing.enums.ReservationStatus;
import com.example.ticketing.exception.ApiException;
import com.example.ticketing.exception.ErrorCode;
import com.example.ticketing.repository.EventRepository;
import com.example.ticketing.repository.ReservationRepository;
import com.example.ticketing.security.AuthenticatedUser;
import com.example.ticketing.util.Hashing;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationService {

    private static final String RESOURCE = "RESERVATION";
    private static final int MAX_KEY_LENGTH = 255;
    private static final int CLAIM_ATTEMPTS = 3;
    private static final List<ReservationStatus> SEAT_HOLDING =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private final EventRepository events;
    private final ReservationRepository reservations;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReservationService(EventRepository events, ReservationRepository reservations,
                              IdempotencyService idempotency, AuditService audit,
                              PlatformTransactionManager transactionManager, Clock clock,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this.events = events;
        this.reservations = reservations;
        this.idempotency = idempotency;
        this.audit = audit;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meters = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
    }

    public ReservationResult create(AuthenticatedUser actor, Long eventId, int seats, String idempotencyKey) {
        String key = validateKey(idempotencyKey);
        String endpoint = "POST /api/events/" + eventId + "/reservations";
        String requestHash = Hashing.sha256Hex(actor.id() + "|" + endpoint + "|" + seats);

        IdempotencyService.Claim claim = claimWithRetry(actor.id(), key, endpoint, requestHash);
        return switch (claim.outcome()) {
            case REPLAY -> new ReservationResult(replay(claim.resourceId()), true);
            case IN_PROGRESS -> throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "A request with this Idempotency-Key is still being processed; retry shortly");
            case MISMATCH -> throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request");
            case NEW -> new ReservationResult(createNew(actor, eventId, seats, claim.keyId()), false);
        };
    }

    @Transactional
    public ReservationResponse confirm(AuthenticatedUser actor, Long reservationId) {
        Reservation reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> ApiException.notFound("Reservation", reservationId));
        if (!actor.isAdmin() && !actor.owns(reservation.getUserId())) {
            throw ApiException.forbidden("Only the reservation owner or an admin can confirm it");
        }
        if (reservation.confirm()) {
            audit.record(actor.id(), AuditAction.RESERVATION_CONFIRMED, RESOURCE, reservationId);
        }
        return ReservationResponse.from(reservation);
    }

    @Transactional
    public ReservationResponse cancel(AuthenticatedUser actor, Long reservationId) {
        Reservation reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> ApiException.notFound("Reservation", reservationId));
        if (!actor.isAdmin() && !actor.owns(reservation.getUserId()) && !ownsEvent(actor, reservation)) {
            throw ApiException.forbidden("Only the reservation owner, the event owner or an admin can cancel it");
        }
        if (reservation.cancel()) {
            audit.record(actor.id(), AuditAction.RESERVATION_CANCELLED, RESOURCE, reservationId);
        }
        return ReservationResponse.from(reservation);
    }

    private ReservationResponse createNew(AuthenticatedUser actor, Long eventId, int seats, Long keyId) {
        Reservation created;
        try {
            created = Objects.requireNonNull(
                    transactionTemplate.execute(status -> reserveSeats(actor, eventId, seats, keyId)));
        } catch (RuntimeException ex) {
            // Business transaction rolled back (sold out, not found, ...): free the key so the client may retry.
            idempotency.release(keyId);
            throw ex;
        }
        meters.counter("ticketing.reservations.created").increment();
        return ReservationResponse.from(created);
    }

    private Reservation reserveSeats(AuthenticatedUser actor, Long eventId, int seats, Long keyId) {
        Event event = events.findByIdForUpdate(eventId).orElseThrow(() -> ApiException.notFound("Event", eventId));
        if (!event.isPublished()) {
            throw ApiException.invalidState("Event is not published yet");
        }
        if (!event.getEndsAt().isAfter(clock.instant())) {
            throw ApiException.invalidState("Event has already ended");
        }
        long held = reservations.sumSeats(eventId, SEAT_HOLDING);
        long available = event.getCapacity() - held;
        if (seats > available) {
            meters.counter("ticketing.reservations.sold_out").increment();
            throw new ApiException(ErrorCode.SOLD_OUT,
                    available <= 0 ? "Event is sold out" : "Only " + available + " seat(s) left");
        }
        Reservation reservation = reservations.saveAndFlush(new Reservation(eventId, actor.id(), seats));
        idempotency.complete(keyId, reservation.getId(), Hashing.sha256Hex(
                reservation.getId() + "|" + reservation.getEventId() + "|" + reservation.getSeats()));
        audit.record(actor.id(), AuditAction.RESERVATION_CREATED, RESOURCE, reservation.getId());
        return reservation;
    }

    private IdempotencyService.Claim claimWithRetry(Long userId, String key, String endpoint, String requestHash) {
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            try {
                return idempotency.claim(userId, key, endpoint, requestHash);
            } catch (DataIntegrityViolationException raced) {
                // A concurrent request inserted the same key first; look again and observe its record.
            }
        }
        throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "A request with this Idempotency-Key is still being processed; retry shortly");
    }

    private ReservationResponse replay(Long reservationId) {
        return reservations.findById(reservationId)
                .map(ReservationResponse::from)
                .orElseThrow(() -> ApiException.notFound("Reservation", reservationId));
    }

    private boolean ownsEvent(AuthenticatedUser actor, Reservation reservation) {
        return events.findById(reservation.getEventId())
                .map(event -> actor.owns(event.getOwnerId()))
                .orElse(false);
    }

    private static String validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("Idempotency-Key header must not be blank");
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return trimmed;
    }
}
