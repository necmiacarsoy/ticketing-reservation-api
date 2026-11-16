package com.example.ticketing.entity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.ticketing.exception.ApiException;
import com.example.ticketing.exception.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTest {

    private static final Instant START = Instant.parse("2031-01-01T10:00:00Z");
    private static final Instant END = START.plus(2, ChronoUnit.HOURS);

    @Test
    void newEventIsDraft() {
        Event event = new Event(1L, "Concert", "Hall", START, END, 10);
        assertFalse(event.isPublished());
    }

    @Test
    void publishIsOneWayAndSecondPublishConflicts() {
        Event event = new Event(1L, "Concert", "Hall", START, END, 10);
        event.publish();
        assertTrue(event.isPublished());
        ApiException ex = assertThrows(ApiException.class, event::publish);
        assertEquals(ErrorCode.CONFLICT, ex.getCode());
    }

    @Test
    void rejectsEndBeforeStart() {
        assertThrows(ApiException.class, () -> new Event(1L, "Concert", "Hall", END, START, 10));
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(ApiException.class, () -> new Event(1L, "Concert", "Hall", START, END, 0));
    }

    @Test
    void updateAppliesNewValuesAndValidates() {
        Event event = new Event(1L, "Concert", "Hall", START, END, 10);
        event.update("Gala", "Arena", START, END.plus(1, ChronoUnit.HOURS), 25);
        assertEquals("Gala", event.getTitle());
        assertEquals(25, event.getCapacity());
        assertThrows(ApiException.class, () -> event.update("Gala", "Arena", END, START, 25));
    }
}
