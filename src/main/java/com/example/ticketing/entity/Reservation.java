package com.example.ticketing.entity;

import java.time.Instant;

import com.example.ticketing.enums.ReservationStatus;
import com.example.ticketing.exception.ApiException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservations", indexes = {
        @Index(name = "idx_reservations_event_status", columnList = "event_id,status"),
        @Index(name = "idx_reservations_user", columnList = "user_id")
})
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private int seats;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reservation() {
        // for JPA
    }

    public Reservation(Long eventId, Long userId, int seats) {
        if (seats < 1) {
            throw ApiException.badRequest("seats must be at least 1");
        }
        this.eventId = eventId;
        this.userId = userId;
        this.seats = seats;
        this.status = ReservationStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public boolean confirm() {
        if (status == ReservationStatus.CONFIRMED) {
            return false;
        }
        if (status == ReservationStatus.CANCELLED) {
            throw ApiException.invalidState("A cancelled reservation cannot be confirmed");
        }
        status = ReservationStatus.CONFIRMED;
        return true;
    }

    public boolean cancel() {
        if (status == ReservationStatus.CANCELLED) {
            return false;
        }
        status = ReservationStatus.CANCELLED;
        return true;
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getUserId() {
        return userId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public int getSeats() {
        return seats;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
