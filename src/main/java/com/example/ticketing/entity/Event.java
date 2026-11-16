package com.example.ticketing.entity;

import java.time.Instant;

import com.example.ticketing.exception.ApiException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "events", indexes = {
        @Index(name = "idx_events_owner", columnList = "owner_id"),
        @Index(name = "idx_events_public", columnList = "published,starts_at")
})
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private boolean published;

    @Version
    private Long version;

    protected Event() {
        // for JPA
    }

    public Event(Long ownerId, String title, String venue, Instant startsAt, Instant endsAt, int capacity) {
        this.ownerId = ownerId;
        apply(title, venue, startsAt, endsAt, capacity);
    }

    public void update(String title, String venue, Instant startsAt, Instant endsAt, int capacity) {
        apply(title, venue, startsAt, endsAt, capacity);
    }

    public void publish() {
        if (published) {
            throw ApiException.conflict("Event is already published");
        }
        published = true;
    }

    private void apply(String newTitle, String newVenue, Instant newStartsAt, Instant newEndsAt, int newCapacity) {
        if (!newEndsAt.isAfter(newStartsAt)) {
            throw ApiException.badRequest("endsAt must be after startsAt");
        }
        if (newCapacity < 1) {
            throw ApiException.badRequest("capacity must be at least 1");
        }
        this.title = newTitle;
        this.venue = newVenue;
        this.startsAt = newStartsAt;
        this.endsAt = newEndsAt;
        this.capacity = newCapacity;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public int getCapacity() {
        return capacity;
    }

    public boolean isPublished() {
        return published;
    }

    public Long getVersion() {
        return version;
    }
}
