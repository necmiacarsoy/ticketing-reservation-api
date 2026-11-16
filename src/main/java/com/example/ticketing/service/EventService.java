package com.example.ticketing.service;

import java.time.Instant;
import java.util.List;

import com.example.ticketing.dto.EventDtos.CreateEventRequest;
import com.example.ticketing.dto.EventDtos.EventResponse;
import com.example.ticketing.dto.EventDtos.UpdateEventRequest;
import com.example.ticketing.dto.PageResponse;
import com.example.ticketing.entity.Event;
import com.example.ticketing.enums.AuditAction;
import com.example.ticketing.enums.ReservationStatus;
import com.example.ticketing.exception.ApiException;
import com.example.ticketing.repository.EventRepository;
import com.example.ticketing.repository.EventSpecifications;
import com.example.ticketing.repository.ReservationRepository;
import com.example.ticketing.security.AuthenticatedUser;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private static final String RESOURCE = "EVENT";
    private static final int MAX_PAGE_SIZE = 100;
    private static final List<ReservationStatus> SEAT_HOLDING =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private final EventRepository events;
    private final ReservationRepository reservations;
    private final AuditService audit;

    public EventService(EventRepository events, ReservationRepository reservations, AuditService audit) {
        this.events = events;
        this.reservations = reservations;
        this.audit = audit;
    }

    @Transactional
    public EventResponse create(AuthenticatedUser actor, CreateEventRequest request) {
        Event event = events.save(new Event(actor.id(), request.title().trim(), request.venue().trim(),
                request.startsAt(), request.endsAt(), request.capacity()));
        audit.record(actor.id(), AuditAction.EVENT_CREATED, RESOURCE, event.getId());
        return EventResponse.from(event);
    }

    @Transactional
    public EventResponse update(AuthenticatedUser actor, Long id, UpdateEventRequest request) {
        Event event = events.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Event", id));
        requireOwnerOrAdmin(actor, event);
        if (request.version() != null && !request.version().equals(event.getVersion())) {
            throw ApiException.conflict("Event was modified by someone else (current version "
                    + event.getVersion() + ")");
        }
        long held = reservations.sumSeats(id, SEAT_HOLDING);
        if (request.capacity() < held) {
            throw ApiException.conflict("Capacity cannot be lower than the " + held + " seats already reserved");
        }
        event.update(request.title().trim(), request.venue().trim(), request.startsAt(), request.endsAt(),
                request.capacity());
        Event saved = events.saveAndFlush(event);
        audit.record(actor.id(), AuditAction.EVENT_UPDATED, RESOURCE, id);
        return EventResponse.from(saved);
    }

    @Transactional
    public EventResponse publish(AuthenticatedUser actor, Long id) {
        Event event = events.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Event", id));
        requireOwnerOrAdmin(actor, event);
        event.publish();
        Event saved = events.saveAndFlush(event);
        audit.record(actor.id(), AuditAction.EVENT_PUBLISHED, RESOURCE, id);
        return EventResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<EventResponse> listManaged(AuthenticatedUser actor, Long ownerId, int page, int size) {
        Specification<Event> spec = EventSpecifications.all();
        if (actor.isAdmin()) {
            if (ownerId != null) {
                spec = spec.and(EventSpecifications.ownedBy(ownerId));
            }
        } else {
            if (ownerId != null && !actor.owns(ownerId)) {
                throw ApiException.forbidden("Organizers can only list their own events");
            }
            spec = spec.and(EventSpecifications.ownedBy(actor.id()));
        }
        return search(spec, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<EventResponse> searchPublic(Instant from, Instant to, String q, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("'from' must not be after 'to'");
        }
        Specification<Event> spec = EventSpecifications.published();
        if (from != null) {
            spec = spec.and(EventSpecifications.startsFrom(from));
        }
        if (to != null) {
            spec = spec.and(EventSpecifications.startsUntil(to));
        }
        if (q != null && !q.isBlank()) {
            spec = spec.and(EventSpecifications.matches(q));
        }
        return search(spec, page, size);
    }

    private PageResponse<EventResponse> search(Specification<Event> spec, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by("startsAt").ascending().and(Sort.by("id")));
        Page<Event> result = events.findAll(spec, pageable);
        return PageResponse.from(result, EventResponse::from);
    }

    private static void requireOwnerOrAdmin(AuthenticatedUser actor, Event event) {
        if (!actor.isAdmin() && !actor.owns(event.getOwnerId())) {
            throw ApiException.forbidden("Only the event owner or an admin can do this");
        }
    }
}
