package com.example.ticketing.dto;

import java.time.Instant;

import com.example.ticketing.entity.Reservation;
import com.example.ticketing.enums.ReservationStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class ReservationDtos {

    public static final int MAX_SEATS_PER_RESERVATION = 10;

    private ReservationDtos() {
    }

    public record CreateReservationRequest(
            @NotNull @Min(1) @Max(MAX_SEATS_PER_RESERVATION) Integer seats) {
    }

    public record ReservationResponse(
            Long id,
            Long eventId,
            Long userId,
            ReservationStatus status,
            int seats,
            Instant createdAt) {

        public static ReservationResponse from(Reservation r) {
            return new ReservationResponse(r.getId(), r.getEventId(), r.getUserId(), r.getStatus(), r.getSeats(),
                    r.getCreatedAt());
        }
    }

    public record ReservationResult(ReservationResponse reservation, boolean replayed) {
    }
}
