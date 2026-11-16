package com.example.ticketing.controller;

import java.util.UUID;

import com.example.ticketing.entity.Event;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.Role;
import com.example.ticketing.support.IntegrationTestBase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventIntegrationTest extends IntegrationTestBase {

    private long createDraft(String token, String title, int capacity) throws Exception {
        var result = postJson("/api/events", token, eventJson(title, capacity))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.published").value(false));
        return jsonLong(result, "$.id");
    }

    @Test
    void organizerCreatesDraftOwnedByTheCaller() throws Exception {
        User organizer = createUser("org", Role.ORGANIZER);
        String token = tokenFor(organizer);

        postJson("/api/events", token, eventJson("Concert", 50))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(organizer.getId()))
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.capacity").value(50))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createValidatesPayload() throws Exception {
        String token = tokenFor(createUser("org", Role.ORGANIZER));

        postJson("/api/events", token, "{\"title\":\"\",\"venue\":\"x\",\"capacity\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        postJson("/api/events", token,
                "{\"title\":\"t\",\"venue\":\"v\",\"startsAt\":\"2031-05-01T21:00:00Z\","
                        + "\"endsAt\":\"2031-05-01T18:00:00Z\",\"capacity\":5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("endsAt must be after startsAt"));
    }

    @Test
    void onlyOwnerOrAdminCanUpdateAndPublish() throws Exception {
        User owner = createUser("owner", Role.ORGANIZER);
        User other = createUser("other", Role.ORGANIZER);
        User admin = createUser("admin", Role.ADMIN);
        long id = createDraft(tokenFor(owner), "Original", 10);

        putJson("/api/events/" + id, tokenFor(other), eventJson("Hacked", 10)).andExpect(status().isForbidden());
        postEmpty("/api/events/" + id + "/publish", tokenFor(other)).andExpect(status().isForbidden());

        putJson("/api/events/" + id, tokenFor(owner), eventJson("Renamed", 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.capacity").value(20))
                .andExpect(jsonPath("$.version").value(1));
        putJson("/api/events/" + id, tokenFor(admin), eventJson("By admin", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("By admin"));
    }

    @Test
    void publishIsOneWay() throws Exception {
        String token = tokenFor(createUser("org", Role.ORGANIZER));
        long id = createDraft(token, "Publishable", 10);

        postEmpty("/api/events/" + id + "/publish", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));
        postEmpty("/api/events/" + id + "/publish", token)
                .andExpect(status().isConflict());
    }

    @Test
    void unknownEventIsNotFound() throws Exception {
        String token = tokenFor(createUser("org", Role.ORGANIZER));
        putJson("/api/events/999999999", token, eventJson("x", 5))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void staleVersionIsRejectedWithConflict() throws Exception {
        String token = tokenFor(createUser("org", Role.ORGANIZER));
        long id = createDraft(token, "Versioned", 10);
        String stale = "{\"title\":\"v\",\"venue\":\"v\",\"startsAt\":\"2031-05-01T18:00:00Z\","
                + "\"endsAt\":\"2031-05-01T21:00:00Z\",\"capacity\":10,\"version\":7}";

        putJson("/api/events/" + id, token, stale).andExpect(status().isConflict());
    }

    @Test
    void capacityCannotDropBelowSeatsAlreadyHeld() throws Exception {
        User owner = createUser("owner", Role.ORGANIZER);
        User customer = createUser("cust", Role.CUSTOMER);
        Event event = createPublishedEvent(owner, 10);
        reserve(tokenFor(customer), event.getId(), 6, UUID.randomUUID().toString()).andExpect(status().isCreated());

        putJson("/api/events/" + event.getId(), tokenFor(owner), eventJson("Shrunk", 5))
                .andExpect(status().isConflict());
        putJson("/api/events/" + event.getId(), tokenFor(owner), eventJson("Shrunk", 6))
                .andExpect(status().isOk());
    }

    @Test
    void organizerListsOnlyOwnEventsAdminListsAnyOwner() throws Exception {
        User orgA = createUser("a", Role.ORGANIZER);
        User orgB = createUser("b", Role.ORGANIZER);
        User admin = createUser("admin", Role.ADMIN);
        createDraft(tokenFor(orgA), "A-one", 5);
        createDraft(tokenFor(orgA), "A-two", 5);
        createDraft(tokenFor(orgB), "B-one", 5);

        get("/api/events", tokenFor(orgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].ownerId").value(orgA.getId()));
        get("/api/events?ownerId=" + orgB.getId(), tokenFor(orgA)).andExpect(status().isForbidden());
        get("/api/events?ownerId=" + orgB.getId(), tokenFor(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("B-one"));
    }

    @Test
    void publicDiscoveryShowsOnlyPublishedAndSupportsFilters() throws Exception {
        String token = tokenFor(createUser("org", Role.ORGANIZER));
        String tag = "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long published = createDraft(token, "Open " + tag, 10);
        createDraft(token, "Draft " + tag, 10);
        postEmpty("/api/events/" + published + "/publish", token).andExpect(status().isOk());

        // no authentication needed
        get("/api/events/public?q=" + tag, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(published));
        // case-insensitive text match
        get("/api/events/public?q=" + tag.toUpperCase(), null)
                .andExpect(jsonPath("$.totalElements").value(1));
        // time window: event starts 2031-05-01T18:00Z
        get("/api/events/public?q=" + tag + "&from=2031-05-01T00:00:00Z&to=2031-05-02T00:00:00Z", null)
                .andExpect(jsonPath("$.totalElements").value(1));
        get("/api/events/public?q=" + tag + "&from=2031-06-01T00:00:00Z", null)
                .andExpect(jsonPath("$.totalElements").value(0));
        get("/api/events/public?from=2031-06-01T00:00:00Z&to=2031-05-01T00:00:00Z", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void likeWildcardsInSearchTextAreTreatedLiterally() throws Exception {
        String token = tokenFor(createUser("org", Role.ORGANIZER));
        long id = createDraft(token, "Plain title", 10);
        postEmpty("/api/events/" + id + "/publish", token).andExpect(status().isOk());

        var result = get("/api/events/public?q=%25", null).andExpect(status().isOk());
        assertEquals(0, jsonLong(result, "$.totalElements"));
    }
}
