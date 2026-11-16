package com.example.ticketing.config;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import com.example.ticketing.entity.Event;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.Role;
import com.example.ticketing.repository.EventRepository;
import com.example.ticketing.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository users;
    private final EventRepository events;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository users, EventRepository events, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.events = events;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("admin@example.com", "Admin123!", Role.ADMIN);
        User organizer = seed("organizer@example.com", "Organizer123!", Role.ORGANIZER);
        seed("customer@example.com", "Customer123!", Role.CUSTOMER);

        if (events.count() == 0) {
            Instant start = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
            Event demo = new Event(organizer.getId(), "Spring Jazz Night", "Grand Hall",
                    start, start.plus(Duration.ofHours(3)), 100);
            demo.publish();
            events.save(demo);
        }
        LOG.info("Seed data ready (admin@example.com, organizer@example.com, customer@example.com)");
    }

    private User seed(String email, String rawPassword, Role role) {
        return users.findByEmail(email)
                .orElseGet(() -> users.save(new User(email, passwordEncoder.encode(rawPassword), Set.of(role))));
    }
}
