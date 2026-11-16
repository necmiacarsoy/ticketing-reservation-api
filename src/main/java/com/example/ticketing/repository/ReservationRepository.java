package com.example.ticketing.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.example.ticketing.entity.Reservation;
import com.example.ticketing.enums.ReservationStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    @Query("select coalesce(sum(r.seats), 0L) from Reservation r "
            + "where r.eventId = :eventId and r.status in :statuses")
    long sumSeats(@Param("eventId") Long eventId, @Param("statuses") Collection<ReservationStatus> statuses);

    List<Reservation> findByEventId(Long eventId);
}
