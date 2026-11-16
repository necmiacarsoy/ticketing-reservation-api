package com.example.ticketing.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.ticketing.entity.Event;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.AuditAction;
import com.example.ticketing.enums.ReservationStatus;
import com.example.ticketing.enums.Role;
import com.example.ticketing.repository.IdempotencyKeyRepository;
import com.example.ticketing.support.IntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private long heldSeats(Event event) {
        return reservationRepository.sumSeats(event.getId(),
                List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED));
    }

    @Test
    void customerReservesSeatsAndGetsPendingReservation() throws Exception {
        User owner = createUser("owner", Role.ORGANIZER);
        User customer = createUser("cust", Role.CUSTOMER);
        Event event = createPublishedEvent(owner, 10);

        reserve(tokenFor(customer), event.getId(), 3, key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.seats").value(3))
                .andExpect(jsonPath("$.userId").value(customer.getId()))
                .andExpect(jsonPath("$.eventId").value(event.getId()));
        assertEquals(3, heldSeats(event));
    }

    @Test
    void idempotencyKeyHeaderIsRequired() throws Exception {
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 10);
        reserve(tokenFor(createUser("cust", Role.CUSTOMER)), event.getId(), 1, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void sameKeyAndPayloadReplaysTheOriginalReservationWithoutConsumingMoreSeats() throws Exception {
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 10);
        String token = tokenFor(createUser("cust", Role.CUSTOMER));
        String key = key();

        var first = reserve(token, event.getId(), 2, key).andExpect(status().isCreated());
        long firstId = jsonLong(first, "$.id");

        reserve(token, event.getId(), 2, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstId));
        assertEquals(2, heldSeats(event), "a replay must not allocate seats again");
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Exception {
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 10);
        String token = tokenFor(createUser("cust", Role.CUSTOMER));
        String key = key();

        reserve(token, event.getId(), 2, key).andExpect(status().isCreated());
        reserve(token, event.getId(), 3, key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(2, heldSeats(event));
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 10);
        String key = key();

        long a = jsonLong(reserve(tokenFor(createUser("a", Role.CUSTOMER)), event.getId(), 1, key)
                .andExpect(status().isCreated()), "$.id");
        long b = jsonLong(reserve(tokenFor(createUser("b", Role.CUSTOMER)), event.getId(), 1, key)
                .andExpect(status().isCreated()), "$.id");

        assertTrue(a != b, "same key from different users must create independent reservations");
        assertEquals(2, heldSeats(event));
    }

    @Test
    void failedAttemptReleasesTheKeySoTheClientCanRetry() throws Exception {
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 2);
        String token = tokenFor(createUser("cust", Role.CUSTOMER));
        String key = key();

        reserve(token, event.getId(), 3, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
        // same key, now with a payload that fits: must be processed, not rejected as a mismatch
        reserve(token, event.getId(), 2, key).andExpect(status().isCreated());
        assertEquals(2, heldSeats(event));
    }

    @Test
    void soldOutAndPartialAvailabilityMessages() throws Exception {
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 3);
        String token = tokenFor(createUser("cust", Role.CUSTOMER));

        reserve(token, event.getId(), 2, key()).andExpect(status().isCreated());
        reserve(token, event.getId(), 2, key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only 1 seat(s) left"));
        reserve(token, event.getId(), 1, key()).andExpect(status().isCreated());
        reserve(token, event.getId(), 1, key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Event is sold out"));
    }

    @Test
    void draftEventCannotBeReserved() throws Exception {
        User owner = createUser("owner", Role.ORGANIZER);
        Event draft = eventRepository.save(new Event(owner.getId(), "Draft", "Hall",
                java.time.Instant.now().plusSeconds(86_400), java.time.Instant.now().plusSeconds(90_000), 10));

        reserve(tokenFor(createUser("cust", Role.CUSTOMER)), draft.getId(), 1, key())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void unknownEventAndInvalidSeatCounts() throws Exception {
        String token = tokenFor(createUser("cust", Role.CUSTOMER));
        reserve(token, 999_999_999L, 1, key()).andExpect(status().isNotFound());

        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 100);
        reserve(token, event.getId(), 0, key()).andExpect(status().isBadRequest());
        reserve(token, event.getId(), 11, key()).andExpect(status().isBadRequest());
    }

    @Test
    void confirmCancelLifecycleAndSeatRelease() throws Exception {
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 2);
        User customer = createUser("cust", Role.CUSTOMER);
        String token = tokenFor(customer);

        long id = jsonLong(reserve(token, event.getId(), 2, key()).andExpect(status().isCreated()), "$.id");
        reserve(token, event.getId(), 1, key()).andExpect(status().isConflict());

        postEmpty("/api/reservations/" + id + "/confirm", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        postEmpty("/api/reservations/" + id + "/confirm", token) // idempotent
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        postEmpty("/api/reservations/" + id + "/cancel", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        postEmpty("/api/reservations/" + id + "/cancel", token) // idempotent
                .andExpect(status().isOk());
        postEmpty("/api/reservations/" + id + "/confirm", token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        // seats were released: the event can be filled again
        reserve(token, event.getId(), 2, key()).andExpect(status().isCreated());
        assertEquals(2, heldSeats(event));
    }

    @Test
    void reservationOwnershipIsEnforced() throws Exception {
        User owner = createUser("owner", Role.ORGANIZER);
        User otherOrganizer = createUser("otherorg", Role.ORGANIZER);
        User customer = createUser("cust", Role.CUSTOMER);
        User stranger = createUser("stranger", Role.CUSTOMER);
        User admin = createUser("admin", Role.ADMIN);
        Event event = createPublishedEvent(owner, 20);

        long id = jsonLong(reserve(tokenFor(customer), event.getId(), 1, key()).andExpect(status().isCreated()),
                "$.id");

        postEmpty("/api/reservations/" + id + "/confirm", tokenFor(stranger)).andExpect(status().isForbidden());
        postEmpty("/api/reservations/" + id + "/cancel", tokenFor(stranger)).andExpect(status().isForbidden());
        postEmpty("/api/reservations/" + id + "/cancel", tokenFor(otherOrganizer)).andExpect(status().isForbidden());
        postEmpty("/api/reservations/999999999/confirm", tokenFor(customer)).andExpect(status().isNotFound());

        // event owner may cancel, admin may confirm
        postEmpty("/api/reservations/" + id + "/confirm", tokenFor(admin)).andExpect(status().isOk());
        postEmpty("/api/reservations/" + id + "/cancel", tokenFor(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void reservationLifecycleIsAudited() throws Exception {
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 5);
        User customer = createUser("cust", Role.CUSTOMER);
        String token = tokenFor(customer);

        long id = jsonLong(reserve(token, event.getId(), 1, key()).andExpect(status().isCreated()), "$.id");
        postEmpty("/api/reservations/" + id + "/confirm", token).andExpect(status().isOk());
        postEmpty("/api/reservations/" + id + "/cancel", token).andExpect(status().isOk());

        for (AuditAction action : List.of(AuditAction.RESERVATION_CREATED, AuditAction.RESERVATION_CONFIRMED,
                AuditAction.RESERVATION_CANCELLED)) {
            assertEquals(1, auditLogRepository
                    .findByActionAndResourceTypeAndResourceId(action, "RESERVATION", String.valueOf(id)).size(),
                    action + " must be audited exactly once");
        }
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        final int capacity = 20;
        final int threads = 60;
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), capacity);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tokens.add(tokenFor(createUser("racer", Role.CUSTOMER)));
        }

        AtomicInteger created = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        runConcurrently(threads, i -> {
            int code = reserve(tokens.get(i), event.getId(), 1, key()).andReturn().getResponse().getStatus();
            if (code == 201) {
                created.incrementAndGet();
            } else if (code == 409) {
                soldOut.incrementAndGet();
            } else {
                other.incrementAndGet();
            }
            return null;
        });

        assertEquals(0, other.get(), "no unexpected status codes");
        assertEquals(capacity, created.get(), "exactly the capacity must be sold");
        assertEquals(threads - capacity, soldOut.get());
        assertEquals(capacity, heldSeats(event), "persisted seats must equal capacity, never more");
    }

    @Test
    void concurrentMultiSeatRequestsNeverExceedCapacity() throws Exception {
        final int capacity = 10;
        final int threads = 30;
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), capacity);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tokens.add(tokenFor(createUser("racer", Role.CUSTOMER)));
        }

        runConcurrently(threads, i -> {
            reserve(tokens.get(i), event.getId(), 3, key());
            return null;
        });

        long held = heldSeats(event);
        assertTrue(held <= capacity, "held " + held + " exceeds capacity " + capacity);
        assertEquals(9, held, "three 3-seat reservations fit in 10 seats; the fourth must not");
    }

    @Test
    void concurrentDuplicateRequestsWithOneKeyCreateExactlyOneReservation() throws Exception {
        final int threads = 20;
        Event event = createPublishedEvent(createUser("owner", Role.ORGANIZER), 50);
        User customer = createUser("cust", Role.CUSTOMER);
        String token = tokenFor(customer);
        String key = key();

        List<Integer> codes = java.util.Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, i -> {
            codes.add(reserve(token, event.getId(), 2, key).andReturn().getResponse().getStatus());
            return null;
        });

        assertEquals(1, reservationRepository.findByEventId(event.getId()).size(),
                "duplicates must collapse into a single reservation");
        assertEquals(2, heldSeats(event));
        assertTrue(codes.contains(201));
        assertTrue(codes.stream().allMatch(c -> c == 201 || c == 409),
                "duplicates get the replay (201) or a retryable in-progress (409), got " + codes);
        assertTrue(idempotencyKeys.findByUserIdAndKeyValue(customer.getId(), key).isPresent(),
                "exactly one idempotency record must exist for the key");
    }

    @FunctionalInterface
    private interface IndexedTask {
        Object run(int index) throws Exception;
    }

    private void runConcurrently(int threads, IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            Callable<Object> job = () -> {
                ready.countDown();
                start.await();
                return task.run(index);
            };
            futures.add(pool.submit(job));
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Object> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }
}
