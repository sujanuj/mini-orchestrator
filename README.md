# mini-orchestrator

A from-scratch container orchestrator, written in Java, deploying real Java
microservices — built and run entirely locally, no cloud provider involved.

The goal isn't to reimplement Kubernetes. It's to actually build the core
mechanism every container orchestrator is built around — a reconciliation
loop that continuously compares desired state to actual state and corrects
drift — rather than only ever operating one through `kubectl`. Understanding
what's underneath the abstraction is the point.

This README follows the same approach as this portfolio's other projects
(see [`lsmdb`](https://github.com/sujanuj/lsmdb),
[`geospatial-intel`](https://github.com/sujanuj/geospatial-intel)): real
measured/verified output, the actual bugs hit during development, and an
honest list of what isn't built yet — not a sanitized changelog.

## Status

**Phase 1: Two real microservices + a verified Docker Compose baseline — done**

- [x] `inventory-service` — in-memory stock, thread-safe atomic reservation
- [x] `orders-service` — calls `inventory-service` over HTTP, with explicit
      timeouts and three distinct, deliberately different failure-mode
      responses (not one generic error)
- [x] Multi-stage Dockerfiles for both (build stage with full Maven
      toolchain, slim `jre-alpine` runtime image)
- [x] `docker-compose.yml` wiring both together with real container-to-
      container DNS and a health-gated startup order
- [x] Every real path verified end to end against actually running
      containers — not simulated, not assumed — see **Verified behavior**
      below
- [x] Two real bugs and one real operational lesson, found and fixed
      during this phase — kept in below, not edited out

**Phase 2 (next): the orchestrator core.** Not started yet. See **Roadmap**.

---

## Why two services, not one

A single service has nothing to orchestrate *around* — no dependency to
fail, no inter-service call to time out, no meaningful difference between
"my container is up" and "my container can actually do its job." Two
services with a real HTTP dependency between them is the minimum shape
that makes an orchestrator's actual value visible: what happens to
`orders-service` when `inventory-service` is killed, restarted, or
slow — and whether the orchestrator built in Phase 2+ can detect and
correct that automatically.

## Architecture

```
        POST /orders                    POST /inventory/reserve
 client ─────────────► orders-service ─────────────────────► inventory-service
                        (port 8080)      (container DNS:      (port 8080 internally,
                                          inventory-service)    8081 on host)
```

Both services expose `/health` for the orchestrator's future health
checker (Phase 2) to poll, and both ship a `HEALTHCHECK` in their
Dockerfile using the same endpoint, so `docker ps` and Compose's own
`depends_on: condition: service_healthy` already agree with what the
orchestrator will later check.

## `inventory-service`

In-memory stock (`widget: 100`, `gadget: 50`, `gizmo: 0` — the zero is
deliberate, to exercise the out-of-stock path from the very first
request, not just after enough orders drain it).

- `GET /health` → `{"status": "UP"}`
- `GET /inventory` → full stock map
- `GET /inventory/{item}` → single item, or `404`
- `POST /inventory/reserve` `{"item": "...", "quantity": N}` → atomically
  decrements stock, or `409 Conflict` if insufficient, or `404` if the
  item doesn't exist

**Why `ConcurrentHashMap.compute()`, not a separate check-then-decrement:**
this service is explicitly meant to eventually run with multiple
replicas once the orchestrator exists. A naive "read current quantity,
then write new quantity" under concurrent requests from multiple
`orders-service` replicas (or multiple orchestrator-managed instances of
`inventory-service` itself, behind a future load balancer) would race —
two requests could both read "5 in stock" before either writes, and both
succeed, overselling by one. `compute()` makes the read-check-write one
atomic operation instead.

**Why `409`, not `400`, for insufficient stock:** the request is
well-formed — `400` would suggest the caller sent something malformed,
when the actual problem is a state conflict: the current stock level
just can't satisfy an otherwise-valid request. This is the more precise
HTTP semantic, and it's what `orders-service`'s error handling is
written against.

## `orders-service`

- `GET /health` → `{"status": "UP"}`
- `GET /orders/{orderId}` → a previously placed order, or `404`
- `POST /orders` `{"item": "...", "quantity": N}` → calls
  `inventory-service`, returns one of three genuinely different outcomes

**Why `/health` doesn't check `inventory-service`:** a "deep" health
check that depends on a downstream service would make `orders-service`
report itself unhealthy every time `inventory-service` is mid-restart —
exactly the kind of cascading-failure amplification a health check
should prevent, not cause. `orders-service` being up, and
`orders-service` being able to reach `inventory-service` *right now*,
are two different facts. Only the first belongs in `/health`; the
second is what a `POST /orders` failure communicates instead.

**Why an explicit 2s connect / 3s read timeout
(`RestTemplateConfig.java`):** this matters specifically because this
service exists to demonstrate resilience under the orchestrator (Phase
2+). When `inventory-service` is killed to test auto-restart, a request
that lands in that window should fail fast and clearly — not hang on
Java's much longer default timeout while the orchestrator is in the
middle of bringing the dependency back.

**The three outcomes of `POST /orders`, deliberately distinguished
rather than collapsed into one generic error:**

| Outcome | HTTP status | Order status recorded | Real cause |
|---|---|---|---|
| Success | `201` | `CONFIRMED` | Stock reserved |
| Legitimately out of stock | `409` | `REJECTED_OUT_OF_STOCK` | `inventory-service` correctly said no — a business outcome, not a failure |
| Unknown item | `400` | *(not recorded)* | Bad request |
| `inventory-service` unreachable | `503` | *(not recorded)* | Infrastructure failure — connection refused or timeout. **This is exactly the case Phase 2's auto-restart is meant to make transient instead of permanent.** |

## Verified behavior

Every path below was run against actually running containers, not
simulated. Real output, copied from an actual terminal session:

```bash
$ curl http://localhost:8081/health
{"status":"UP"}

$ curl http://localhost:8081/inventory
{"widget":100,"gadget":50,"gizmo":0}

$ curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
    -d '{"item":"widget","quantity":5}'
{"orderId":"dd069ed5-64da-4586-806c-e36c33562b0e","item":"widget","quantity":5,"status":"CONFIRMED"}

$ curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
    -d '{"item":"gizmo","quantity":1}'
{"orderId":"0b54b4af-eaf2-4155-8ab9-678d626fb18c","item":"gizmo","quantity":1,"status":"REJECTED_OUT_OF_STOCK"}

$ curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
    -d '{"item":"nonexistent","quantity":1}'
{"error":"unknown item: nonexistent"}

$ curl http://localhost:8081/inventory
{"widget":95,"gadget":50,"gizmo":0}
```

That last line is the important one: `widget` dropped from `100` to
`95` after the confirmed order — proof the reservation actually
persisted state inside `inventory-service`, not just that
`orders-service` returned a plausible-looking response.

**The infrastructure-failure path, specifically:**

```bash
$ docker kill inventory-service
inventory-service

$ curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
    -d '{"item":"widget","quantity":5}'
{"error":"inventory-service unavailable: I/O error on POST request for \"http://inventory-service:8080/inventory/reserve\": inventory-service"}
```

Returned in a few seconds, not hung — confirming the connect/read
timeout in `RestTemplateConfig` actually works, and confirming the
`ResourceAccessException` handler in `OrdersController` catches exactly
the failure mode it was written for.

**Restart and state reset:**

```bash
$ docker compose up -d inventory-service
$ curl http://localhost:8081/inventory
{"widget":100,"gadget":50,"gizmo":0}
```

Fresh in-memory state after restart, as expected for a service with no
persistence layer — see **Known limitations**.

## Bugs and lessons, found and fixed during this phase

Kept here instead of squashed out of the commit history, same as every
other project in this portfolio.

### 1. A real Spring Boot version mismatch

`RestTemplateConfig.java` originally called:

```java
return builder
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(3))
        .build();
```

which failed to compile inside the actual Docker build (Maven Central
isn't reachable from every environment code gets written in, so this
surfaced only once a real `mvn package` ran against the real
dependency):

```
RestTemplateConfig.java:[25,17] cannot find symbol
symbol:   method connectTimeout(java.time.Duration)
location: variable builder of type org.springframework.boot.web.client.RestTemplateBuilder
```

`connectTimeout`/`readTimeout` are the current `RestTemplateBuilder`
method names in newer Spring Boot releases; `pom.xml` pins Spring Boot
**3.3.4**, where those methods are still named `setConnectTimeout`/
`setReadTimeout`. Fixed by using the names that match the pinned
version, not the newest ones. A reminder that "this compiles against
the latest docs" and "this compiles against the version actually
pinned in `pom.xml`" are different claims.

### 2. A filename collision with an unrelated project

`docker-compose.yml`, `pom.xml`, `Dockerfile`, and `application.properties`
all landed in a shared `~/Downloads` folder that already had files with
those exact same generic names from a completely different, earlier
project (an unrelated Redis-based rate limiter). The browser silently
renamed the second copy of each with a `_1` suffix rather than erroring
— so `docker compose up` from `~/Downloads` picked up the **old,
unrelated** `docker-compose.yml` and tried to start a service called
`ratelimiter-redis` that had nothing to do with this project, failing
with a container-name conflict.

Not a code bug — a real lesson about generic filenames landing in a
shared directory. Fixed by moving every file into its correct nested
project path immediately, and by always `cd`-ing into the actual
project directory before running any `docker compose` command rather
than assuming the shell's current directory.

### 3. "Started" doesn't mean "ready"

Immediately after `docker compose up -d inventory-service` reported
`Container inventory-service Started`, a `curl` against it returned:

```
curl: (56) Recv failure: Connection reset by peer
```

not because anything was actually wrong, but because Docker considers a
container "started" the instant its process launches — the JVM then
still needs real time to boot Spring, initialize Tomcat, and bind port
8080 (confirmed from the full startup log: `Started InventoryApplication
in 0.533 seconds`). A request that lands inside that startup window hits
a socket with nothing listening on it yet. The very next `curl`, a
moment later, succeeded cleanly.

This is exactly *why* `HEALTHCHECK` directives and Compose's
`depends_on: condition: service_healthy` exist and matter — "container
started" and "container ready to take traffic" are genuinely different
facts, and conflating them is a common, real source of flaky-looking
integration tests and premature traffic routing in real deployment
pipelines, not just a local quirk. Phase 2's health checker is built on
this exact distinction.

## Known limitations

- **In-memory state only, in both services.** No database, no
  persistence. A container restart resets `inventory-service`'s stock to
  its hardcoded seed values and clears every order `orders-service` has
  recorded. This is a deliberate scope cut to keep the orchestrator
  (Phase 2+) the actual subject of this project, not a data layer.
- **No deep/cascading health checks**, by design — see the `/health`
  design note above. This means the orchestrator's health checker
  (Phase 2) will only ever know "is this specific container's own
  process healthy," not "is this container's dependency chain healthy."
- **Single Docker host.** Both services and the future orchestrator run
  against one local Docker daemon. True multi-node scheduling (multiple
  physical/VM Docker hosts) is real infrastructure complexity that's
  explicitly out of scope for this project's demo — see **Roadmap**.
- **No authentication, no TLS, no rate limiting.** Neither service
  restricts who can call it. Fine for a local orchestrator demo;
  would need real hardening for anything beyond that.

## Roadmap

**Phase 2 — the orchestrator core.** A declarative spec (image, replica
count, health endpoint) plus a reconciliation loop, talking directly to
the Docker Engine API via the `docker-java` client library rather than
shelling out to the `docker` CLI.

**Phase 3 — health checking + auto-restart.** Periodic HTTP health
checks per container; on failure, restart with backoff; track restart
counts. This is the phase that turns the `503`s demonstrated above from
a permanent failure into a transient one.

**Phase 4 — rolling deploys.** Deploy a new image version with
zero-downtime replacement: start the new container, wait for it to pass
health checks, only then stop the old one. The headline demo: `docker
kill` a container mid-traffic and watch the system self-heal live.

**Phase 5 — a CLI** (`apply`, `status`, `scale`, `rollback`) against this
project's own spec format.

**Explicitly out of scope, named now rather than discovered later:**
true multi-node scheduling across multiple Docker hosts. The scheduler
will be built with a pluggable "node" interface so the architecture is
real, but the actual demo targets a single Docker host — see **Known
limitations**.

## Running it

```bash
git clone https://github.com/sujanuj/mini-orchestrator.git
cd mini-orchestrator
docker compose up --build
```

In another terminal:

```bash
curl http://localhost:8081/health
curl http://localhost:8081/inventory

curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"item":"widget","quantity":5}'
```

## Project layout

```
mini-orchestrator/
├── docker-compose.yml
└── services/
    ├── inventory-service/
    │   ├── pom.xml
    │   ├── Dockerfile
    │   └── src/main/
    │       ├── java/com/sujanuj/inventory/
    │       │   ├── InventoryApplication.java
    │       │   └── InventoryController.java
    │       └── resources/application.properties
    └── orders-service/
        ├── pom.xml
        ├── Dockerfile
        └── src/main/
            ├── java/com/sujanuj/orders/
            │   ├── OrdersApplication.java
            │   ├── OrdersController.java
            │   └── RestTemplateConfig.java
            └── resources/application.properties
```
