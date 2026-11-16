# Secure Ticketing & Reservation API

Spring Boot **4.0** / Java **25** REST API for an event platform: organizers publish events,
customers reserve seats. No overselling, idempotent reservation creation, JWT authentication
with role-based authorization, rate limiting and an audit trail.

## Quick start

Requirements: JDK 25, Maven 3.9+ (Docker optional).

```bash
mvn spring-boot:run            # in-memory H2, seed data enabled
mvn verify                     # tests + JaCoCo report (target/site/jacoco/index.html)
docker compose up --build      # app + PostgreSQL 17 (profile "postgres")
```

* Swagger UI: <http://localhost:8080/swagger-ui.html>
* OpenAPI JSON: <http://localhost:8080/v3/api-docs>
* Health: <http://localhost:8080/actuator/health> (metrics need an ADMIN token)

Configuration lives in `src/main/resources/application.yml`; set `JWT_SECRET` (>= 32 bytes) in
any real deployment and `app.seed.enabled=false`.

## Seed users

| Email | Password | Role |
|---|---|---|
| admin@example.com | Admin123! | ADMIN |
| organizer@example.com | Organizer123! | ORGANIZER |
| customer@example.com | Customer123! | CUSTOMER |

A published demo event ("Spring Jazz Night", capacity 100) is created as well.

## Roles

| Role | Capabilities |
|---|---|
| ADMIN | everything |
| ORGANIZER | create events, manage (update/publish/list) **owned** events, cancel reservations on own events |
| CUSTOMER | reserve, confirm and cancel own reservations |

Self-registration may choose CUSTOMER or ORGANIZER; ADMIN cannot be self-registered.

## Auth flow

1. `POST /api/auth/register` `{ "email", "password", "role"? }`
2. `POST /api/auth/login` → `{ accessToken, refreshToken, tokenType: "Bearer", expiresIn }`
3. Send `Authorization: Bearer <accessToken>` on every call (access token: 15 min).
4. `POST /api/auth/refresh` `{ "refreshToken" }` → new token pair (refresh token: 7 days).

Tokens are HS256 JWTs. A refresh token is rejected as an access token (`token_type` claim) and
vice versa. Sample access-token payload:

```json
{
  "iss": "ticketing-api",
  "sub": "3",
  "email": "customer@example.com",
  "roles": ["CUSTOMER"],
  "token_type": "access",
  "iat": 1790000000,
  "exp": 1790000900
}
```

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"customer@example.com","password":"Customer123!"}' | jq -r .accessToken)

curl -X POST localhost:8080/api/events/1/reservations \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: 7d1c2c3e-0001' \
  -H 'Content-Type: application/json' -d '{"seats":2}'
```

## Endpoints

| Method & path | Access |
|---|---|
| POST `/api/auth/register`, `/login`, `/refresh` | public (rate limited) |
| POST `/api/events` | ORGANIZER, ADMIN |
| PUT `/api/events/{id}` | owner, ADMIN |
| POST `/api/events/{id}/publish` | owner, ADMIN |
| GET `/api/events?ownerId=` | ORGANIZER (own only), ADMIN |
| GET `/api/events/public?from=&to=&q=` | public |
| POST `/api/events/{id}/reservations` (header `Idempotency-Key`) | CUSTOMER, ADMIN |
| POST `/api/reservations/{id}/confirm` | reservation owner, ADMIN |
| POST `/api/reservations/{id}/cancel` | reservation owner, event owner, ADMIN |

Errors share one JSON shape (`ApiError`: `code`, `message`, `path`, `timestamp`, field errors)
with stable codes such as `VALIDATION_ERROR`, `SOLD_OUT`, `INVALID_STATE`,
`IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_IN_PROGRESS`, `RATE_LIMITED`.

## Architecture & decisions

Classic layered architecture: **controller → service → repository → entity**. Each layer only
talks to the one below it; controllers hold no business logic, repositories hold no rules, and
the API never exposes entities (DTOs are Java records).

```
com.example.ticketing
├── controller/   REST endpoints, request validation, OpenAPI annotations
│                 AuthController, EventController, ReservationController
├── service/      business rules, transactions, authorization of ownership
│                 AuthService, EventService, ReservationService, IdempotencyService, AuditService
├── repository/   Spring Data JPA repositories + EventSpecifications (dynamic filters)
├── entity/       JPA entities: User, Event, Reservation, IdempotencyKey, AuditLog
├── enums/        Role, ReservationStatus, IdempotencyStatus, AuditAction
├── dto/          request/response records, ApiError, PageResponse
├── exception/    ApiException, ErrorCode, GlobalExceptionHandler (single error model)
├── security/     JwtService, RateLimitFilter/RateLimiter, AuthenticatedUser, JSON 401/403 writer
├── config/       SecurityConfig, OpenApiConfig, AppProperties, DataSeeder
├── scheduler/    IdempotencyCleanupJob
└── util/         Hashing
```

Tests mirror this layout (`controller` integration tests, `entity`/`service` unit tests,
`security` tests, shared `support` base class).

**ADR-1 No overselling: pessimistic row lock.** `ReservationService` locks the event row
(`SELECT … FOR UPDATE`), sums seats held by PENDING + CONFIRMED reservations and inserts only if
the request fits, all in one transaction. Allocation per event is serialized across threads and
instances; other events are unaffected. Chosen over optimistic locking (`Event.version` is kept
for update conflicts) because under contention retries would stampede on a hot event.

**ADR-2 Idempotency: claim → complete → release.** The key is claimed in its own transaction
(unique constraint on user + key, so concurrent duplicates collide). It is marked COMPLETED in
the *same* transaction as the reservation insert, so it flips if and only if the business change
commits. On failure the key is released so the client can retry. Same key + same request → the
original reservation with header `Idempotency-Replayed: true`; same key + different request →
422; still processing → 409. Keys are scoped per user, stored with request/response hashes and
expire (24 h, 60 s while in progress); a scheduled job purges expired rows.

**ADR-3 Stateless JWT via Spring Security resource server** (Nimbus). Roles are embedded in the
token; method/endpoint rules sit in `SecurityConfig`, ownership rules in services. 401/403 are
returned as JSON by custom handlers.

**ADR-4 Rate limiting** is an in-memory fixed-window limiter per client address, stricter for
`/api/auth/**`. `X-Forwarded-For` is deliberately ignored (spoofable); behind a proxy, use a
shared store (e.g. Redis) or enforce limits at the gateway for multi-instance deployments.

**ADR-5 Audit log** records actor, action, resource, IP and user agent for register/login/refresh,
event and reservation changes.

**ADR-6 Cancel releases seats immediately**; confirm/cancel are idempotent state transitions
guarded by a row lock on the reservation.

## Testing

* Unit: domain rules (`Event`, `Reservation`), `JwtService`, `RateLimiter`, `RateLimitFilter`,
  `IdempotencyService`.
* Integration (H2, MockMvc, full context): auth, events, reservations, RBAC/security, rate limit.
* Concurrency: 60 threads racing for 20 seats (exactly 20 succeed), multi-seat races, and 20
  parallel requests with one idempotency key (exactly one reservation).
* Coverage: JaCoCo, `mvn verify`.

## Observability

Actuator `health` (liveness/readiness probes), `info`, `metrics` (ADMIN). Custom counters:
`ticketing.reservations.created`, `ticketing.reservations.sold_out`.
