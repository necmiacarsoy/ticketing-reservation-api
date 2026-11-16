package com.example.ticketing.controller;

import java.net.URI;

import com.example.ticketing.dto.ReservationDtos.CreateReservationRequest;
import com.example.ticketing.dto.ReservationDtos.ReservationResponse;
import com.example.ticketing.dto.ReservationDtos.ReservationResult;
import com.example.ticketing.security.AuthenticatedUser;
import com.example.ticketing.service.ReservationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Reservations")
public class ReservationController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/api/events/{id}/reservations")
    @Operation(summary = "Create a PENDING reservation (CUSTOMER/ADMIN). Requires the Idempotency-Key header; "
            + "repeating the same key + payload returns the original reservation.")
    public ResponseEntity<ReservationResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") Long eventId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request) {
        ReservationResult result = reservationService.create(
                AuthenticatedUser.from(jwt), eventId, request.seats(), idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity
                .created(URI.create("/api/reservations/" + result.reservation().id()));
        if (result.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(result.reservation());
    }

    @PostMapping("/api/reservations/{id}/confirm")
    @Operation(summary = "Confirm a PENDING reservation (owner or ADMIN)")
    public ReservationResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") Long id) {
        return reservationService.confirm(AuthenticatedUser.from(jwt), id);
    }

    @PostMapping("/api/reservations/{id}/cancel")
    @Operation(summary = "Cancel a reservation and release its seats (owner, event owner or ADMIN)")
    public ReservationResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") Long id) {
        return reservationService.cancel(AuthenticatedUser.from(jwt), id);
    }
}
