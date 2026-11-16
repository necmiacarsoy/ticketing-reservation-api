package com.example.ticketing.entity;

import com.example.ticketing.enums.ReservationStatus;
import com.example.ticketing.exception.ApiException;
import com.example.ticketing.exception.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationTest {

    private static Reservation pending() {
        return new Reservation(1L, 2L, 3);
    }

    @Test
    void newReservationIsPending() {
        assertEquals(ReservationStatus.PENDING, pending().getStatus());
    }

    @Test
    void rejectsNonPositiveSeats() {
        assertThrows(ApiException.class, () -> new Reservation(1L, 2L, 0));
    }

    @Test
    void confirmMovesPendingToConfirmedAndIsIdempotent() {
        Reservation r = pending();
        assertTrue(r.confirm());
        assertEquals(ReservationStatus.CONFIRMED, r.getStatus());
        assertFalse(r.confirm(), "second confirm must be a no-op");
        assertEquals(ReservationStatus.CONFIRMED, r.getStatus());
    }

    @Test
    void cancelWorksFromPendingAndConfirmedAndIsIdempotent() {
        Reservation fromPending = pending();
        assertTrue(fromPending.cancel());
        assertFalse(fromPending.cancel());
        assertEquals(ReservationStatus.CANCELLED, fromPending.getStatus());

        Reservation fromConfirmed = pending();
        fromConfirmed.confirm();
        assertTrue(fromConfirmed.cancel());
        assertEquals(ReservationStatus.CANCELLED, fromConfirmed.getStatus());
    }

    @Test
    void cancelledReservationCannotBeConfirmed() {
        Reservation r = pending();
        r.cancel();
        ApiException ex = assertThrows(ApiException.class, r::confirm);
        assertEquals(ErrorCode.INVALID_STATE, ex.getCode());
    }
}
