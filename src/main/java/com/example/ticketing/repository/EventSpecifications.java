package com.example.ticketing.repository;

import java.time.Instant;
import java.util.Locale;

import com.example.ticketing.entity.Event;

import org.springframework.data.jpa.domain.Specification;

public final class EventSpecifications {

    private static final char ESCAPE = '\\';

    private EventSpecifications() {
    }

    public static Specification<Event> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    public static Specification<Event> published() {
        return (root, query, cb) -> cb.isTrue(root.<Boolean>get("published"));
    }

    public static Specification<Event> ownedBy(Long ownerId) {
        return (root, query, cb) -> cb.equal(root.get("ownerId"), ownerId);
    }

    public static Specification<Event> startsFrom(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("startsAt"), from);
    }

    public static Specification<Event> startsUntil(Instant to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.<Instant>get("startsAt"), to);
    }

    public static Specification<Event> matches(String text) {
        String pattern = "%" + escape(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.<String>get("title")), pattern, ESCAPE),
                cb.like(cb.lower(root.<String>get("venue")), pattern, ESCAPE));
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
