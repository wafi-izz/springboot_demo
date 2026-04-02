# Advanced Spring Boot Learning Plan

> **Starting point:** A working user-management monolith with JWT auth, CRUD, and PostgreSQL.
> **End goal:** A distributed multi-service system that exercises the full depth of Spring Boot, Spring Cloud, and production-grade Java patterns.

> **How to use this plan:** Each phase builds on the previous one. Within each phase, topics are ordered by dependency. Don't skip ahead — the later phases assume you've internalized the earlier ones. Each topic includes **why it matters** so you know what you're actually learning, not just what you're typing.
>
> **Checkpoints:** Each phase ends with a checkpoint. When you're ready, ask Claude to "update checkpoints" — Claude will read this file, ask you which items you've completed, and mark them. A fully checked checkpoint means you're ready for the next phase.
>
> **Learning materials:** Each phase has a detailed file at `plan/phase-X.md`. Ask Claude to fill it in when you're starting that phase.

---

## Phase 1 — Harden the Monolith

> **Goal:** Turn the current codebase into something that would survive a senior-level code review. No new services yet — just depth.

### 1.1 Database Migrations with Flyway

**Why:** `ddl-auto=update` is a ticking bomb. It silently alters tables, can't roll back, and doesn't version your schema. Every production system uses migration tooling. Flyway is the Spring Boot default.

- [x] Add `flyway-core` dependency
- [x] Disable `ddl-auto` (set to `validate`)
- [x] Write `V1__create_users_table.sql` that matches your current schema exactly
- [x] Write `V2__add_indexes.sql` — add indexes on `email`, `username`
- [x] Learn the naming convention (`V{version}__{description}.sql`) and what happens when you violate it
- [x] Understand the `flyway_schema_history` table — how Flyway tracks what's been applied
- [x] Practice writing a migration that modifies an existing column (e.g., make `email` unique at the DB level)

**Spring depth:** Flyway's auto-configuration — how Spring Boot detects the dependency and runs migrations before JPA initializes. Read `FlywayAutoConfiguration` source.

---

### 1.2 DTO Mapping with MapStruct

**Why:** Manual `user.setFirstName(dto.getFirstName())` in controllers doesn't scale, is error-prone (you already had bugs from this), and clutters controller logic. MapStruct generates compile-time mapping code — zero reflection, zero runtime cost.

- [x] Add MapStruct dependency + annotation processor to `pom.xml`
- [x] Create `UserMapper` interface with `@Mapper(componentModel = "spring")`
- [x] Map `CreateUser → User`, `UpdateUser → User`, `User → UserResponse` (new DTO — stop returning entities)
- [x] Use `@MappingTarget` for update operations (map onto an existing entity)
- [x] Use `@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)` for PATCH support
- [x] Create a `UserResponse` DTO — **never return JPA entities directly from controllers** (even with `@JsonIgnore`, it's fragile and leaks your data model)

**Spring depth:** How annotation processors interact with Spring's component scanning. MapStruct's `componentModel = "spring"` makes it a `@Component` — understand why that matters for DI.

---

### 1.3 JPA Specifications for Dynamic Queries

**Why:** Right now your queries are rigid — `findById`, `findAll`. Real applications need dynamic filtering: "find users where role=ADMIN and name contains 'john' and created after last week." JPA Specifications let you compose query predicates programmatically.

- [x] Make `UserRepository` extend `JpaSpecificationExecutor<User>`
- [x] Create `UserSpecifications` class with static methods returning `Specification<User>`
- [x] Build specs: `hasRole(Role)`, `nameLike(String)`, `emailLike(String)`, `createdAfter(LocalDateTime)`
- [x] Compose them: `hasRole(ADMIN).and(nameLike("john"))`
- [x] Add a `GET /api/users/filter?role=ADMIN&name=john` endpoint that accepts optional query params and builds specs dynamically
- [x] Combine with `Pageable` for paginated filtered results

**Spring depth:** The Criteria API underneath Specifications. Understand how `Specification<T>` wraps `Predicate` creation and how `JpaSpecificationExecutor` applies them to `CriteriaQuery`.

---

### 1.4 Pagination & Sorting

**Why:** `findAll()` returning every row is a production incident waiting to happen. Pagination is expected in any real API.

- [x] Add `Pageable` parameter to service and controller methods
- [x] Return `Page<UserResponse>` instead of `List<User>`
- [x] Support `?page=0&size=20&sort=lastName,asc` via Spring's automatic `Pageable` resolution
- [x] Configure default page size in `application.properties` (`spring.data.web.pageable.default-page-size`)
- [x] Understand `Page` vs `Slice` — when to use each and the performance implications (count query vs no count query)

**Spring depth:** How `PageableHandlerMethodArgumentResolver` auto-resolves `Pageable` from request params. Read the source.

---

### 1.5 JPA Auditing

**Why:** Knowing who created/modified a record and when is non-negotiable in any real system. Spring Data JPA has built-in support via `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`.

- [x] Add `spring-boot-starter-data-jpa`'s auditing: `@EnableJpaAuditing`
- [x] Create a `BaseEntity` with `createdAt`, `updatedAt`, `createdBy`, `updatedBy`
- [x] Have `User` extend `BaseEntity`
- [x] Write a Flyway migration to add those columns
- [x] Implement `AuditorAware<String>` bean that pulls the current username from `SecurityContextHolder`
- [x] Verify auditing works on create and update operations

**Spring depth:** How `@EntityListeners(AuditingEntityListener.class)` hooks into JPA lifecycle events. Understand `@PrePersist` and `@PreUpdate` under the hood.

---

### 1.6 Custom AOP — Build Your Own Annotations

**Why:** AOP is one of Spring's most powerful and least understood features. Building custom annotations forces you to understand proxy-based interception, which is the same mechanism behind `@Transactional`, `@Cacheable`, `@Async`, and `@Secured`.

#### `@LogExecutionTime`
- [ ] Create the annotation
- [ ] Create an `@Aspect` with `@Around` advice that logs method name, args, duration, and result status
- [ ] Apply to service methods
- [ ] Understand why it won't work on private methods or self-invocations (proxy limitation)

#### `@Auditable`
- [ ] Create an annotation with params: `action` (CREATE, UPDATE, DELETE), `entity`
- [ ] Create an aspect that captures before/after state and writes to an `audit_log` table
- [ ] Create the `AuditLog` entity and repository
- [ ] Use `JoinPoint` to extract method arguments

#### `@RateLimit`
- [ ] Create an annotation with `maxRequests` and `windowSeconds` params
- [ ] Implement using a simple in-memory map (later you'll move this to Redis)
- [ ] Apply to login endpoint to prevent brute force
- [ ] Return `429 Too Many Requests` when exceeded

**Spring depth:** Read how Spring creates proxies (`JdkDynamicProxy` vs CGLIB). Understand `@EnableAspectJAutoProxy(proxyTargetClass = true)`. Learn why `@Transactional` on a private method silently does nothing.

---

### 1.7 Bean Validation Deep-Dive

**Why:** You're using `@NotBlank` and `@Email` — that's surface level. Real validation involves custom validators, cross-field validation, and validation groups.

- [ ] Create a custom `@UniqueEmail` annotation with its own `ConstraintValidator` that queries the database
- [ ] Create a custom `@StrongPassword` annotation (min length, uppercase, digit, special char)
- [ ] Implement cross-field validation: `@PasswordsMatch` on a class level (for password + confirmPassword)
- [ ] Use validation groups: `OnCreate.class` vs `OnUpdate.class` — email required on create but optional on update
- [ ] Understand `@Validated` (Spring) vs `@Valid` (Jakarta) — they're not the same

**Spring depth:** How `LocalValidatorFactoryBean` bridges Jakarta Validation with Spring's DI, allowing your custom validators to `@Autowire` repositories.

---

### Checkpoint 1

> Before moving on, you should be able to answer **yes** to all of these.

- [ ] Can you start the app with `ddl-auto=validate` and Flyway running all migrations cleanly?
- [ ] Are controllers returning `UserResponse` DTOs (not `User` entities) with MapStruct handling the mapping?
- [ ] Can you hit `GET /api/users/filter?role=ADMIN&name=john&page=0&size=10` and get paginated, filtered results?
- [ ] Do `createdAt`, `updatedAt`, `createdBy`, `updatedBy` populate automatically on create/update?
- [ ] Does `@LogExecutionTime` on a service method print timing in the logs? Does it **not** fire when called from within the same class? (Can you explain why?)
- [ ] Does `@RateLimit` on the login endpoint return 429 after the limit is hit?
- [ ] Does submitting a duplicate email on register return a validation error from your custom `@UniqueEmail` validator?

**Prove it:** Create a user via Swagger, update them, check the audit log table has entries, and verify the response has no `password` field and includes audit timestamps.

> **Detailed learning material:** [`plan/phase-1.md`](plan/phase-1.md)

---

## Phase 2 — Advanced Security

> **Goal:** Go from "JWT works" to "I understand Spring Security's architecture and can build any auth scheme."

### 2.1 Spring Authorization Server

**Why:** Your current JWT setup is hand-rolled — fine for learning, but Spring has a full OAuth2 Authorization Server. Understanding OAuth2 flows (authorization code, client credentials, refresh tokens) is mandatory senior knowledge.

- [ ] Add `spring-boot-starter-oauth2-authorization-server`
- [ ] Configure an authorization server that issues tokens
- [ ] Implement the `client_credentials` grant (service-to-service auth — you'll need this in Phase 3)
- [ ] Implement the `authorization_code` grant with PKCE (for browser clients)
- [ ] Implement refresh token rotation
- [ ] Store registered clients in the database (not in-memory)
- [ ] Understand the token endpoint, introspection endpoint, and JWKS endpoint

**Spring depth:** The filter chain architecture. How `SecurityFilterChain` beans are ordered. How `AuthorizationServerSecurityFilterChain` vs your regular `SecurityFilterChain` coexist.

---

### 2.2 Method-Level Security with Custom SpEL

**Why:** URL-based security (`requestMatchers(...)`) is coarse-grained. Real apps need "user can only edit their own profile" or "managers can see their team but not other teams." This is method-level security with custom expressions.

- [ ] Enable `@PreAuthorize` / `@PostAuthorize`
- [ ] Use built-in expressions: `@PreAuthorize("hasRole('ADMIN')")`
- [ ] Write custom SpEL: `@PreAuthorize("#id == authentication.principal.id or hasRole('ADMIN')")` — user can get themselves, admin can get anyone
- [ ] Implement a custom `PermissionEvaluator` — `@PreAuthorize("hasPermission(#id, 'User', 'EDIT')")`
- [ ] Build a `Permission` entity and a permissions table (RBAC with fine-grained permissions, not just roles)
- [ ] Use `@PostFilter` to filter collections: return only the users the current user is allowed to see

**Spring depth:** How Spring Security's `MethodSecurityInterceptor` wraps your beans in proxies. How SpEL is evaluated against the `SecurityExpressionRoot`. Read `DefaultMethodSecurityExpressionHandler`.

---

### 2.3 Security Event Handling

**Why:** You need to know when someone fails to log in 5 times, when a token is rejected, when a new device logs in. Spring Security publishes events for all of these.

- [ ] Listen to `AuthenticationSuccessEvent`, `AuthenticationFailureBadCredentialsEvent`
- [ ] Implement account lockout after N failed attempts (store count in DB, unlock after timeout)
- [ ] Log security events to the audit table you built in Phase 1
- [ ] Publish custom events: `UserRegisteredEvent`, `PasswordChangedEvent`

**Spring depth:** Spring's `ApplicationEventPublisher` and `@EventListener`. Understand synchronous vs `@Async` listeners and `@TransactionalEventListener(phase = AFTER_COMMIT)`.

---

### Checkpoint 2

> Before moving on, you should be able to answer **yes** to all of these.

- [ ] Can you obtain a token via the OAuth2 `/oauth2/token` endpoint using `client_credentials` grant?
- [ ] Can you use the `authorization_code` + PKCE flow to get a token for a browser client?
- [ ] Does a regular user get `403 Forbidden` when trying to `DELETE /api/users/{id}` (admin-only)?
- [ ] Can a user `GET /api/users/{their-own-id}` but not `GET /api/users/{someone-else-id}` (unless admin)?
- [ ] After 5 failed login attempts, is the account locked? Does it unlock after the configured timeout?
- [ ] Can you see `AuthenticationFailure` events in the audit log?

**Prove it:** Log in as a regular user, try to delete another user (get 403), try to view another user (get 403), view yourself (get 200). Then log in as admin and do all three (get 200/204). Check the audit trail shows every action.

> **Detailed learning material:** [`plan/phase-2.md`](plan/phase-2.md)

---

## Phase 3 — Extract & Distribute

> **Goal:** Break the monolith into services. Learn multi-module Maven, Spring Cloud, and what it actually takes to make services talk to each other.

### 3.1 Multi-Module Maven Project

**Why:** Before microservices, you need to understand multi-module builds — shared dependencies, parent POMs, and how to structure a project where multiple services share common code.

```
demo/
├── pom.xml                          (parent POM)
├── demo-common/                     (shared DTOs, exceptions, utilities)
├── demo-user-service/               (current app, trimmed)
├── demo-auth-service/               (extracted auth + OAuth2 server)
├── demo-notification-service/       (new, Phase 4)
├── demo-gateway/                    (API gateway)
└── demo-config-server/              (centralized config)
```

- [ ] Restructure into parent + child modules
- [ ] Move shared code (DTOs, exceptions, common config) to `demo-common`
- [ ] Each service has its own `application.properties`, its own main class, its own port
- [ ] Understand `<dependencyManagement>` in parent POM vs `<dependencies>` in child
- [ ] Build from root with `mvn clean install` — understand reactor build order

---

### 3.2 Build a Custom Spring Boot Starter

**Why:** This is the "I truly understand Spring Boot" badge. Every auto-configuration you use (`DataSource`, `Security`, etc.) is a starter. Building one proves you understand `@Conditional`, `META-INF/spring/`, and how Boot assembles itself.

- [ ] Create `demo-spring-boot-starter` module
- [ ] It should auto-configure: structured JSON logging, common exception handler, correlation ID filter, standard actuator config
- [ ] Use `@ConditionalOnClass`, `@ConditionalOnProperty`, `@ConditionalOnMissingBean`
- [ ] Create `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- [ ] Add `@ConfigurationProperties` with a `demo.starter.*` namespace
- [ ] Have every service depend on this starter and get all shared behavior for free

**Spring depth:** This is the deepest cut. Read `spring-boot-autoconfigure` source. Understand `@AutoConfiguration(before/after)`, the condition evaluation report (`--debug`), and `spring-boot-configuration-processor`.

---

### 3.3 Extract Auth Service

**Why:** The auth server is a natural first extraction — it has clear boundaries (issues tokens, validates clients, manages credentials).

- [ ] Move OAuth2 Authorization Server config to `demo-auth-service`
- [ ] User service becomes an **OAuth2 Resource Server** (validates tokens, doesn't issue them)
- [ ] Auth service owns the `users` and `credentials` tables
- [ ] User service calls auth service to resolve user details (via REST or shared DB — discuss tradeoffs)
- [ ] Configure `spring-boot-starter-oauth2-resource-server` with JWKS endpoint pointing to auth service

---

### 3.4 API Gateway with Spring Cloud Gateway

**Why:** Clients shouldn't know about individual services. The gateway is the single entry point — handles routing, rate limiting, CORS, and authentication pre-checks.

- [ ] Create `demo-gateway` module with `spring-cloud-starter-gateway`
- [ ] Route `/api/auth/**` → auth-service, `/api/users/**` → user-service
- [ ] Add global filters: logging, correlation ID injection, request/response timing
- [ ] Add rate limiting per client (use Redis + `RequestRateLimiterGatewayFilterFactory`)
- [ ] Configure circuit breaker at gateway level (fallback to error response when downstream is down)
- [ ] Propagate JWT token to downstream services

**Spring depth:** Gateway uses Spring WebFlux (reactive). Understand the difference between `WebMVC` (servlet, blocking) and `WebFlux` (Netty, non-blocking). Learn why gateway uses reactive and when you should/shouldn't.

---

### 3.5 Centralized Configuration

**Why:** When you have 5 services, duplicating config is a maintenance nightmare. Spring Cloud Config Server lets you store all config in one place (Git repo or file system).

- [ ] Create `demo-config-server` with `spring-cloud-config-server`
- [ ] Store service configs in a Git repo (or local directory for development)
- [ ] Configure each service as a config client
- [ ] Implement config refresh: `@RefreshScope` + `/actuator/refresh` endpoint
- [ ] Understand config precedence: local `application.properties` vs config server vs environment variables
- [ ] Use profiles: `application-dev.properties`, `application-prod.properties`

---

### 3.6 Service Discovery with Eureka

**Why:** Hardcoding `localhost:8081` for service URLs doesn't work when services scale or move. Service discovery lets services find each other by name.

- [ ] Add Eureka Server (can be embedded in config-server or standalone)
- [ ] Register all services as Eureka clients
- [ ] Use `@LoadBalanced RestTemplate` or `WebClient` to call services by name (`http://user-service/api/users`)
- [ ] Use Spring Cloud OpenFeign for declarative service-to-service calls
- [ ] Understand self-preservation mode, lease renewal, and what happens when Eureka goes down

---

### Checkpoint 3

> Before moving on, you should be able to answer **yes** to all of these.

- [ ] Does `mvn clean install` from the root build all modules successfully?
- [ ] Can you start all services independently on different ports?
- [ ] Does hitting the gateway at `localhost:8080/api/users` route to the user-service?
- [ ] Does the gateway propagate the JWT to downstream services?
- [ ] Does your custom starter auto-configure logging + error handling in every service without manual config?
- [ ] Can you run `--debug` on any service and see your starter's conditions being evaluated?
- [ ] If you stop user-service and hit the gateway, does it return a fallback error (not hang)?
- [ ] Can you change a config value in the config server and refresh it in a running service without restarting?
- [ ] Do services find each other by name via Eureka (no hardcoded URLs)?

**Prove it:** Start all services. Hit the gateway. Kill user-service. Hit the gateway again (get fallback). Restart user-service. Hit the gateway again (works). Check Eureka dashboard shows all registered services.

> **Detailed learning material:** [`plan/phase-3.md`](plan/phase-3.md)

---

## Phase 4 — Event-Driven Architecture

> **Goal:** Services shouldn't call each other for everything. Learn async communication with message brokers, event sourcing, and the patterns that prevent distributed systems from becoming a tangled mess.

### 4.1 Kafka Integration

**Why:** Synchronous REST calls between services create tight coupling and cascading failures. Kafka decouples producers from consumers and provides durability, ordering, and replay.

- [ ] Add Kafka (Docker Compose — `confluentinc/cp-kafka`)
- [ ] Create `demo-notification-service` that consumes events
- [ ] User service publishes events: `UserCreated`, `UserUpdated`, `UserDeleted`
- [ ] Notification service consumes them and (simulates) sending emails
- [ ] Understand topics, partitions, consumer groups, and offsets
- [ ] Configure serialization with JSON (`JsonSerializer` / `JsonDeserializer`)
- [ ] Handle deserialization errors (poison pills) with `ErrorHandlingDeserializer`

**Spring depth:** `KafkaTemplate`, `@KafkaListener`, `ConcurrentKafkaListenerContainerFactory`. How Spring Boot auto-configures Kafka from `spring.kafka.*` properties.

---

### 4.2 Transactional Outbox Pattern

**Why:** Publishing an event after saving to DB is unreliable — the DB save succeeds but Kafka publish fails, and your system is inconsistent. The outbox pattern guarantees consistency.

- [ ] Create an `outbox_events` table (id, aggregate_type, aggregate_id, event_type, payload, created_at, published)
- [ ] When saving a user, also insert into outbox within the same transaction
- [ ] Build a poller (or use `@Scheduled`) that reads unpublished events and sends to Kafka
- [ ] Mark events as published after successful send
- [ ] Understand why this works: single DB transaction guarantees atomicity

**Spring depth:** `@Transactional` propagation levels. What `REQUIRES_NEW` vs `REQUIRED` means. How `TransactionSynchronizationManager` and `@TransactionalEventListener` hook into transaction completion.

---

### 4.3 Idempotent Consumers

**Why:** Kafka guarantees at-least-once delivery, not exactly-once. Your consumer must handle receiving the same event twice without creating duplicate side effects.

- [ ] Create a `processed_events` table (event_id, processed_at)
- [ ] Before processing, check if event_id exists — skip if already processed
- [ ] Insert event_id within the same transaction as the business logic
- [ ] Test: publish the same event twice, verify it's processed only once
- [ ] Understand idempotency keys and how they differ from deduplication

---

### 4.4 Dead Letter Topics & Error Handling

**Why:** Events that fail to process shouldn't block the queue forever. They should be routed to a dead letter topic for investigation and possible replay.

- [ ] Configure `DefaultErrorHandler` with retry (3 attempts, exponential backoff)
- [ ] After max retries, route to a `.DLT` (dead letter topic)
- [ ] Build a small admin endpoint to view and replay DLT messages
- [ ] Understand the difference between retriable errors (timeout) and non-retriable (bad data)

---

### Checkpoint 4

> Before moving on, you should be able to answer **yes** to all of these.

- [ ] When you create a user, does an event appear in the Kafka topic?
- [ ] Does the notification service consume that event and log a simulated email?
- [ ] If Kafka is down when you create a user, does the user still save (outbox pattern)?
- [ ] When Kafka comes back up, does the outbox poller pick up the unsent event and publish it?
- [ ] If you publish the same event twice, does the notification service process it only once?
- [ ] If you send a malformed event, does it end up in the dead letter topic after retries?
- [ ] Can you replay a DLT message via the admin endpoint?

**Prove it:** Create a user. Check Kafka topic (event exists). Check notification service logs (email simulated). Stop Kafka. Create another user (succeeds — outbox stores event). Start Kafka. Wait for poller — event appears in topic. Send a corrupt message manually — watch retries, then see it in DLT.

> **Detailed learning material:** [`plan/phase-4.md`](plan/phase-4.md)

---

## Phase 5 — Resilience & Performance

> **Goal:** Make the system survive failures gracefully and handle load efficiently.

### 5.1 Resilience4j

**Why:** In a distributed system, a slow downstream service can cascade and take everything down. Circuit breakers, retries, and bulkheads prevent this.

- [ ] Add `resilience4j-spring-boot3` dependency
- [ ] **Circuit Breaker:** Wrap calls from user-service → auth-service. When auth-service is down, fail fast instead of waiting.
  - Configure thresholds: failure rate, slow call rate, wait duration in open state
  - Implement fallback methods
- [ ] **Retry:** Wrap Kafka publish with retry (transient broker failures)
  - Configure max attempts, wait duration, retry on specific exceptions
- [ ] **Rate Limiter:** Replace your AOP `@RateLimit` with Resilience4j's `@RateLimiter` (now backed by Redis for distributed rate limiting)
- [ ] **Bulkhead:** Limit concurrent calls to external services to prevent thread pool exhaustion
  - Understand semaphore vs thread pool bulkhead
- [ ] **TimeLimiter:** Set timeouts on async operations

**Spring depth:** How Resilience4j integrates via AOP (same mechanism as your custom annotations). How it publishes events to Micrometer for monitoring. How `@Order` controls aspect priority when multiple aspects are on one method.

---

### 5.2 Caching with Redis

**Why:** Database calls are expensive. Caching frequently-read, rarely-changed data is the single biggest performance win in most applications.

- [ ] Add Redis (Docker Compose) + `spring-boot-starter-data-redis`
- [ ] Use `@Cacheable("users")` on `getUserById`
- [ ] Use `@CacheEvict` on update and delete
- [ ] Use `@CachePut` on create
- [ ] Configure TTL per cache
- [ ] Move your `@RateLimit` data from in-memory map to Redis (distributed rate limiting)
- [ ] Understand cache stampede / thundering herd and how to prevent it (`sync = true`)
- [ ] Understand cache-aside vs read-through vs write-through patterns

**Spring depth:** `CacheManager`, `RedisCacheConfiguration`, `RedisTemplate` vs `StringRedisTemplate`. How `@Cacheable` generates cache keys (SpEL `#id` vs `key` attribute). How `CacheInterceptor` (another AOP proxy) wraps your method.

---

### 5.3 Async Processing

**Why:** Some operations don't need to block the request: sending notifications, writing audit logs, updating search indexes. `@Async` offloads these to a background thread pool.

- [ ] Enable `@Async` with `@EnableAsync`
- [ ] Configure a custom `TaskExecutor` (thread pool size, queue capacity, rejection policy)
- [ ] Make audit logging async
- [ ] Make notification event publishing async
- [ ] Handle async errors with `AsyncUncaughtExceptionHandler`
- [ ] Use `CompletableFuture` return types for async methods that return results
- [ ] Understand: `@Async` uses the same proxy mechanism as `@Transactional` — self-invocation won't work

**Spring depth:** `ThreadPoolTaskExecutor` configuration. How `@Async` proxy delegates to a different thread. What happens when the queue is full (rejection policies). How `SecurityContext` propagation works (or doesn't) across async boundaries — you need `DelegatingSecurityContextTaskExecutor`.

---

### 5.4 Database Performance

**Why:** Most application performance problems are database problems. N+1 queries, missing indexes, and unbounded fetches destroy performance.

- [ ] Enable Hibernate statistics: `spring.jpa.properties.hibernate.generate_statistics=true`
- [ ] Detect N+1 queries — add a relationship (e.g., `User` has many `AuditLog` entries) and observe the queries
- [ ] Fix with `@EntityGraph` or `JOIN FETCH` in JPQL
- [ ] Use `@BatchSize` for batch fetching
- [ ] Add database indexes via Flyway migration for commonly queried fields
- [ ] Use `@QueryHints` for read-only queries (`@QueryHint(name = HINT_READONLY, value = "true")`)
- [ ] Understand `LAZY` vs `EAGER` fetch types and why `EAGER` is almost never correct
- [ ] Use Hibernate's slow query log: `spring.jpa.properties.hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS=100`

---

### Checkpoint 5

> Before moving on, you should be able to answer **yes** to all of these.

- [ ] When auth-service is down, does user-service return a fallback response within 2 seconds (not hang for 30)?
- [ ] Can you see the circuit breaker state transition in logs: CLOSED → OPEN → HALF_OPEN → CLOSED?
- [ ] Does `GET /api/users/{id}` hit Redis on the second call (not the database)? Can you verify by checking Hibernate SQL logs?
- [ ] After updating a user, is the cache entry evicted (next GET hits the DB again)?
- [ ] Is audit logging running on a separate thread (verify with thread name in logs)?
- [ ] Can you explain why `@Async` on a method called from within the same bean won't work?
- [ ] Have you found and fixed at least one N+1 query using `@EntityGraph` or `JOIN FETCH`?

**Prove it:** Call `GET /api/users/1` twice — first call shows SQL in logs, second doesn't (cache hit). Kill auth-service — user-service responds with fallback in < 2s. Check Redis CLI: `GET users::1` shows cached data. Run Hibernate statistics — zero N+1 queries on the user list endpoint.

> **Detailed learning material:** [`plan/phase-5.md`](plan/phase-5.md)

---

## Phase 6 — Observability

> **Goal:** You can't fix what you can't see. Build comprehensive monitoring, tracing, and logging across all services.

### 6.1 Structured Logging

**Why:** `System.out.println` and unstructured logs are unsearchable in production. Structured (JSON) logging with correlation IDs lets you trace a request across services.

- [ ] Configure Logback to output JSON (use `logstash-logback-encoder`)
- [ ] Add MDC (Mapped Diagnostic Context) fields: `correlationId`, `userId`, `service`
- [ ] Create a filter that extracts/generates correlation ID from `X-Correlation-ID` header
- [ ] Propagate correlation ID across service calls (add to outgoing headers)
- [ ] Propagate correlation ID through Kafka messages (message headers)
- [ ] Move this into your custom starter — all services get it for free

---

### 6.2 Micrometer Metrics & Prometheus

**Why:** Metrics tell you how your system is performing right now: request rate, error rate, latency, JVM memory, DB connection pool usage.

- [ ] Add `micrometer-registry-prometheus` dependency
- [ ] Expose `/actuator/prometheus` endpoint
- [ ] Add custom metrics:
  - Counter: `user.registrations.total`
  - Timer: `user.service.get.duration`
  - Gauge: `user.active.count`
- [ ] Use `@Timed` annotation on controller methods
- [ ] Set up Prometheus (Docker) to scrape metrics from all services
- [ ] Build a Grafana dashboard: request rate, p50/p95/p99 latency, error rate, JVM heap

**Spring depth:** How `MeterRegistry` is auto-configured. How `@Timed` uses AOP. How `MeterBinder` interface lets you register custom meters at startup.

---

### 6.3 Distributed Tracing

**Why:** A single user request might hit gateway → user-service → auth-service → database → Kafka. When something is slow, you need to see the full trace across services.

- [ ] Add `micrometer-tracing-bridge-otel` + Zipkin exporter
- [ ] Run Zipkin (Docker)
- [ ] Verify trace propagation: gateway → service → service
- [ ] Verify trace propagation through Kafka messages
- [ ] Add custom spans for important business operations
- [ ] Understand trace ID vs span ID vs parent span ID

---

### 6.4 Spring Boot Actuator Deep-Dive

**Why:** Actuator is far more than `/health`. It's a full operational toolkit.

- [ ] Expose all relevant endpoints: health, metrics, info, env, configprops, conditions
- [ ] Create a custom `HealthIndicator` (check Kafka connectivity, check auth-service availability)
- [ ] Use health groups: `liveness` (can the app accept traffic?) vs `readiness` (is it ready to serve?)
- [ ] Secure actuator endpoints — separate security config for actuator paths
- [ ] Use `/actuator/conditions` to debug auto-configuration

---

### Checkpoint 6

> Before moving on, you should be able to answer **yes** to all of these.

- [ ] Are logs output as structured JSON with `correlationId`, `userId`, and `service` fields?
- [ ] Can you make a request to the gateway and see the same `correlationId` in logs across gateway → user-service → auth-service?
- [ ] Does `/actuator/prometheus` expose your custom metrics (`user.registrations.total`, etc.)?
- [ ] Can you open Grafana and see a dashboard showing request rate, latency percentiles, and JVM heap for all services?
- [ ] Can you open Zipkin and trace a single request through all services it touched?
- [ ] Does your custom `HealthIndicator` show `DOWN` when Kafka or auth-service is unreachable?
- [ ] Does `/actuator/health/readiness` return `DOWN` when dependencies are unavailable?

**Prove it:** Make 10 requests. Open Grafana — see the spike in request rate. Open Zipkin — pick a trace, see the full call chain with timing per span. Kill Kafka — check `/actuator/health` shows degraded status. Grep logs by correlationId — see the full request lifecycle across services.

> **Detailed learning material:** [`plan/phase-6.md`](plan/phase-6.md)

---

## Phase 7 — Advanced Testing

> **Goal:** Build a test suite that gives you confidence to refactor and deploy without fear.

### 7.1 Testcontainers

**Why:** Mocking the database gives you a false sense of security. Testcontainers spins up real PostgreSQL, Redis, and Kafka in Docker for your tests — same as production.

- [ ] Add `spring-boot-testcontainers` dependency
- [ ] Configure `@ServiceConnection` with PostgreSQL container
- [ ] Add Redis container for cache tests
- [ ] Add Kafka container for event tests
- [ ] Use `@DynamicPropertySource` for custom container config
- [ ] Understand container reuse (`testcontainers.reuse.enable=true`) for faster test runs

---

### 7.2 Slice Tests

**Why:** `@SpringBootTest` loads everything — it's slow. Slice tests load only what you need.

- [ ] `@WebMvcTest(UserController.class)` — tests controller layer only (mock the service)
- [ ] `@DataJpaTest` — tests repository layer only (loads JPA + embedded DB or Testcontainer)
- [ ] `@JsonTest` — tests JSON serialization/deserialization (verify your DTOs serialize correctly)
- [ ] Create custom slice: `@UserServiceTest` that loads only service + repository layers
- [ ] Understand what each slice annotation includes/excludes

**Spring depth:** How `@*Test` annotations use `@ImportAutoConfiguration` to load only specific auto-configurations. Read `WebMvcTypeExcludeFilter`.

---

### 7.3 Custom Test Annotations

**Why:** You'll find yourself repeating `@SpringBootTest @Testcontainers @ActiveProfiles("test")` on every test class. Custom annotations clean this up and standardize test configuration.

- [ ] Create `@IntegrationTest` — meta-annotation combining `@SpringBootTest` + Testcontainers + test profile
- [ ] Create `@WithMockAdmin` / `@WithMockUser` custom security context annotations
- [ ] Create `@DatabaseTest` for repository-layer tests with Testcontainers

---

### 7.4 Architecture Tests with ArchUnit

**Why:** Code conventions (controllers shouldn't access repositories directly, services shouldn't import controller classes) are usually enforced by code review. ArchUnit enforces them automatically.

- [ ] Add ArchUnit dependency
- [ ] Write rules:
  - Controllers should only depend on services and DTOs
  - Services should only depend on repositories and entities
  - No cycles between packages
  - All repository interfaces should be in `repository` package
  - All entities should be annotated with `@Entity`
- [ ] Run as part of the test suite — broken rules fail the build

---

### 7.5 Contract Testing

**Why:** When service A depends on service B's API, how do you test that B doesn't break A? Contract tests define the expected API shape and verify both sides honor it.

- [ ] Add Spring Cloud Contract to user-service (producer side)
- [ ] Write contracts in Groovy DSL or YAML: "when POST /api/users with this body, return 201 with this shape"
- [ ] Generate stubs for consumers
- [ ] In gateway or other services (consumer side), use generated stubs in tests
- [ ] Understand consumer-driven contracts vs provider-driven

---

### Checkpoint 7

> Before moving on, you should be able to answer **yes** to all of these.

- [ ] Do tests run against real PostgreSQL, Redis, and Kafka via Testcontainers (not mocks, not H2)?
- [ ] Does `@WebMvcTest(UserController.class)` run in under 3 seconds (not loading the full context)?
- [ ] Does `@DataJpaTest` verify your Specifications and custom queries against a real Postgres schema?
- [ ] Does `@JsonTest` verify that `UserResponse` serialization doesn't include `password`?
- [ ] Can you use `@IntegrationTest` on any test class and get Testcontainers + test profile automatically?
- [ ] Do ArchUnit rules fail the build if someone adds a direct repository call from a controller?
- [ ] Does changing user-service's API response shape fail the contract test in the consumer?

**Prove it:** Run the full test suite — `mvn test`. All pass. Deliberately break a contract (change a field name in UserResponse). Run tests again — contract test fails with a clear message explaining the mismatch. Deliberately add a `@Autowired UserRepository` to a controller — ArchUnit test fails.

> **Detailed learning material:** [`plan/phase-7.md`](plan/phase-7.md)

---

## Phase 8 — Production Readiness

> **Goal:** Everything needed to deploy this system for real.

### 8.1 Docker Compose — Full Stack Local Development

**Why:** You should be able to `docker compose up` and have the entire system running — all services, databases, Kafka, Redis, Prometheus, Grafana, Zipkin.

- [ ] Write a `docker-compose.yml` with all infrastructure (PostgreSQL, Kafka, Zookeeper, Redis, Prometheus, Grafana, Zipkin)
- [ ] Write Dockerfiles for each service (multi-stage builds for small images)
- [ ] Add a `docker-compose.override.yml` for development (volume mounts, debug ports)
- [ ] Configure service startup order with `depends_on` + health checks
- [ ] Document how to run the full system

---

### 8.2 Graceful Shutdown

**Why:** When a service restarts, in-flight requests and Kafka messages shouldn't be lost.

- [ ] Configure `server.shutdown=graceful` with `spring.lifecycle.timeout-per-shutdown-phase=30s`
- [ ] Understand what happens: stop accepting new requests → finish in-flight → close connections
- [ ] Configure Kafka consumer graceful shutdown (commit offsets before stopping)
- [ ] Test: send a request, kill the service mid-processing, verify it completes

---

### 8.3 Configuration & Secrets Management

**Why:** Passwords and keys in `application.properties` is a security violation. Learn how to externalize secrets properly.

- [ ] Move sensitive values to environment variables
- [ ] Use `spring.config.import=optional:configserver:` for Spring Cloud Config integration
- [ ] Understand Spring Boot's configuration precedence (17 levels!)
- [ ] Use `@ConfigurationProperties` with validation (`@Validated`, `@NotBlank`)
- [ ] Create `@ConfigurationProperties` classes for all custom config (replace scattered `@Value` annotations)

---

### 8.4 API Versioning

**Why:** Once clients depend on your API, you can't change it without breaking them. Versioning lets old and new clients coexist.

- [ ] Implement URL-based versioning: `/api/v1/users`, `/api/v2/users`
- [ ] Understand header-based versioning (`Accept: application/vnd.demo.v2+json`) and when it's appropriate
- [ ] Create a `v2` of the user endpoint with a different response shape
- [ ] Keep `v1` working with the old shape — use separate DTOs and mappers

---

### 8.5 Database Multi-Tenancy

**Why:** SaaS applications often need data isolation between tenants. Schema-per-tenant is a powerful pattern that reuses one DB instance with separate schemas.

- [ ] Implement `CurrentTenantIdentifierResolver` — resolve tenant from JWT claim or HTTP header
- [ ] Implement `MultiTenantConnectionProvider` — route to the correct schema
- [ ] Configure Hibernate multi-tenancy: `hibernate.multiTenancy=SCHEMA`
- [ ] Write Flyway config to run migrations on all tenant schemas
- [ ] Test: two tenants, same user ID, different data — verify complete isolation

**Spring depth:** How Hibernate's `SessionFactory` delegates to your tenant resolver per request. How to combine this with Spring Security's `Authentication` object.

---

### Checkpoint 8 (Final)

> This is the finish line. If you can do all of this, you've built a senior-level distributed system.

- [ ] Does `docker compose up` start the entire system — all services + all infrastructure?
- [ ] Can you register a user, log in, use the token, CRUD users, and see events flow through Kafka — all through the gateway?
- [ ] When you `docker compose stop user-service`, does the gateway return a graceful fallback? When you restart, does it recover automatically?
- [ ] Are there zero `@Value` annotations left? All config uses `@ConfigurationProperties` with validation?
- [ ] Can you call `/api/v1/users` and `/api/v2/users` and get different response shapes?
- [ ] Can you set the `X-Tenant-ID` header and see completely isolated data between tenants?
- [ ] Can you kill a service mid-request and verify the response still completes (graceful shutdown)?
- [ ] Is every secret externalized (no passwords in committed properties files)?

**Prove it:** Cold start: `docker compose up -d`. Wait for health checks. Run the full test scenario end-to-end through the gateway. Check Grafana dashboards. Check Zipkin traces. Check logs are structured JSON with correlation IDs. Check two tenants have isolated data. Tear it all down: `docker compose down`. Everything cleans up.

> **Detailed learning material:** [`plan/phase-8.md`](plan/phase-8.md)

---

## Appendix — Infrastructure Cheat Sheet

For local development, your `docker-compose.yml` will include:

| Service | Image | Port | Purpose |
|---|---|---|---|
| PostgreSQL | `postgres:17` | 5432 | Primary database |
| Kafka | `confluentinc/cp-kafka` | 9092 | Message broker |
| Redis | `redis:7` | 6379 | Cache + rate limiting |
| Zipkin | `openzipkin/zipkin` | 9411 | Distributed tracing |
| Prometheus | `prom/prometheus` | 9090 | Metrics collection |
| Grafana | `grafana/grafana` | 3000 | Dashboards |

---

## Appendix — Topic Map

Shows which **Spring concept** each topic teaches and where it applies:

| Spring Concept | Phases | Applied Where |
|---|---|---|
| AOP / Proxies | 1.6, 2.2, 5.1, 5.2, 5.3 | Custom annotations, security, resilience, caching, async |
| Auto-configuration | 3.2 | Custom starter |
| `@Transactional` internals | 4.2 | Outbox pattern |
| Event system | 2.3, 4.1 | Security events, Kafka |
| Bean lifecycle | 3.2, 6.4 | Starter, Actuator |
| Security filter chain | 2.1, 3.4 | OAuth2, Gateway |
| Reactive (WebFlux) | 3.4 | Gateway |
| Configuration model | 3.5, 8.3 | Config server, `@ConfigurationProperties` |
| Testing infrastructure | 7.1–7.5 | Testcontainers, slices, contracts |
