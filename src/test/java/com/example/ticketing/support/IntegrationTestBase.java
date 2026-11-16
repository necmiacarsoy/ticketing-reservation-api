package com.example.ticketing.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.ticketing.entity.Event;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.Role;
import com.example.ticketing.repository.AuditLogRepository;
import com.example.ticketing.repository.EventRepository;
import com.example.ticketing.repository.ReservationRepository;
import com.example.ticketing.repository.UserRepository;
import com.example.ticketing.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final String PASSWORD = "Passw0rd!x";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected EventRepository eventRepository;
    @Autowired
    protected ReservationRepository reservationRepository;
    @Autowired
    protected AuditLogRepository auditLogRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JwtService jwtService;

    protected MockMvc mvc;

    @BeforeEach
    void initMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    protected User createUser(String prefix, Role role) {
        String email = prefix + "-" + UUID.randomUUID() + "@test.example";
        return userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), java.util.Set.of(role)));
    }

    protected String tokenFor(User user) {
        return jwtService.issueAccessToken(user);
    }

    protected Event createPublishedEvent(User owner, int capacity) {
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS);
        Event event = new Event(owner.getId(), "Event " + UUID.randomUUID(), "Test Hall",
                start, start.plus(3, ChronoUnit.HOURS), capacity);
        event.publish();
        return eventRepository.save(event);
    }

    protected static String eventJson(String title, int capacity) {
        return """
                {"title":"%s","venue":"Main Hall","startsAt":"2031-05-01T18:00:00Z",\
                "endsAt":"2031-05-01T21:00:00Z","capacity":%d}""".formatted(title, capacity);
    }

    protected ResultActions postJson(String url, String token, String json) throws Exception {
        return mvc.perform(withAuth(MockMvcRequestBuilders.post(url), token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions putJson(String url, String token, String json) throws Exception {
        return mvc.perform(withAuth(MockMvcRequestBuilders.put(url), token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions get(String url, String token) throws Exception {
        return mvc.perform(withAuth(MockMvcRequestBuilders.get(url), token));
    }

    protected ResultActions postEmpty(String url, String token) throws Exception {
        return mvc.perform(withAuth(MockMvcRequestBuilders.post(url), token));
    }

    protected ResultActions reserve(String token, long eventId, int seats, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder builder = withAuth(
                MockMvcRequestBuilders.post("/api/events/" + eventId + "/reservations"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":" + seats + "}");
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(builder);
    }

    protected static long jsonLong(ResultActions actions, String path) throws Exception {
        Object value = JsonPath.read(actions.andReturn().getResponse().getContentAsString(), path);
        return ((Number) value).longValue();
    }

    protected static String jsonString(ResultActions actions, String path) throws Exception {
        return JsonPath.read(actions.andReturn().getResponse().getContentAsString(), path);
    }

    private static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, String token) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
