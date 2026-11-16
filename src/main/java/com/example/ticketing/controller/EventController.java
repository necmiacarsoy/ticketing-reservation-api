package com.example.ticketing.controller;

import java.net.URI;
import java.time.Instant;

import com.example.ticketing.dto.EventDtos.CreateEventRequest;
import com.example.ticketing.dto.EventDtos.EventResponse;
import com.example.ticketing.dto.EventDtos.UpdateEventRequest;
import com.example.ticketing.dto.PageResponse;
import com.example.ticketing.security.AuthenticatedUser;
import com.example.ticketing.service.EventService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@Tag(name = "Events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @Operation(summary = "Create a draft event owned by the caller (ORGANIZER/ADMIN)")
    public ResponseEntity<EventResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody CreateEventRequest request) {
        EventResponse created = eventService.create(AuthenticatedUser.from(jwt), request);
        return ResponseEntity.created(URI.create("/api/events/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an event (owner or ADMIN)")
    public EventResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") Long id,
                                @Valid @RequestBody UpdateEventRequest request) {
        return eventService.update(AuthenticatedUser.from(jwt), id, request);
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish a draft event (owner or ADMIN)")
    public EventResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") Long id) {
        return eventService.publish(AuthenticatedUser.from(jwt), id);
    }

    @GetMapping
    @Operation(summary = "List events for management; optional ownerId filter (ORGANIZER sees own only)")
    public PageResponse<EventResponse> list(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(name = "ownerId", required = false) Long ownerId,
                                            @RequestParam(name = "page", defaultValue = "0") int page,
                                            @RequestParam(name = "size", defaultValue = "20") int size) {
        return eventService.listManaged(AuthenticatedUser.from(jwt), ownerId, page, size);
    }

    @GetMapping("/public")
    @SecurityRequirements
    @Operation(summary = "Discover published events; filter by start time window and free text")
    public PageResponse<EventResponse> listPublic(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return eventService.searchPublic(from, to, q, page, size);
    }
}
