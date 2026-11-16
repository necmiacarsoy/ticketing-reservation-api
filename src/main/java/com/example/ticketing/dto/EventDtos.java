package com.example.ticketing.dto;

import java.time.Instant;

import com.example.ticketing.entity.Event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class EventDtos {

    private EventDtos() {
    }

    public record CreateEventRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200) String venue,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull @Min(1) @Max(1_000_000) Integer capacity) {
    }

    public record UpdateEventRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200) String venue,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull @Min(1) @Max(1_000_000) Integer capacity,
            Long version) {
    }

    public record EventResponse(
            Long id,
            Long ownerId,
            String title,
            String venue,
            Instant startsAt,
            Instant endsAt,
            int capacity,
            boolean published,
            Long version) {

        public static EventResponse from(Event e) {
            return new EventResponse(e.getId(), e.getOwnerId(), e.getTitle(), e.getVenue(), e.getStartsAt(),
                    e.getEndsAt(), e.getCapacity(), e.isPublished(), e.getVersion());
        }
    }
}
