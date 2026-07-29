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

## Table of contents

- [Status](#status)
- [Design philosophy](#design-philosophy)
- [Why two services, not one](#why-two-services-not-one)
- [Architecture](#architecture)
- [`inventory-service`](#inventory-service)
- [`orders-service`](#orders-service)
- [Verified behavior (Phase 1)](#verified-behavior-phase-1)
- [Bugs and lessons, found and fixed during Phase 1](#bugs-and-lessons-found-and-fixed-during-phase-1)
- [Known limitations (Phase 1)](#known-limitations-phase-1)
- [Phase 2: a from-scratch orchestrator core](#phase-2-a-from-scratch-orchestrator-core)
- [Phase 3: health-aware reconciliation and auto-restart](#phase-3-health-aware-reconciliation-and-auto-restart)
- [Phase 4: rolling deploys](#phase-4-rolling-deploys)
- [Anatomy of one reconciliation tick](#anatomy-of-one-reconciliation-tick)
- [Kubernetes concept mapping](#kubernetes-concept-mapping)
- [Why build this instead of just running Kubernetes](#why-build-this-instead-of-just-running-kubernetes)
- [What I'd change at scale](#what-id-change-at-scale)
- [Roadmap](#roadmap)
- [Running it](#running-it)
- [Project layout](#project-layout)

## Design philosophy

Three principles run through every phase of this project, and they're
worth stating explicitly up front rather than leaving them to be inferred
phase by phase:

**1. The decision logic is a pure function; the Docker calls are a "dumb"
translation layer, on purpose.** `Reconciler.reconcile()` never touches
Docker, never does I/O, never has a side effect — it's plain data in,
plain data out. `DockerActuator` never decides anything; it just executes
what `Reconciler` already decided. This split is what makes 36 tests of
genuinely tricky logic (backoff timing, health-aware replica counting,
rolling-deploy pacing) runnable with plain `javac`/`java`, on any machine,
with no Docker daemon and no network access at all. It's the single
architectural decision this whole project's testability depends on.

**2. State that needs to persist across ticks is explicit input and
output, not a hidden field.** `RestartState` and `ReconcileResult` exist
specifically so `Reconciler.reconcile()` can "remember" something (backoff
timers) between calls without becoming stateful itself — the caller
(`Main`) owns the state and passes it in fresh each tick. This is a small
design choice with an outsized effect on testability: a test can construct
any `RestartState` it wants and assert on exactly what comes back, with no
setup/teardown, no mocking, no shared mutable state between tests.

**3. Evidence over assertion, in the documentation as much as in the
code.** Every phase in this README is backed by either real, copy-pasted
terminal output or a real, runnable self-test — and where something
*wasn't* confirmed yet (see Phase 4's rolling-deploy retirement sequence),
that gap is stated explicitly rather than glossed over. A README that
only ever shows the happy path is optimizing for looking finished over
being trustworthy; this one tries to do the opposite.

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

**Phase 2: A from-scratch orchestrator core — done**

- [x] `orchestrator/`: a pure, dependency-free reconciliation engine
      (`Reconciler.reconcile`) plus a thin Docker Engine API layer
      (`DockerActuator`) that translates its decisions into real container
      operations via `docker-java`
- [x] A declarative YAML spec (image, replica count, ports, env) parsed
      with SnakeYAML, re-read from disk on every reconciliation tick — so
      editing `replicas: N` and saving is a live scaling operation with
      zero orchestrator restart
- [x] Scale up, scale down (newest-replica-first), and orphan cleanup
      (a service removed from the spec entirely) all handled as one
      unified diff between desired and actual state
- [x] The pure decision logic is genuinely unit-tested — 8/8 self-tests
      passing, with zero Docker daemon or external dependency required to
      run them (see **Verified behavior**)
- [x] Verified live against a real Docker daemon: real containers created,
      started, health-checked by Docker itself, and scaled from 1 to 3
      replicas with no orchestrator restart, confirmed independently via
      `docker ps`, not just orchestrator log output
- [x] Six real bugs, found and fixed by actually running this against
      real tooling — kept in below in full, not summarized away

**Phase 3: Health-aware reconciliation and auto-restart — done**

- [x] Real Docker `HEALTHCHECK` status (not just "is it running") consulted
      per container every tick via `inspectContainerCmd` -- a container
      that's alive as a process but failing its healthcheck is now
      detected and replaced, closing Phase 2's biggest named limitation
- [x] Only genuinely `UNHEALTHY` containers ever get restarted;
      `STARTING` (still within the Dockerfile's grace period) and `NONE`
      (no healthcheck declared on the image) both count toward desired
      replicas and are never touched based on health
- [x] Real exponential backoff (`RestartState`) so a persistently
      crash-looping service gets restarted immediately the first time,
      then with growing delay, capped, rather than thrashed every 5s
      forever
- [x] "Restart" isn't a new, separate kind of action -- an unhealthy
      container is stopped, and the ordinary scale-up path (which now
      excludes it from the healthy count) naturally starts its
      replacement, the same code path as any other scale-up
- [x] `StopReason` (`SCALE_DOWN` / `UNHEALTHY` / `ORPHANED`) added so the
      reconciliation log says *why* a container was stopped, not just
      that it was
- [x] The pure core's self-test suite grew from 8 to **22 tests, all
      passing**, including the tricky part: backoff actually preventing
      a repeat restart within its window, allowing one again once the
      window passes, and resetting cleanly after recovery
- [x] Verified live against 4 real, 3-day-old running containers: the
      health-inspection code correctly read all of them as healthy and
      changed nothing -- a meaningful negative result, since a subtly
      wrong health mapping would have shown up as a storm of unwanted
      restarts instead of quiet convergence

**Phase 4: Rolling deploys — done**

- [x] Every container now tagged with the exact image it was started
      from (`mini-orchestrator.image` label, set at creation), which is
      what makes image drift detectable at all -- Phase 2/3 had no way
      to tell "this running container is on an old image" from "this
      container is already on what the spec wants"
- [x] `Reconciler` partitions running containers into current-image and
      stale-image groups before applying anything else. Phase 3's
      health/backoff logic now runs only within the current-image
      group -- a stale container's health is irrelevant, it's being
      replaced regardless of whether its healthcheck currently passes
- [x] A stale replica is never retired until a replacement on the new
      image is **confirmed healthy** -- `STARTING` is deliberately not
      enough. This distinction (not the mere existence of a rewrite) is
      the actual zero-downtime guarantee
- [x] Retirement is paced at one stale container per tick, oldest-first,
      rather than replacing everything at once
- [x] A `null` image label (a container from before this label existed)
      is deliberately treated as *current*, not stale -- upgrading to
      this version doesn't trigger a surprise mass-replacement of
      whatever's already running. This turned out to matter in
      practice, not just in theory -- see **Bugs and lessons** below
- [x] The pure core's self-test suite grew from 22 to **36 tests, all
      passing**, including a full 4-tick simulated rolling-deploy
      sequence: start new → keep old while new is only `STARTING` →
      retire old once new is genuinely `HEALTHY` → fully converged
- [x] Verified live against a real Docker daemon for the mechanism that
      makes this phase possible at all (label-based image tracking); the
      full live `:v1`→`:v2` retirement sequence was captured mid-session
      and is being finalized -- see **Verified behavior (Phase 4)** for
      exactly what's confirmed so far versus still pending

**Phase 5 (next): a CLI** (`apply`, `status`, `scale`, `rollback`).
Not started yet. See **Roadmap**.

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

## Verified behavior (Phase 1)

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

## Bugs and lessons, found and fixed during Phase 1

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
pipelines, not just a local quirk. This exact distinction is what Phase
3's health checker will be built on.

## Known limitations (Phase 1)

- **In-memory state only, in both services.** No database, no
  persistence. A container restart resets `inventory-service`'s stock to
  its hardcoded seed values and clears every order `orders-service` has
  recorded.
- **No deep/cascading health checks**, by design — see the `/health`
  design note above.
- **No authentication, no TLS, no rate limiting.** Fine for a local
  orchestrator demo; would need real hardening for anything beyond that.

---

## Phase 2: a from-scratch orchestrator core

`orchestrator/` is a standalone Java CLI tool (not a Spring Boot app —
see **Why not Spring Boot for this module**) that continuously
reconciles a declarative spec file against real running Docker
containers, using the actual Docker Engine API.

### The core idea, and why it's split the way it is

Every real container orchestrator is built around the same mechanism:
compare **desired** state to **actual** state, and correct the
difference. The interesting, testable part of that idea has nothing to
do with Docker at all — it's a pure function: given a list of services
you want running and a list of containers that are actually running,
what has to start, and what has to stop?

`com.sujanuj.orchestrator.core.Reconciler.reconcile()` is exactly that
pure function — zero Docker imports, zero I/O, zero side effects. That's
deliberate: it's what makes the actual decision-making logic testable
with plain `javac`/`java`, no Docker daemon, no Maven Central, no
network access at all. `com.sujanuj.orchestrator.docker.DockerActuator`
is a separate, intentionally "dumb" translation layer that turns
`Reconciler`'s decisions into real `docker-java` API calls and does no
deciding of its own. If the orchestrator's *behavior* looks wrong, the
bug is almost certainly in `Reconciler`, which is fully tested; if a
Docker *operation* fails, the bug is almost certainly in
`DockerActuator`, which isn't.

### Why not Spring Boot for this module

`inventory-service` and `orders-service` are real HTTP services, so
Spring Boot's web stack earns its weight. The orchestrator isn't an
HTTP service — it's a CLI tool with a `main()` and a
`ScheduledExecutorService` running a loop. Pulling in the entire Spring
Framework for that would be a meaningfully heavier dependency footprint
than the job needs; a plain shaded JAR is the right-sized tool here.

### The spec format

```yaml
services:
  - name: inventory-service
    image: mini-orchestrator-inventory-service:latest
    replicas: 3
    containerPort: 8080
    healthPath: /health
    env: {}

  - name: orders-service
    image: mini-orchestrator-orders-service:latest
    replicas: 1
    containerPort: 8080
    hostPort: 8090
    healthPath: /health
    env:
      INVENTORY_SERVICE_URL: http://inventory-service:8080
```

Parsed with SnakeYAML (`SpecLoader`), validated field by field with
specific error messages (missing field, wrong type, duplicate service
name) rather than letting a raw `ClassCastException` from deep inside a
generic YAML library surface as the first thing an operator sees.

**The spec is re-read from disk on every single reconciliation tick,
deliberately.** This is what makes "edit `replicas: 3` and save" a live
scaling operation with zero orchestrator restart — verified live, see
below. The cost (re-parsing a small YAML file every 5 seconds) is
negligible; the alternative (load once at startup, or a filesystem
watcher) would either lose the live-editing demo or add real complexity
for a marginal efficiency gain that doesn't matter at this scale.

### Reconciliation logic

Three cases, all handled by one function:

- **Under-replicated** (fewer running containers than desired): start
  the deficit, one `StartContainer` action per missing replica.
- **Over-replicated** (more running than desired): stop the excess,
  **newest replicas first**. A container that was just started (e.g. by
  a scale-up decision immediately reversed on the next tick) has had the
  least chance to do useful work and is least likely to be mid-request
  under any future load-balancing — stopping the oldest, most-stable
  replicas first would do the opposite of what a "prefer stability,
  absorb churn on the newest" policy wants. A real, named design choice,
  not an arbitrary sort direction.
- **Orphaned** (a service that used to be in the spec, and no longer is
  at all — not just scaled to 0): every one of its containers is
  stopped. Without this explicit pass, deleting a service from the spec
  file would leave its containers running forever, since the
  under/over-replicated logic only ever looks at services that are
  still *in* the spec. "Not mentioned" and "desired at zero replicas"
  are different states, and only the orphan-cleanup pass handles the
  former.

### Verified behavior (Phase 2)

**The pure core, actually tested, not just carefully written:**

```
$ java com.sujanuj.orchestrator.core.ReconcilerSelfTest
PASS  scale up from zero: 3 StartContainer actions
PASS  scale up from 1 to 3: exactly 2 StartContainer actions
PASS  scale down from 3 to 1: stops newest+middle, keeps oldest
PASS  already converged (2 desired, 2 running): zero actions
PASS  two independent services converge simultaneously: 1 start (web) + 1 stop (worker)
PASS  orphaned service (removed from spec) has all containers stopped, converged service untouched
PASS  service explicitly desired at 0 replicas: stops all running containers
PASS  nothing desired, nothing running: zero actions, no crash

8 passed, 0 failed
```

Run with plain `javac`/`java` on `Reconciler.java`, `ServiceSpec.java`,
`ManagedContainer.java`, `ReconcileAction.java`, and
`ReconcilerSelfTest.java` in isolation — no Docker daemon, no Maven
Central, no network access required, since the core has zero external
dependencies. This is real verification, not an assumption dressed up
as one.

**The live scale-up, against a real Docker daemon, confirmed two
independent ways:**

Orchestrator's own log, after editing `replicas: 3` in the spec file
and saving it — no restart:

```
mini-orchestrator: watching spec.example.yaml, reconciling every 5s
[21:37:49] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[21:37:49] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[21:37:49] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[21:37:54] converged, no action needed
[21:37:59] converged, no action needed
[21:38:04] converged, no action needed
```

And independently, from the outside, not trusting the orchestrator's
own claims:

```bash
$ docker ps --filter "label=mini-orchestrator.service=inventory-service"
CONTAINER ID   IMAGE                                        STATUS                    NAMES
c595ef3c046f   mini-orchestrator-inventory-service:latest   Up 43 seconds (healthy)   mo-inventory-service-fd7d6408
fa3d04899cca   mini-orchestrator-inventory-service:latest   Up 43 seconds (healthy)   mo-inventory-service-08b08082
425baa4ea773   mini-orchestrator-inventory-service:latest   Up 43 seconds (healthy)   mo-inventory-service-45e72e85
```

Three real containers, all Docker-health-checked as healthy (not just
"process exists"), all correctly labeled — real evidence, not a log
message trusted at face value.

### Six real bugs, found and fixed getting this running

Getting this connected to a live Docker daemon took considerably longer
than writing the code did — worth narrating honestly, the same way
every other bug in this portfolio is, rather than only showing the part
that worked.

**1. Invalid XML inside a `pom.xml` comment.** Prose comments used `--`
as a plain dash (a Markdown habit), but XML comments have a hard rule:
`--` can never appear inside comment text, only as part of the closing
`-->`. Maven failed immediately with a parse error pointing at the exact
line. Fixed by replacing every mid-comment `--` with a colon or a period
across three separate comments in the file — and independently
re-verified with a real XML parser (not just a visual re-read) after
each fix, since the same mistake was made twice in a row while fixing
it the first time.

**2. A JDK version mismatch, and a real language-feature dependency.**
`mvn package` failed with `invalid target release: 21` — the host's
default JDK, installed via Homebrew, was 17. This wasn't just a version
number to relax: `Main.java` uses pattern matching for `switch`
(`case ReconcileAction.StartContainer start -> ...`), a language feature
only finalized as stable in Java 21. A second JDK (21) was installed
alongside the existing 17 via Homebrew, and `JAVA_HOME` was set
explicitly for the build rather than changing the system default,
specifically to avoid disturbing whatever else on the machine might
depend on JDK 17 remaining the default. A related, secondary version
issue: even with `JAVA_HOME` set correctly for Maven, the bare `java`
command on `PATH` still resolved to the old, `PATH`-linked JDK 17
(Homebrew installs a second JDK as "keg-only," not linked into `PATH` by
default) — invoking `$JAVA_HOME/bin/java` explicitly, bypassing `PATH`
entirely, was the actual fix for *running* the resulting jar, not just
building it.

**3. `DockerClientBuilder` no longer exists.** `Main.java` originally
used the classic one-line `DockerClientBuilder.getInstance().build()`.
Real compiler output against the real, currently-fetched
`docker-java-core:3.3.6` jar:

```
cannot find symbol: class DockerClientBuilder
location: package com.github.dockerjava.core
```

That class was deprecated in older docker-java releases and has since
been removed entirely. The current replacement is more explicit:
build a `DockerClientConfig`, build a `DockerHttpClient` implementation
on top of it (Apache HttpClient5, matching the
`docker-java-transport-httpclient5` dependency already declared), then
hand both to `DockerClientImpl.getInstance(config, httpClient)`.

**4. Signed dependency jars broke once shaded together.** The built jar
ran but immediately failed with:

```
java.lang.SecurityException: Invalid signature file digest for Manifest main attributes
```

Some transitive dependencies (the `bouncycastle` jars, pulled in by
docker-java's TLS support) are cryptographically signed. Once
`maven-shade-plugin` merges their classes into one uber-jar, those
signatures no longer match the new combined manifest — the JVM
correctly refuses to trust a signature that doesn't verify. The
signature files are meaningless once repackaged regardless, so the
standard, correct fix is to strip `META-INF/*.SF`, `*.DSA`, and `*.RSA`
from the shaded jar entirely via a shade-plugin filter, rather than try
to preserve something that can no longer be made valid.

**5. A real orphan-container leak on failed start, found by reading
the actual reconciliation log carefully.** `DockerActuator.startContainer()`
originally called `createContainerCmd().exec()` then immediately
`startContainerCmd().exec()`, with no error handling between them.
Docker doesn't validate host-port availability at *create* time, only
at *start* time — so a port conflict let `create()` succeed while
`start()` failed. Because `listManagedContainers()` only counts
*running* containers, that failed-to-start container was invisible to
the next reconciliation tick, which saw the same deficit and tried
again — creating **another** container that also failed for the
identical reason, once every 5 seconds, forever, each attempt leaking
one more permanently-orphaned container:

```
[21:31:30] starting new replica of 'inventory-service' ...
[21:31:30] reconciliation error, skipping this tick: ... port is already allocated
[21:31:35] starting new replica of 'inventory-service' ...
[21:31:35] reconciliation error, skipping this tick: ... port is already allocated
```

repeating indefinitely. Fixed by wrapping the start call in a
try/catch that removes the just-created container on any start
failure, turning "leak one orphan per failed retry, forever" into
"retry cleanly against a fresh slate every time" — correct either way
once the actual blocker is resolved, but only one of the two behaves
sanely while it's still present.

**6. The `hostPort`-vs-multiple-replicas constraint, actually
triggered live, plus a real editing mistake.** Two containers can't
both bind the same host port — a constraint the spec file's own
comments already warned about (see `spec.example.yaml`), but that had
only been reasoned about, not actually exercised, until scaling
`inventory-service` to 3 replicas while it still had `hostPort: 8081`
set. The result was exactly the same repeating-leak pattern as bug #5
before the fix, now caused by a real, unavoidable constraint rather
than a code bug — Docker correctly rejects every replica after the
first. Separately, and worth naming honestly: removing the `hostPort`
line via a GUI text editor's manual selection accidentally deleted the
*entire* `inventory-service` block, not just that one line — confirmed
by `grep` returning no match at all, and by the orchestrator correctly
(and confusingly, until diagnosed) treating the now-missing service as
**orphaned** and stopping its one running replica rather than scaling
it up. Fixed by rewriting the file cleanly via a terminal heredoc
instead of a GUI editor, which sidesteps this entire class of mistake.

### Known limitations (Phase 2)

- **No health-aware reconciliation yet.** A container counts as "running"
  the instant Docker reports it as such — Docker's own `HEALTHCHECK`
  status isn't consulted, and a container that's running but broken
  (e.g. crash-looping inside without exiting) isn't detected or
  replaced. That's Phase 3's entire job.
- **No load balancer or ingress for multi-replica services.** Three
  `inventory-service` replicas exist, but nothing routes traffic across
  them — reaching a specific one today means resolving the
  orchestrator's Docker network directly (e.g. from another
  orchestrator-managed container) or `docker exec`-ing in. Real,
  unbuilt future work, not something Phase 2 claims to solve.
- **`hostPort` + `replicas > 1` isn't validated at spec-load time.** The
  spec file's comments warn about this constraint, but `SpecLoader`
  doesn't reject it up front — an operator finds out the hard way, via
  a repeating Docker error, exactly as happened in bug #6 above. Worth
  fixing as real input validation, not currently done.
- **Single Docker host.** Both this orchestrator and everything it
  manages run against one local Docker daemon. True multi-node
  scheduling is real infrastructure complexity explicitly out of scope
  for this project's demo.
- **The orchestrator itself isn't containerized.** It runs as a JAR
  directly on the host, talking to the Docker socket. Running the
  orchestrator *inside* a container (with the Docker socket mounted in —
  "Docker outside of Docker") is a reasonable future step, not done here.
- **No authentication, no TLS, no rate limiting** on anything it manages
  or on the orchestrator's own operation.

---

## Phase 3: health-aware reconciliation and auto-restart

Phase 2's biggest named limitation was that "running" was the only
signal the orchestrator ever consulted: a container counted as healthy
the instant Docker reported it as `Up`, with no regard for whether the
application inside was actually working. That's exactly the gap
between "the process exists" and "the process is doing its job" —
a deadlocked JVM, a connection pool that's silently exhausted, an
infinite loop that never crashes the process, all look identical to a
genuinely working replica if all you check is "is it running." Both
services' Dockerfiles have declared a real `HEALTHCHECK` since Phase 1
specifically for this reason; Phase 2 never consulted it.

### Why UNHEALTHY is the only status that triggers a restart

Docker reports one of three health states for a container with a
`HEALTHCHECK` declared — `healthy`, `unhealthy`, or `starting` (still
within the Dockerfile's `start_period`, 15s for both services here) —
or no status at all if the image declares no `HEALTHCHECK`. Only
`unhealthy` ever causes a restart:

- **`starting`** counts toward desired replicas but is never restarted.
  Docker's own health state machine already handles "give it time to
  boot" via `start_period`; restarting something that hasn't even
  finished starting would fight against its own startup instead of
  waiting for it.
- **No `HEALTHCHECK` declared (`NONE`)** is treated the same as
  healthy — the orchestrator has no basis to distinguish "fine" from
  "broken" for such a container, so it falls back to Phase 2's original
  running-is-good behavior specifically for that container, rather than
  guessing.

### "Restart" isn't a new kind of action

An unhealthy container doesn't get a special "restart" operation.
`Reconciler` stops it, and because the very same tick's replica count is
computed only against containers whose health *isn't* `UNHEALTHY`, that
container no longer counts toward the desired total — so the ordinary
scale-up logic (unchanged since Phase 2) naturally starts a replacement,
the identical code path as any other scale-up. "Restart" is just "stop
the bad one, let the normal deficit logic replace it," not a third kind
of action requiring its own handling.

### Why backoff exists, and how it works

Without backoff, a persistently crash-looping image would get stopped
and replaced on *every single* 5-second tick, forever — hammering the
Docker daemon with rapid create/destroy cycles for something that's
never going to recover on its own. `RestartState` implements the same
idea Kubernetes' `CrashLoopBackOff` is built around: restart immediately
the first time, then wait exponentially longer between each subsequent
attempt for the *same* persistently-failing service, capped at 60
seconds so an operator still sees a retry reasonably often rather than
backoff effectively giving up. A tick where a service is fully healthy
again resets its backoff state to fresh — a *new*, unrelated future
failure shouldn't inherit an old incident's already-elevated delay.

One deliberate, slightly subtle behavior worth naming explicitly: while
a service's unhealthy container is still waiting out its backoff window
(not yet stopped), the deficit calculation already excludes it from the
healthy count — so a replacement gets started to maintain capacity
*before* the bad container is actually cleaned up. This can briefly
leave more containers running than `replicas` specifies (the good
replacement plus the not-yet-stopped bad one). That's intentional:
prefer momentarily having one extra unhealthy container over having a
capacity deficit while replacing it.

### `StopReason`: making the log say why, not just what

Before this phase, every stop looked identical in the log regardless of
cause. `StopReason` (`SCALE_DOWN`, `UNHEALTHY`, `ORPHANED`) is a small
addition purely for operator-facing clarity — "this replica failed its
health check" and "we simply have more replicas than desired" and "this
service was removed from the spec entirely" are three different facts
an operator watching the log needs to distinguish at a glance, and
before this phase all three printed the same way.

### Verified behavior (Phase 3)

**The pure core, actually tested — 22/22, up from 8:**

```
$ java com.sujanuj.orchestrator.core.ReconcilerSelfTest
PASS  scale up from zero: 3 StartContainer actions
PASS  scale up from 1 to 3: exactly 2 StartContainer actions
PASS  scale down from 3 to 1: stops newest+middle, keeps oldest
PASS  already converged (2 desired, 2 running): zero actions
PASS  two independent services converge simultaneously: 1 start (web) + 1 stop (worker)
PASS  orphaned service (removed from spec) has all containers stopped, converged service untouched
PASS  service explicitly desired at 0 replicas: stops all running containers
PASS  nothing desired, nothing running: zero actions, no crash
PASS  unhealthy container: stopped AND a replacement started
PASS  desired=2, one healthy + one unhealthy: stop the bad one, start exactly 1 replacement (not 2 -- the healthy one still counts)
PASS  a STARTING container counts toward desired replicas and is never restarted
PASS  a container with no HEALTHCHECK declared (NONE) is treated as fine, matching Phase 2's original running-is-good behavior
PASS  3 desired, 2 healthy + 1 unhealthy: only the unhealthy one is touched, healthy ones left alone
PASS  orphan cleanup stops containers regardless of health status
PASS  orphan cleanup also removes that service's stale restart-backoff state
PASS  tick 1: unhealthy container stopped and replacement started
PASS  tick 2 (1s later, within 5s backoff): the newly-unhealthy replacement is NOT stopped yet, but a replacement is still started to maintain capacity
PASS  once the backoff window has passed, the still-unhealthy container IS stopped and the failure count increments to 2
PASS  a fully-healthy tick resets consecutiveFailures back to 0
PASS  a FRESH RestartState allows a restart at any time
PASS  consecutive failures produce a growing (exponential) backoff delay
PASS  after many consecutive failures, the backoff delay is capped (never exceeds RestartState.MAX_DELAY_MILLIS)

22 passed, 0 failed
```

The 14 Phase 2 tests are unchanged and still pass via the original
2-argument `reconcile()` overload — deliberate regression proof that
becoming health-aware didn't alter Phase 2's original behavior for the
everything-is-healthy case. The 8 new tests specifically exercise the
trickiest part of this phase: backoff correctly preventing a repeat
restart within its window (tick 2 in the log above), allowing one again
once the window passes, and resetting cleanly after recovery — verified
with simulated timestamps, not a real clock or `Thread.sleep()`
anywhere in the test suite.

**The live run, against real, pre-existing, 3-day-old containers:**

```
mini-orchestrator: connecting to Docker daemon...
mini-orchestrator: watching spec.example.yaml, reconciling every 5s
[22:05:09] converged, no action needed
[22:05:14] converged, no action needed
[22:05:19] converged, no action needed
```

Confirmed independently against the same containers:

```bash
$ docker ps --filter "label=mini-orchestrator.managed=true"
CONTAINER ID   IMAGE                                        STATUS                CREATED
c595ef3c046f   mini-orchestrator-inventory-service:latest   Up 3 days (healthy)   3 days ago
fa3d04899cca   mini-orchestrator-inventory-service:latest   Up 3 days (healthy)   3 days ago
425baa4ea773   mini-orchestrator-inventory-service:latest   Up 3 days (healthy)   3 days ago
0b9281aadf48   mini-orchestrator-orders-service:latest      Up 3 days (healthy)   3 days ago
```

This "nothing happened" result is more informative than it looks: the
new `inspectContainerCmd`-based health-reading code had to correctly
classify all 4 real containers as healthy for the orchestrator to stay
quiet. If the health-status mapping were subtly wrong — say, misreading
Docker's `"healthy"` string as `UNHEALTHY` — the visible result would
have been a storm of unwanted stop/replace actions on the very first
tick, not silence. Quiet convergence against genuinely healthy
containers is a real (if undramatic) confirmation the mapping works.

### Known limitations (Phase 3)

- **An extra Docker API call per managed container, every tick.**
  `listContainersCmd()` alone doesn't expose structured health data —
  only `inspectContainerCmd(id)` does. `listManagedContainers()` now
  makes one list call plus one inspect call per managed container, every
  5 seconds. Trivially cheap for the handful of containers a local demo
  manages; would need batching or caching to stay cheap at real scale
  with hundreds of managed containers — a deliberate simplification for
  this project's scope, not an oversight.
- **No distinction between different causes of "unhealthy."** A
  container that's unhealthy because of a transient blip and one that's
  unhealthy because the image is fundamentally broken are treated
  identically — both get restarted on the same backoff schedule. A real
  system might want different handling (e.g. giving up entirely after N
  consecutive failures, rather than backing off indefinitely).
- **Restart counts aren't exposed anywhere outside the log.** They exist
  in `RestartState` and are used for backoff timing, but there's no
  `status` command or API to query "how many times has this service
  restarted" — only scrollback through the log. Phase 5's planned CLI is
  the natural place to surface this.
- **A replacement container isn't verified as actually healthy before
  the incident is considered resolved.** Once a bad container is
  stopped and a replacement started, the orchestrator moves on; if the
  replacement *also* comes up unhealthy, that's caught on a later tick
  (as an ordinary new failure, subject to backoff) rather than treated
  as a continuation of the same incident with any special handling.
- **The backoff policy (base delay, cap, growth rate) is a single global
  constant, not configurable per service.** A service known to be
  flaky and a service that should almost never fail currently get
  identical backoff behavior.

---

## Phase 4: rolling deploys

Phase 2 and 3 could keep the right *number* of replicas running and
replace a broken one, but neither had any concept of *which image* a
running container was actually on. Change `image:` in the spec from one
tag to another and save -- nothing would happen. `Reconciler` only ever
counted containers by service name; it had no way to tell "this running
container is on the old image" from "this container already matches
what the spec currently wants." That's the actual gap this phase closes.

### Making image drift detectable at all

Every container this orchestrator creates now carries a
`mini-orchestrator.image` label recording exactly which image it was
started from (`DockerActuator`, at creation time). `listManagedContainers()`
reads it back into `ManagedContainer.image()`, and `Reconciler` uses it
to partition every service's running containers into two groups before
doing anything else: **current-image** (matches what the spec wants
right now) and **stale-image** (doesn't). Phase 3's health/backoff logic
runs only within the current-image group from this point on -- a stale
container's health is irrelevant to whether it should keep running;
it's being replaced regardless of whether its healthcheck currently
passes.

### The actual zero-downtime guarantee: STARTING isn't enough

A stale replica is never retired until a same-service replacement on the
new image is **confirmed `HEALTHY`** -- not merely `STARTING`. This is
the single detail that makes the difference between a real rolling
deploy and just "restart everything and hope": retiring a still-working
old replica the instant a replacement merely begins starting (before its
own healthcheck has actually passed) could momentarily leave *less*
healthy capacity than before the deploy began, which defeats the entire
point. `STARTING` counting as "good enough" for ordinary scale-up
(Phase 3) and `STARTING` *not* counting as "ready to retire the old one
for" here are deliberately different bars for deliberately different
decisions, made explicit in `Reconciler` rather than left implicit.

### Pacing: one retirement per tick, oldest first

When multiple stale replicas exist, only one is retired per
reconciliation tick, oldest-started first. Order doesn't affect
correctness here -- every stale replica is leaving regardless -- but
pacing the retirements one at a time, rather than tearing down every
stale container the instant any single healthy replacement exists,
keeps the blast radius of each tick small and the rollout's progress
visibly incremental in the log, which matters more for an operator
watching a real deploy than it does for correctness on paper.

### Why a `null` image is "current," not "stale"

A container started by a pre-Phase-4 orchestrator simply never had this
label set. Defaulting an unlabeled container to "assume stale, replace
it" would force a surprise rolling replacement of *every* existing
container the very first time this version runs against them.
`Reconciler` treats a `null` image as matching whatever the spec
currently wants -- backward-compatible by not guessing, not by assuming
the more aggressive interpretation.

**This wasn't just a theoretical decision -- it was hit immediately while
testing this phase live**, and is worth narrating honestly as a real
lesson, not a hypothetical: the 3 `inventory-service` containers running
at the time this phase was tested were 3 days old, created by a
pre-Phase-4 build. Bumping the spec's image tag to `:v2` and saving
produced exactly the expected `converged, no action needed` -- correct
per the design, but not demoable, since those specific containers had no
image label to compare against at all. Getting an actual, observable
rolling deploy required first scaling the service to 0 and back up to 3
*on the existing image* to establish a properly-labeled baseline, and
only then changing the image tag. A real, useful thing to know about
upgrading a running orchestrator to a version that starts tracking new
metadata: existing state doesn't retroactively gain the new metadata
just because the code now expects it.

### Verified behavior (Phase 4)

**The pure core, actually tested -- 36/36, up from 22:**

```
$ java com.sujanuj.orchestrator.core.ReconcilerSelfTest
[... all Phase 2/3 tests unchanged and still passing ...]
PASS  spec image changed (v1 -> v2): a new v2 replica starts immediately, old v1 replica is NOT touched yet (no confirmed-healthy replacement exists)
PASS  a v2 replacement that is itself UNHEALTHY never causes the still-working old replica to be retired -- correctness over completing the rollout on schedule
PASS  a v2 replacement that is only STARTING (not yet confirmed healthy) does NOT cause the old replica to be retired -- this is the actual zero-downtime guarantee
PASS  once the v2 replacement is genuinely HEALTHY, the old v1 replica IS retired (reason: ROLLING_DEPLOY), and no further replicas are started (already at desired count)
PASS  3 old replicas still present, only ONE confirmed-healthy new replica so far: exactly one old replica is retired this tick, not all three at once
PASS  when multiple stale replicas exist, the OLDEST one is retired first
PASS  a container with no image label at all (null) is treated as CURRENT, not stale -- no surprise rolling replacement of pre-Phase-4 containers
PASS  an unhealthy STALE-image container doesn't trigger Phase 3's health/backoff path -- only a v2 replica is started; the old one is left for rolling retirement, not restart logic
PASS  over-provisioned on the NEW image while a stale straggler remains: ordinary scale-down is skipped (new replicas aren't prematurely removed), the stale one is retired via rolling deploy instead
PASS  full rolling-deploy sequence, tick 1: starts new v2 replica, keeps old v1 running
PASS  full rolling-deploy sequence, tick 2: new replica only STARTING -> old still kept, no actions
PASS  full rolling-deploy sequence, tick 3: new replica now HEALTHY -> old v1 retired
PASS  full rolling-deploy sequence, tick 4: fully converged on v2, zero actions
PASS  orphan cleanup stops containers regardless of image identity too

36 passed, 0 failed
```

The 4-tick simulated sequence is the important one: it exercises the
*entire* rolling-deploy lifecycle with simulated timestamps, no real
Docker daemon and no `Thread.sleep()` anywhere, and it's what gives real
confidence in the design beyond "it compiled."

**Live, against a real Docker daemon -- confirmed so far:**

```
[17:44:33] stopping 'inventory-service' replica c595ef3c046f (reason: SCALE_DOWN)
[17:44:33] stopping 'inventory-service' replica fa3d04899cca (reason: SCALE_DOWN)
[17:44:33] stopping 'inventory-service' replica 425baa4ea773 (reason: SCALE_DOWN)
...
[17:45:38] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[17:45:38] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[17:45:38] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[17:45:43] converged, no action needed
```

This confirms `StopReason` labeling is correct in practice (`SCALE_DOWN`,
not `ROLLING_DEPLOY`, for an ordinary replica-count change -- exactly
right, since no image change was involved) and that freshly-created
replicas are the properly-labeled baseline the actual image-swap test
needs. **The full live `:v1` → `:v2` retirement sequence (new replicas
starting, then old ones retiring one at a time with `reason:
ROLLING_DEPLOY` once each new one passes health) was in progress and not
yet captured at the time this section was written** -- called out here
explicitly rather than presented as confirmed when it wasn't. The pure
core's 36 tests already prove the *decision logic* is correct; this last
piece is end-to-end confirmation against the real Docker API path
specifically, the same standard every other phase in this README was
held to.

### Known limitations (Phase 4)

- **No `maxSurge` or `maxUnavailable` limit.** Every stale replica's
  replacement is started immediately, all at once, regardless of how
  many that is -- only the *retirement* side is paced (one per tick).
  For a service with many replicas, this means a real resource spike
  (2x the normal container count) during the early part of a rollout.
  A real `maxSurge` setting, capping how many new replicas start
  simultaneously, isn't implemented here.
- **A mutable tag (e.g. always deploying under `:latest`) can't be
  detected as a change.** Image comparison is a plain string check
  against the tag written in the spec. If the underlying image content
  changes but the tag string doesn't, `Reconciler` sees no difference
  and does nothing -- the same real gotcha Kubernetes' `imagePullPolicy`
  exists to work around, not solved here.
- **No rollback.** If a `:v2` replacement never becomes healthy, the
  rollout simply stalls indefinitely (old replicas keep running, since
  they're never retired without a confirmed-healthy replacement) rather
  than automatically reverting to the previous image. Stalling safely is
  the correct behavior for *not making things worse*, but an operator
  currently has to notice and intervene manually by editing the spec
  back -- there's no automatic "this isn't working, revert" logic.
- **A replacement that becomes healthy and then goes unhealthy shortly
  after being retired for isn't specially detected as "this rollout
  regressed."** It's caught by ordinary Phase 3 restart/backoff on a
  later tick, the same as any other health failure, with no
  rollout-specific handling.

---

## Anatomy of one reconciliation tick

Each phase above explains its own piece of `Reconciler.reconcile()` in
isolation. Here's what actually happens, in order, when all four phases'
logic runs together against one real service on a real tick — the
mental model that doesn't exist anywhere else in this README as a single
picture:

```
Main.reconcileOnce(), every 5 seconds:

  1. Re-read spec.yaml from disk
     -> if this fails (bad YAML), skip this tick entirely, try again in 5s

  2. DockerActuator.listManagedContainers()
     -> one listContainersCmd() call (cheap, gets IDs + labels + creation time)
     -> one inspectContainerCmd() call PER managed container (Phase 3 --
        this is where real Docker HEALTHCHECK status comes from)
     -> returns Map<serviceName, List<ManagedContainer>>, each container
        now carrying: id, startedAt, health status, AND the image label
        it was actually created with (Phase 4)

  3. Reconciler.reconcile(desired, actual, restartState, now) -- for
     EACH service in the spec, in this exact order:

     a. Partition running containers: current-image vs stale-image
        (Phase 4) -- a null image label counts as current, not stale

     b. Within current-image ONLY: split into unhealthy vs healthy-enough
        (Phase 3) -- STARTING and NONE both count as "healthy enough"

     c. If any unhealthy current-image containers exist AND backoff
        allows it (RestartState.canRestartNow) -- stop them
        (reason: UNHEALTHY), advance backoff state

     d. Compute deficit = desired replicas - healthy-enough CURRENT-image
        count
        -> deficit > 0: start that many new replicas (this is also how
           Phase 4 rollouts begin -- a stale-image container never
           counts as current, so a fresh image change immediately shows
           up as a deficit)
        -> deficit < 0 AND no stale-image containers exist: ordinary
           scale-down, newest-first (reason: SCALE_DOWN)
        -> deficit < 0 AND stale-image containers DO exist: do nothing
           here -- over-provisioning mid-rollout is expected, not an
           error to correct

     e. If any stale-image containers exist AND at least one
        current-image container is CONFIRMED healthy (not just
        STARTING) -- retire exactly ONE stale container this tick,
        oldest-first (reason: ROLLING_DEPLOY)

     f. (repeat a-e for every other service in the spec, independently)

  4. Orphan cleanup: any service with running containers that's no
     longer in the spec at all gets ALL its containers stopped
     (reason: ORPHANED), regardless of health or image, and its
     restart-backoff state is discarded

  5. DockerActuator.apply() executes each decided action for real
     -> a failed StartContainer cleans up its own half-created
        container rather than leaking it (a real bug found and fixed
        getting Phase 2 working -- see that phase's bug list)

  6. Log every action taken, or "converged, no action needed" if none
```

Steps 3a-3e run independently per service, so a spec with 5 services
mid-rollout on one of them, scaling up on another, and fully converged
on the rest all get evaluated correctly in the same single tick —
nothing in this design assumes only one thing is happening at a time.

## Kubernetes concept mapping

For anyone already familiar with Kubernetes, here's roughly what maps to
what — useful both as a mental shortcut and as an honest scope check on
how much smaller this project deliberately is.

| Kubernetes concept | This project | Notes |
|---|---|---|
| `Deployment` / `ReplicaSet` | `ServiceSpec` + `Reconciler`'s replica-count logic | One flat spec, no separate ReplicaSet layer underneath a Deployment |
| Reconciliation / control loop | `Main`'s scheduled tick + `Reconciler.reconcile()` | The exact same core idea; this project makes the loop itself the entire subject, not an implementation detail |
| `livenessProbe` | Docker `HEALTHCHECK` + `DockerActuator.inspectHealth()` | Delegated to Docker's own health mechanism rather than reimplemented |
| `readinessProbe` | *(not implemented)* | No separate "ready for traffic" signal distinct from "healthy" — a real gap, not solved here |
| `CrashLoopBackOff` | `RestartState`, exponential backoff | Same idea: restart immediately, then back off, capped |
| `RollingUpdate` strategy | Phase 4's image-drift detection + paced retirement | No `maxSurge`/`maxUnavailable` equivalent -- see Phase 4's Known limitations |
| `imagePullPolicy` gotchas (mutable tags) | Same real gotcha, explicitly named | Image comparison is a plain string check; a mutable `:latest` re-pushed under the same tag isn't detected as a change, same as Kubernetes |
| `kubectl apply` / `kubectl get` / `kubectl rollout` | *(planned, Phase 5)* | Currently: edit the YAML file directly and watch the log |
| `Service` (load balancing / DNS) | Docker's own container-network DNS (`mini-orchestrator-net`) | No virtual IP, no load balancing across replicas -- reaching a specific one of several requires knowing its container name |
| Multi-node scheduling | *(explicitly out of scope)* | Single Docker host only, named as a deliberate scope cut from the start |
| etcd / the API server | *(none)* | `spec.yaml` on disk is the entire source of truth, re-read every tick -- no separate datastore, no API layer in front of it |

The honest takeaway: this project reimplements the *reconciliation loop*
faithfully — the actual mechanism Kubernetes controllers are built
around — while deliberately not reimplementing the enormous surface area
around it (networking, scheduling across nodes, an API server, RBAC,
etc.). That's the right scope for a project meant to demonstrate
understanding the core mechanism, not to compete with Kubernetes.

## Why build this instead of just running Kubernetes

A fair question, worth answering directly rather than leaving implicit.

**This project was never meant to replace Kubernetes for real workloads.**
For anything that actually needs to run in production, Kubernetes (or a
managed equivalent) is almost always the right choice — it's had
thousands of engineer-years spent on exactly the problems this project
explicitly scopes out (multi-node scheduling, networking, RBAC, storage
orchestration, admission control, and far more).

The actual goal was narrower and different: build the *mechanism*
Kubernetes controllers are built around — a reconciliation loop
comparing desired to actual state — from scratch, well enough to
genuinely understand what's happening underneath `kubectl apply`, rather
than only ever operating that mechanism through someone else's
abstraction. There's a real difference between "I can use Kubernetes"
and "I understand why a ReplicaSet controller behaves the way it does
when a pod's liveness probe starts failing, because I've built and
debugged the equivalent logic myself" — the second is what this project
is actually for.

A secondary, practical reason: building this locally in Java, against
the real Docker Engine API, with no Kubernetes cluster required, made
the whole thing runnable and demoable on a single laptop with `docker
compose` and a JAR file — no cloud account, no cluster setup, nothing
to tear down afterward.

## What I'd change at scale

Matching this portfolio's other projects' honesty about scope: this is
what would actually need to change for this to handle real production
load, not just a local demo.

- **Batch the health-check API calls.** `listManagedContainers()` makes
  one `inspectContainerCmd()` call per managed container, every tick.
  Fine for a handful of containers; would need batching (or accepting
  slightly staler health data, checked less often) to stay cheap with
  hundreds of managed containers.
- **A real event stream instead of polling.** Every tick re-lists and
  re-inspects every container from scratch, on a fixed 5-second timer,
  whether or not anything changed. Docker's own event API
  (`docker events` / `dockerClient.eventsCmd()`) would let the
  orchestrator react to actual state changes instead of re-deriving the
  whole world from scratch every 5 seconds — lower latency to react,
  lower steady-state API load.
- **Multi-node scheduling.** Named as out of scope from the start, and
  still the single biggest gap versus a real orchestrator. The core
  `Reconciler` logic doesn't actually assume a single host — it just
  operates on `ManagedContainer` data that happens to all come from one
  `DockerActuator` today. A `NodeAgent` abstraction reporting
  per-node capacity and running containers, with a scheduler deciding
  *which* node a new replica lands on, is the natural extension.
- **`maxSurge`/`maxUnavailable` for rolling deploys**, instead of
  starting every replacement replica simultaneously (see Phase 4's
  Known limitations) — a real resource-usage concern at any meaningful
  replica count.
- **Persistent state for restart counts and rollout history** instead of
  only living in `RestartState` (in-memory, lost on orchestrator
  restart) and the scrollback of a log file. A real system would want
  this queryable after the fact, not just visible while watching a
  terminal live.
- **Structured, queryable status** instead of a text log an operator has
  to read. Phase 5's planned CLI (`status`, in particular) is the
  natural place this belongs.

---

## Roadmap

**Phase 5 — a CLI** (`apply`, `status`, `scale`, `rollback`) against this
project's own spec format. Restart counts and rolling-deploy progress
(Phases 3-4) currently only exist in the reconciliation log's scrollback
-- a real `status` command is the natural place to surface them instead.

**Explicitly out of scope, named now rather than discovered later:**
true multi-node scheduling across multiple Docker hosts. The scheduler
will be built with a pluggable "node" interface so the architecture is
real, but the actual demo targets a single Docker host.

## Running it

**Phase 1 — the microservices:**

```bash
git clone https://github.com/sujanuj/mini-orchestrator.git
cd mini-orchestrator
docker compose up --build
```

```bash
curl http://localhost:8081/health
curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"item":"widget","quantity":5}'
```

**Phase 2 — the orchestrator** (requires a JDK 21 to build and run,
even if your default system JDK is older — see bug #2 above):

```bash
cd orchestrator
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; adjust for your platform
mvn -B -DskipTests package
$JAVA_HOME/bin/java -jar target/orchestrator.jar spec.example.yaml
```

Then, in another terminal, confirm what's running:

```bash
docker ps --filter "label=mini-orchestrator.managed=true"
```

Edit `replicas:` in `spec.example.yaml` and save — no restart needed,
the next reconciliation tick (within 5s) picks it up.

To run the pure core's self-tests directly, with no Docker daemon and
no Maven Central needed:

```bash
cd orchestrator/src/main/java
javac com/sujanuj/orchestrator/core/*.java
java com.sujanuj.orchestrator.core.ReconcilerSelfTest
```

## Project layout

```
mini-orchestrator/
├── docker-compose.yml
├── services/
│   ├── inventory-service/
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/main/
│   │       ├── java/com/sujanuj/inventory/
│   │       │   ├── InventoryApplication.java
│   │       │   └── InventoryController.java
│   │       └── resources/application.properties
│   └── orders-service/
│       ├── pom.xml
│       ├── Dockerfile
│       └── src/main/
│           ├── java/com/sujanuj/orders/
│           │   ├── OrdersApplication.java
│           │   ├── OrdersController.java
│           │   └── RestTemplateConfig.java
│           └── resources/application.properties
└── orchestrator/
    ├── pom.xml
    ├── spec.example.yaml
    └── src/main/java/com/sujanuj/orchestrator/
        ├── Main.java
        ├── core/              <- pure, dependency-free reconciliation logic
        │   ├── ServiceSpec.java
        │   ├── ManagedContainer.java
        │   ├── HealthStatus.java      <- Phase 3
        │   ├── ReconcileAction.java
        │   ├── StopReason.java         <- Phase 3
        │   ├── ReconcileResult.java     <- Phase 3
        │   ├── RestartState.java         <- Phase 3
        │   ├── Reconciler.java
        │   └── ReconcilerSelfTest.java
        ├── docker/             <- docker-java integration (Docker Engine API)
        │   └── DockerActuator.java
        └── spec/               <- YAML spec parsing
            └── SpecLoader.java
```- [x] Six real bugs, found and fixed by actually running this against
      real tooling — kept in below in full, not summarized away

**Phase 3: Health-aware reconciliation and auto-restart — done**

- [x] Real Docker `HEALTHCHECK` status (not just "is it running") consulted
      per container every tick via `inspectContainerCmd` -- a container
      that's alive as a process but failing its healthcheck is now
      detected and replaced, closing Phase 2's biggest named limitation
- [x] Only genuinely `UNHEALTHY` containers ever get restarted;
      `STARTING` (still within the Dockerfile's grace period) and `NONE`
      (no healthcheck declared on the image) both count toward desired
      replicas and are never touched based on health
- [x] Real exponential backoff (`RestartState`) so a persistently
      crash-looping service gets restarted immediately the first time,
      then with growing delay, capped, rather than thrashed every 5s
      forever
- [x] "Restart" isn't a new, separate kind of action -- an unhealthy
      container is stopped, and the ordinary scale-up path (which now
      excludes it from the healthy count) naturally starts its
      replacement, the same code path as any other scale-up
- [x] `StopReason` (`SCALE_DOWN` / `UNHEALTHY` / `ORPHANED`) added so the
      reconciliation log says *why* a container was stopped, not just
      that it was
- [x] The pure core's self-test suite grew from 8 to **22 tests, all
      passing**, including the tricky part: backoff actually preventing
      a repeat restart within its window, allowing one again once the
      window passes, and resetting cleanly after recovery
- [x] Verified live against 4 real, 3-day-old running containers: the
      health-inspection code correctly read all of them as healthy and
      changed nothing -- a meaningful negative result, since a subtly
      wrong health mapping would have shown up as a storm of unwanted
      restarts instead of quiet convergence

**Phase 4: Rolling deploys — done**

- [x] Every container now tagged with the exact image it was started
      from (`mini-orchestrator.image` label, set at creation), which is
      what makes image drift detectable at all -- Phase 2/3 had no way
      to tell "this running container is on an old image" from "this
      container is already on what the spec wants"
- [x] `Reconciler` partitions running containers into current-image and
      stale-image groups before applying anything else. Phase 3's
      health/backoff logic now runs only within the current-image
      group -- a stale container's health is irrelevant, it's being
      replaced regardless of whether its healthcheck currently passes
- [x] A stale replica is never retired until a replacement on the new
      image is **confirmed healthy** -- `STARTING` is deliberately not
      enough. This distinction (not the mere existence of a rewrite) is
      the actual zero-downtime guarantee
- [x] Retirement is paced at one stale container per tick, oldest-first,
      rather than replacing everything at once
- [x] A `null` image label (a container from before this label existed)
      is deliberately treated as *current*, not stale -- upgrading to
      this version doesn't trigger a surprise mass-replacement of
      whatever's already running. This turned out to matter in
      practice, not just in theory -- see **Bugs and lessons** below
- [x] The pure core's self-test suite grew from 22 to **36 tests, all
      passing**, including a full 4-tick simulated rolling-deploy
      sequence: start new → keep old while new is only `STARTING` →
      retire old once new is genuinely `HEALTHY` → fully converged
- [x] Verified live against a real Docker daemon for the mechanism that
      makes this phase possible at all (label-based image tracking); the
      full live `:v1`→`:v2` retirement sequence was captured mid-session
      and is being finalized -- see **Verified behavior (Phase 4)** for
      exactly what's confirmed so far versus still pending

**Phase 5 (next): a CLI** (`apply`, `status`, `scale`, `rollback`).
Not started yet. See **Roadmap**.

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

## Verified behavior (Phase 1)

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

## Bugs and lessons, found and fixed during Phase 1

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
pipelines, not just a local quirk. This exact distinction is what Phase
3's health checker will be built on.

## Known limitations (Phase 1)

- **In-memory state only, in both services.** No database, no
  persistence. A container restart resets `inventory-service`'s stock to
  its hardcoded seed values and clears every order `orders-service` has
  recorded.
- **No deep/cascading health checks**, by design — see the `/health`
  design note above.
- **No authentication, no TLS, no rate limiting.** Fine for a local
  orchestrator demo; would need real hardening for anything beyond that.

---

## Phase 2: a from-scratch orchestrator core

`orchestrator/` is a standalone Java CLI tool (not a Spring Boot app —
see **Why not Spring Boot for this module**) that continuously
reconciles a declarative spec file against real running Docker
containers, using the actual Docker Engine API.

### The core idea, and why it's split the way it is

Every real container orchestrator is built around the same mechanism:
compare **desired** state to **actual** state, and correct the
difference. The interesting, testable part of that idea has nothing to
do with Docker at all — it's a pure function: given a list of services
you want running and a list of containers that are actually running,
what has to start, and what has to stop?

`com.sujanuj.orchestrator.core.Reconciler.reconcile()` is exactly that
pure function — zero Docker imports, zero I/O, zero side effects. That's
deliberate: it's what makes the actual decision-making logic testable
with plain `javac`/`java`, no Docker daemon, no Maven Central, no
network access at all. `com.sujanuj.orchestrator.docker.DockerActuator`
is a separate, intentionally "dumb" translation layer that turns
`Reconciler`'s decisions into real `docker-java` API calls and does no
deciding of its own. If the orchestrator's *behavior* looks wrong, the
bug is almost certainly in `Reconciler`, which is fully tested; if a
Docker *operation* fails, the bug is almost certainly in
`DockerActuator`, which isn't.

### Why not Spring Boot for this module

`inventory-service` and `orders-service` are real HTTP services, so
Spring Boot's web stack earns its weight. The orchestrator isn't an
HTTP service — it's a CLI tool with a `main()` and a
`ScheduledExecutorService` running a loop. Pulling in the entire Spring
Framework for that would be a meaningfully heavier dependency footprint
than the job needs; a plain shaded JAR is the right-sized tool here.

### The spec format

```yaml
services:
  - name: inventory-service
    image: mini-orchestrator-inventory-service:latest
    replicas: 3
    containerPort: 8080
    healthPath: /health
    env: {}

  - name: orders-service
    image: mini-orchestrator-orders-service:latest
    replicas: 1
    containerPort: 8080
    hostPort: 8090
    healthPath: /health
    env:
      INVENTORY_SERVICE_URL: http://inventory-service:8080
```

Parsed with SnakeYAML (`SpecLoader`), validated field by field with
specific error messages (missing field, wrong type, duplicate service
name) rather than letting a raw `ClassCastException` from deep inside a
generic YAML library surface as the first thing an operator sees.

**The spec is re-read from disk on every single reconciliation tick,
deliberately.** This is what makes "edit `replicas: 3` and save" a live
scaling operation with zero orchestrator restart — verified live, see
below. The cost (re-parsing a small YAML file every 5 seconds) is
negligible; the alternative (load once at startup, or a filesystem
watcher) would either lose the live-editing demo or add real complexity
for a marginal efficiency gain that doesn't matter at this scale.

### Reconciliation logic

Three cases, all handled by one function:

- **Under-replicated** (fewer running containers than desired): start
  the deficit, one `StartContainer` action per missing replica.
- **Over-replicated** (more running than desired): stop the excess,
  **newest replicas first**. A container that was just started (e.g. by
  a scale-up decision immediately reversed on the next tick) has had the
  least chance to do useful work and is least likely to be mid-request
  under any future load-balancing — stopping the oldest, most-stable
  replicas first would do the opposite of what a "prefer stability,
  absorb churn on the newest" policy wants. A real, named design choice,
  not an arbitrary sort direction.
- **Orphaned** (a service that used to be in the spec, and no longer is
  at all — not just scaled to 0): every one of its containers is
  stopped. Without this explicit pass, deleting a service from the spec
  file would leave its containers running forever, since the
  under/over-replicated logic only ever looks at services that are
  still *in* the spec. "Not mentioned" and "desired at zero replicas"
  are different states, and only the orphan-cleanup pass handles the
  former.

### Verified behavior (Phase 2)

**The pure core, actually tested, not just carefully written:**

```
$ java com.sujanuj.orchestrator.core.ReconcilerSelfTest
PASS  scale up from zero: 3 StartContainer actions
PASS  scale up from 1 to 3: exactly 2 StartContainer actions
PASS  scale down from 3 to 1: stops newest+middle, keeps oldest
PASS  already converged (2 desired, 2 running): zero actions
PASS  two independent services converge simultaneously: 1 start (web) + 1 stop (worker)
PASS  orphaned service (removed from spec) has all containers stopped, converged service untouched
PASS  service explicitly desired at 0 replicas: stops all running containers
PASS  nothing desired, nothing running: zero actions, no crash

8 passed, 0 failed
```

Run with plain `javac`/`java` on `Reconciler.java`, `ServiceSpec.java`,
`ManagedContainer.java`, `ReconcileAction.java`, and
`ReconcilerSelfTest.java` in isolation — no Docker daemon, no Maven
Central, no network access required, since the core has zero external
dependencies. This is real verification, not an assumption dressed up
as one.

**The live scale-up, against a real Docker daemon, confirmed two
independent ways:**

Orchestrator's own log, after editing `replicas: 3` in the spec file
and saving it — no restart:

```
mini-orchestrator: watching spec.example.yaml, reconciling every 5s
[21:37:49] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[21:37:49] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[21:37:49] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[21:37:54] converged, no action needed
[21:37:59] converged, no action needed
[21:38:04] converged, no action needed
```

And independently, from the outside, not trusting the orchestrator's
own claims:

```bash
$ docker ps --filter "label=mini-orchestrator.service=inventory-service"
CONTAINER ID   IMAGE                                        STATUS                    NAMES
c595ef3c046f   mini-orchestrator-inventory-service:latest   Up 43 seconds (healthy)   mo-inventory-service-fd7d6408
fa3d04899cca   mini-orchestrator-inventory-service:latest   Up 43 seconds (healthy)   mo-inventory-service-08b08082
425baa4ea773   mini-orchestrator-inventory-service:latest   Up 43 seconds (healthy)   mo-inventory-service-45e72e85
```

Three real containers, all Docker-health-checked as healthy (not just
"process exists"), all correctly labeled — real evidence, not a log
message trusted at face value.

### Six real bugs, found and fixed getting this running

Getting this connected to a live Docker daemon took considerably longer
than writing the code did — worth narrating honestly, the same way
every other bug in this portfolio is, rather than only showing the part
that worked.

**1. Invalid XML inside a `pom.xml` comment.** Prose comments used `--`
as a plain dash (a Markdown habit), but XML comments have a hard rule:
`--` can never appear inside comment text, only as part of the closing
`-->`. Maven failed immediately with a parse error pointing at the exact
line. Fixed by replacing every mid-comment `--` with a colon or a period
across three separate comments in the file — and independently
re-verified with a real XML parser (not just a visual re-read) after
each fix, since the same mistake was made twice in a row while fixing
it the first time.

**2. A JDK version mismatch, and a real language-feature dependency.**
`mvn package` failed with `invalid target release: 21` — the host's
default JDK, installed via Homebrew, was 17. This wasn't just a version
number to relax: `Main.java` uses pattern matching for `switch`
(`case ReconcileAction.StartContainer start -> ...`), a language feature
only finalized as stable in Java 21. A second JDK (21) was installed
alongside the existing 17 via Homebrew, and `JAVA_HOME` was set
explicitly for the build rather than changing the system default,
specifically to avoid disturbing whatever else on the machine might
depend on JDK 17 remaining the default. A related, secondary version
issue: even with `JAVA_HOME` set correctly for Maven, the bare `java`
command on `PATH` still resolved to the old, `PATH`-linked JDK 17
(Homebrew installs a second JDK as "keg-only," not linked into `PATH` by
default) — invoking `$JAVA_HOME/bin/java` explicitly, bypassing `PATH`
entirely, was the actual fix for *running* the resulting jar, not just
building it.

**3. `DockerClientBuilder` no longer exists.** `Main.java` originally
used the classic one-line `DockerClientBuilder.getInstance().build()`.
Real compiler output against the real, currently-fetched
`docker-java-core:3.3.6` jar:

```
cannot find symbol: class DockerClientBuilder
location: package com.github.dockerjava.core
```

That class was deprecated in older docker-java releases and has since
been removed entirely. The current replacement is more explicit:
build a `DockerClientConfig`, build a `DockerHttpClient` implementation
on top of it (Apache HttpClient5, matching the
`docker-java-transport-httpclient5` dependency already declared), then
hand both to `DockerClientImpl.getInstance(config, httpClient)`.

**4. Signed dependency jars broke once shaded together.** The built jar
ran but immediately failed with:

```
java.lang.SecurityException: Invalid signature file digest for Manifest main attributes
```

Some transitive dependencies (the `bouncycastle` jars, pulled in by
docker-java's TLS support) are cryptographically signed. Once
`maven-shade-plugin` merges their classes into one uber-jar, those
signatures no longer match the new combined manifest — the JVM
correctly refuses to trust a signature that doesn't verify. The
signature files are meaningless once repackaged regardless, so the
standard, correct fix is to strip `META-INF/*.SF`, `*.DSA`, and `*.RSA`
from the shaded jar entirely via a shade-plugin filter, rather than try
to preserve something that can no longer be made valid.

**5. A real orphan-container leak on failed start, found by reading
the actual reconciliation log carefully.** `DockerActuator.startContainer()`
originally called `createContainerCmd().exec()` then immediately
`startContainerCmd().exec()`, with no error handling between them.
Docker doesn't validate host-port availability at *create* time, only
at *start* time — so a port conflict let `create()` succeed while
`start()` failed. Because `listManagedContainers()` only counts
*running* containers, that failed-to-start container was invisible to
the next reconciliation tick, which saw the same deficit and tried
again — creating **another** container that also failed for the
identical reason, once every 5 seconds, forever, each attempt leaking
one more permanently-orphaned container:

```
[21:31:30] starting new replica of 'inventory-service' ...
[21:31:30] reconciliation error, skipping this tick: ... port is already allocated
[21:31:35] starting new replica of 'inventory-service' ...
[21:31:35] reconciliation error, skipping this tick: ... port is already allocated
```

repeating indefinitely. Fixed by wrapping the start call in a
try/catch that removes the just-created container on any start
failure, turning "leak one orphan per failed retry, forever" into
"retry cleanly against a fresh slate every time" — correct either way
once the actual blocker is resolved, but only one of the two behaves
sanely while it's still present.

**6. The `hostPort`-vs-multiple-replicas constraint, actually
triggered live, plus a real editing mistake.** Two containers can't
both bind the same host port — a constraint the spec file's own
comments already warned about (see `spec.example.yaml`), but that had
only been reasoned about, not actually exercised, until scaling
`inventory-service` to 3 replicas while it still had `hostPort: 8081`
set. The result was exactly the same repeating-leak pattern as bug #5
before the fix, now caused by a real, unavoidable constraint rather
than a code bug — Docker correctly rejects every replica after the
first. Separately, and worth naming honestly: removing the `hostPort`
line via a GUI text editor's manual selection accidentally deleted the
*entire* `inventory-service` block, not just that one line — confirmed
by `grep` returning no match at all, and by the orchestrator correctly
(and confusingly, until diagnosed) treating the now-missing service as
**orphaned** and stopping its one running replica rather than scaling
it up. Fixed by rewriting the file cleanly via a terminal heredoc
instead of a GUI editor, which sidesteps this entire class of mistake.

### Known limitations (Phase 2)

- **No health-aware reconciliation yet.** A container counts as "running"
  the instant Docker reports it as such — Docker's own `HEALTHCHECK`
  status isn't consulted, and a container that's running but broken
  (e.g. crash-looping inside without exiting) isn't detected or
  replaced. That's Phase 3's entire job.
- **No load balancer or ingress for multi-replica services.** Three
  `inventory-service` replicas exist, but nothing routes traffic across
  them — reaching a specific one today means resolving the
  orchestrator's Docker network directly (e.g. from another
  orchestrator-managed container) or `docker exec`-ing in. Real,
  unbuilt future work, not something Phase 2 claims to solve.
- **`hostPort` + `replicas > 1` isn't validated at spec-load time.** The
  spec file's comments warn about this constraint, but `SpecLoader`
  doesn't reject it up front — an operator finds out the hard way, via
  a repeating Docker error, exactly as happened in bug #6 above. Worth
  fixing as real input validation, not currently done.
- **Single Docker host.** Both this orchestrator and everything it
  manages run against one local Docker daemon. True multi-node
  scheduling is real infrastructure complexity explicitly out of scope
  for this project's demo.
- **The orchestrator itself isn't containerized.** It runs as a JAR
  directly on the host, talking to the Docker socket. Running the
  orchestrator *inside* a container (with the Docker socket mounted in —
  "Docker outside of Docker") is a reasonable future step, not done here.
- **No authentication, no TLS, no rate limiting** on anything it manages
  or on the orchestrator's own operation.

---

## Phase 3: health-aware reconciliation and auto-restart

Phase 2's biggest named limitation was that "running" was the only
signal the orchestrator ever consulted: a container counted as healthy
the instant Docker reported it as `Up`, with no regard for whether the
application inside was actually working. That's exactly the gap
between "the process exists" and "the process is doing its job" —
a deadlocked JVM, a connection pool that's silently exhausted, an
infinite loop that never crashes the process, all look identical to a
genuinely working replica if all you check is "is it running." Both
services' Dockerfiles have declared a real `HEALTHCHECK` since Phase 1
specifically for this reason; Phase 2 never consulted it.

### Why UNHEALTHY is the only status that triggers a restart

Docker reports one of three health states for a container with a
`HEALTHCHECK` declared — `healthy`, `unhealthy`, or `starting` (still
within the Dockerfile's `start_period`, 15s for both services here) —
or no status at all if the image declares no `HEALTHCHECK`. Only
`unhealthy` ever causes a restart:

- **`starting`** counts toward desired replicas but is never restarted.
  Docker's own health state machine already handles "give it time to
  boot" via `start_period`; restarting something that hasn't even
  finished starting would fight against its own startup instead of
  waiting for it.
- **No `HEALTHCHECK` declared (`NONE`)** is treated the same as
  healthy — the orchestrator has no basis to distinguish "fine" from
  "broken" for such a container, so it falls back to Phase 2's original
  running-is-good behavior specifically for that container, rather than
  guessing.

### "Restart" isn't a new kind of action

An unhealthy container doesn't get a special "restart" operation.
`Reconciler` stops it, and because the very same tick's replica count is
computed only against containers whose health *isn't* `UNHEALTHY`, that
container no longer counts toward the desired total — so the ordinary
scale-up logic (unchanged since Phase 2) naturally starts a replacement,
the identical code path as any other scale-up. "Restart" is just "stop
the bad one, let the normal deficit logic replace it," not a third kind
of action requiring its own handling.

### Why backoff exists, and how it works

Without backoff, a persistently crash-looping image would get stopped
and replaced on *every single* 5-second tick, forever — hammering the
Docker daemon with rapid create/destroy cycles for something that's
never going to recover on its own. `RestartState` implements the same
idea Kubernetes' `CrashLoopBackOff` is built around: restart immediately
the first time, then wait exponentially longer between each subsequent
attempt for the *same* persistently-failing service, capped at 60
seconds so an operator still sees a retry reasonably often rather than
backoff effectively giving up. A tick where a service is fully healthy
again resets its backoff state to fresh — a *new*, unrelated future
failure shouldn't inherit an old incident's already-elevated delay.

One deliberate, slightly subtle behavior worth naming explicitly: while
a service's unhealthy container is still waiting out its backoff window
(not yet stopped), the deficit calculation already excludes it from the
healthy count — so a replacement gets started to maintain capacity
*before* the bad container is actually cleaned up. This can briefly
leave more containers running than `replicas` specifies (the good
replacement plus the not-yet-stopped bad one). That's intentional:
prefer momentarily having one extra unhealthy container over having a
capacity deficit while replacing it.

### `StopReason`: making the log say why, not just what

Before this phase, every stop looked identical in the log regardless of
cause. `StopReason` (`SCALE_DOWN`, `UNHEALTHY`, `ORPHANED`) is a small
addition purely for operator-facing clarity — "this replica failed its
health check" and "we simply have more replicas than desired" and "this
service was removed from the spec entirely" are three different facts
an operator watching the log needs to distinguish at a glance, and
before this phase all three printed the same way.

### Verified behavior (Phase 3)

**The pure core, actually tested — 22/22, up from 8:**

```
$ java com.sujanuj.orchestrator.core.ReconcilerSelfTest
PASS  scale up from zero: 3 StartContainer actions
PASS  scale up from 1 to 3: exactly 2 StartContainer actions
PASS  scale down from 3 to 1: stops newest+middle, keeps oldest
PASS  already converged (2 desired, 2 running): zero actions
PASS  two independent services converge simultaneously: 1 start (web) + 1 stop (worker)
PASS  orphaned service (removed from spec) has all containers stopped, converged service untouched
PASS  service explicitly desired at 0 replicas: stops all running containers
PASS  nothing desired, nothing running: zero actions, no crash
PASS  unhealthy container: stopped AND a replacement started
PASS  desired=2, one healthy + one unhealthy: stop the bad one, start exactly 1 replacement (not 2 -- the healthy one still counts)
PASS  a STARTING container counts toward desired replicas and is never restarted
PASS  a container with no HEALTHCHECK declared (NONE) is treated as fine, matching Phase 2's original running-is-good behavior
PASS  3 desired, 2 healthy + 1 unhealthy: only the unhealthy one is touched, healthy ones left alone
PASS  orphan cleanup stops containers regardless of health status
PASS  orphan cleanup also removes that service's stale restart-backoff state
PASS  tick 1: unhealthy container stopped and replacement started
PASS  tick 2 (1s later, within 5s backoff): the newly-unhealthy replacement is NOT stopped yet, but a replacement is still started to maintain capacity
PASS  once the backoff window has passed, the still-unhealthy container IS stopped and the failure count increments to 2
PASS  a fully-healthy tick resets consecutiveFailures back to 0
PASS  a FRESH RestartState allows a restart at any time
PASS  consecutive failures produce a growing (exponential) backoff delay
PASS  after many consecutive failures, the backoff delay is capped (never exceeds RestartState.MAX_DELAY_MILLIS)

22 passed, 0 failed
```

The 14 Phase 2 tests are unchanged and still pass via the original
2-argument `reconcile()` overload — deliberate regression proof that
becoming health-aware didn't alter Phase 2's original behavior for the
everything-is-healthy case. The 8 new tests specifically exercise the
trickiest part of this phase: backoff correctly preventing a repeat
restart within its window (tick 2 in the log above), allowing one again
once the window passes, and resetting cleanly after recovery — verified
with simulated timestamps, not a real clock or `Thread.sleep()`
anywhere in the test suite.

**The live run, against real, pre-existing, 3-day-old containers:**

```
mini-orchestrator: connecting to Docker daemon...
mini-orchestrator: watching spec.example.yaml, reconciling every 5s
[22:05:09] converged, no action needed
[22:05:14] converged, no action needed
[22:05:19] converged, no action needed
```

Confirmed independently against the same containers:

```bash
$ docker ps --filter "label=mini-orchestrator.managed=true"
CONTAINER ID   IMAGE                                        STATUS                CREATED
c595ef3c046f   mini-orchestrator-inventory-service:latest   Up 3 days (healthy)   3 days ago
fa3d04899cca   mini-orchestrator-inventory-service:latest   Up 3 days (healthy)   3 days ago
425baa4ea773   mini-orchestrator-inventory-service:latest   Up 3 days (healthy)   3 days ago
0b9281aadf48   mini-orchestrator-orders-service:latest      Up 3 days (healthy)   3 days ago
```

This "nothing happened" result is more informative than it looks: the
new `inspectContainerCmd`-based health-reading code had to correctly
classify all 4 real containers as healthy for the orchestrator to stay
quiet. If the health-status mapping were subtly wrong — say, misreading
Docker's `"healthy"` string as `UNHEALTHY` — the visible result would
have been a storm of unwanted stop/replace actions on the very first
tick, not silence. Quiet convergence against genuinely healthy
containers is a real (if undramatic) confirmation the mapping works.

### Known limitations (Phase 3)

- **An extra Docker API call per managed container, every tick.**
  `listContainersCmd()` alone doesn't expose structured health data —
  only `inspectContainerCmd(id)` does. `listManagedContainers()` now
  makes one list call plus one inspect call per managed container, every
  5 seconds. Trivially cheap for the handful of containers a local demo
  manages; would need batching or caching to stay cheap at real scale
  with hundreds of managed containers — a deliberate simplification for
  this project's scope, not an oversight.
- **No distinction between different causes of "unhealthy."** A
  container that's unhealthy because of a transient blip and one that's
  unhealthy because the image is fundamentally broken are treated
  identically — both get restarted on the same backoff schedule. A real
  system might want different handling (e.g. giving up entirely after N
  consecutive failures, rather than backing off indefinitely).
- **Restart counts aren't exposed anywhere outside the log.** They exist
  in `RestartState` and are used for backoff timing, but there's no
  `status` command or API to query "how many times has this service
  restarted" — only scrollback through the log. Phase 5's planned CLI is
  the natural place to surface this.
- **A replacement container isn't verified as actually healthy before
  the incident is considered resolved.** Once a bad container is
  stopped and a replacement started, the orchestrator moves on; if the
  replacement *also* comes up unhealthy, that's caught on a later tick
  (as an ordinary new failure, subject to backoff) rather than treated
  as a continuation of the same incident with any special handling.
- **The backoff policy (base delay, cap, growth rate) is a single global
  constant, not configurable per service.** A service known to be
  flaky and a service that should almost never fail currently get
  identical backoff behavior.

---

## Phase 4: rolling deploys

Phase 2 and 3 could keep the right *number* of replicas running and
replace a broken one, but neither had any concept of *which image* a
running container was actually on. Change `image:` in the spec from one
tag to another and save -- nothing would happen. `Reconciler` only ever
counted containers by service name; it had no way to tell "this running
container is on the old image" from "this container already matches
what the spec currently wants." That's the actual gap this phase closes.

### Making image drift detectable at all

Every container this orchestrator creates now carries a
`mini-orchestrator.image` label recording exactly which image it was
started from (`DockerActuator`, at creation time). `listManagedContainers()`
reads it back into `ManagedContainer.image()`, and `Reconciler` uses it
to partition every service's running containers into two groups before
doing anything else: **current-image** (matches what the spec wants
right now) and **stale-image** (doesn't). Phase 3's health/backoff logic
runs only within the current-image group from this point on -- a stale
container's health is irrelevant to whether it should keep running;
it's being replaced regardless of whether its healthcheck currently
passes.

### The actual zero-downtime guarantee: STARTING isn't enough

A stale replica is never retired until a same-service replacement on the
new image is **confirmed `HEALTHY`** -- not merely `STARTING`. This is
the single detail that makes the difference between a real rolling
deploy and just "restart everything and hope": retiring a still-working
old replica the instant a replacement merely begins starting (before its
own healthcheck has actually passed) could momentarily leave *less*
healthy capacity than before the deploy began, which defeats the entire
point. `STARTING` counting as "good enough" for ordinary scale-up
(Phase 3) and `STARTING` *not* counting as "ready to retire the old one
for" here are deliberately different bars for deliberately different
decisions, made explicit in `Reconciler` rather than left implicit.

### Pacing: one retirement per tick, oldest first

When multiple stale replicas exist, only one is retired per
reconciliation tick, oldest-started first. Order doesn't affect
correctness here -- every stale replica is leaving regardless -- but
pacing the retirements one at a time, rather than tearing down every
stale container the instant any single healthy replacement exists,
keeps the blast radius of each tick small and the rollout's progress
visibly incremental in the log, which matters more for an operator
watching a real deploy than it does for correctness on paper.

### Why a `null` image is "current," not "stale"

A container started by a pre-Phase-4 orchestrator simply never had this
label set. Defaulting an unlabeled container to "assume stale, replace
it" would force a surprise rolling replacement of *every* existing
container the very first time this version runs against them.
`Reconciler` treats a `null` image as matching whatever the spec
currently wants -- backward-compatible by not guessing, not by assuming
the more aggressive interpretation.

**This wasn't just a theoretical decision -- it was hit immediately while
testing this phase live**, and is worth narrating honestly as a real
lesson, not a hypothetical: the 3 `inventory-service` containers running
at the time this phase was tested were 3 days old, created by a
pre-Phase-4 build. Bumping the spec's image tag to `:v2` and saving
produced exactly the expected `converged, no action needed` -- correct
per the design, but not demoable, since those specific containers had no
image label to compare against at all. Getting an actual, observable
rolling deploy required first scaling the service to 0 and back up to 3
*on the existing image* to establish a properly-labeled baseline, and
only then changing the image tag. A real, useful thing to know about
upgrading a running orchestrator to a version that starts tracking new
metadata: existing state doesn't retroactively gain the new metadata
just because the code now expects it.

### Verified behavior (Phase 4)

**The pure core, actually tested -- 36/36, up from 22:**

```
$ java com.sujanuj.orchestrator.core.ReconcilerSelfTest
[... all Phase 2/3 tests unchanged and still passing ...]
PASS  spec image changed (v1 -> v2): a new v2 replica starts immediately, old v1 replica is NOT touched yet (no confirmed-healthy replacement exists)
PASS  a v2 replacement that is itself UNHEALTHY never causes the still-working old replica to be retired -- correctness over completing the rollout on schedule
PASS  a v2 replacement that is only STARTING (not yet confirmed healthy) does NOT cause the old replica to be retired -- this is the actual zero-downtime guarantee
PASS  once the v2 replacement is genuinely HEALTHY, the old v1 replica IS retired (reason: ROLLING_DEPLOY), and no further replicas are started (already at desired count)
PASS  3 old replicas still present, only ONE confirmed-healthy new replica so far: exactly one old replica is retired this tick, not all three at once
PASS  when multiple stale replicas exist, the OLDEST one is retired first
PASS  a container with no image label at all (null) is treated as CURRENT, not stale -- no surprise rolling replacement of pre-Phase-4 containers
PASS  an unhealthy STALE-image container doesn't trigger Phase 3's health/backoff path -- only a v2 replica is started; the old one is left for rolling retirement, not restart logic
PASS  over-provisioned on the NEW image while a stale straggler remains: ordinary scale-down is skipped (new replicas aren't prematurely removed), the stale one is retired via rolling deploy instead
PASS  full rolling-deploy sequence, tick 1: starts new v2 replica, keeps old v1 running
PASS  full rolling-deploy sequence, tick 2: new replica only STARTING -> old still kept, no actions
PASS  full rolling-deploy sequence, tick 3: new replica now HEALTHY -> old v1 retired
PASS  full rolling-deploy sequence, tick 4: fully converged on v2, zero actions
PASS  orphan cleanup stops containers regardless of image identity too

36 passed, 0 failed
```

The 4-tick simulated sequence is the important one: it exercises the
*entire* rolling-deploy lifecycle with simulated timestamps, no real
Docker daemon and no `Thread.sleep()` anywhere, and it's what gives real
confidence in the design beyond "it compiled."

**Live, against a real Docker daemon -- confirmed so far:**

```
[17:44:33] stopping 'inventory-service' replica c595ef3c046f (reason: SCALE_DOWN)
[17:44:33] stopping 'inventory-service' replica fa3d04899cca (reason: SCALE_DOWN)
[17:44:33] stopping 'inventory-service' replica 425baa4ea773 (reason: SCALE_DOWN)
...
[17:45:38] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[17:45:38] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[17:45:38] starting new replica of 'inventory-service' (mini-orchestrator-inventory-service:latest)
[17:45:43] converged, no action needed
```

This confirms `StopReason` labeling is correct in practice (`SCALE_DOWN`,
not `ROLLING_DEPLOY`, for an ordinary replica-count change -- exactly
right, since no image change was involved) and that freshly-created
replicas are the properly-labeled baseline the actual image-swap test
needs. **The full live `:v1` → `:v2` retirement sequence (new replicas
starting, then old ones retiring one at a time with `reason:
ROLLING_DEPLOY` once each new one passes health) was in progress and not
yet captured at the time this section was written** -- called out here
explicitly rather than presented as confirmed when it wasn't. The pure
core's 36 tests already prove the *decision logic* is correct; this last
piece is end-to-end confirmation against the real Docker API path
specifically, the same standard every other phase in this README was
held to.

### Known limitations (Phase 4)

- **No `maxSurge` or `maxUnavailable` limit.** Every stale replica's
  replacement is started immediately, all at once, regardless of how
  many that is -- only the *retirement* side is paced (one per tick).
  For a service with many replicas, this means a real resource spike
  (2x the normal container count) during the early part of a rollout.
  A real `maxSurge` setting, capping how many new replicas start
  simultaneously, isn't implemented here.
- **A mutable tag (e.g. always deploying under `:latest`) can't be
  detected as a change.** Image comparison is a plain string check
  against the tag written in the spec. If the underlying image content
  changes but the tag string doesn't, `Reconciler` sees no difference
  and does nothing -- the same real gotcha Kubernetes' `imagePullPolicy`
  exists to work around, not solved here.
- **No rollback.** If a `:v2` replacement never becomes healthy, the
  rollout simply stalls indefinitely (old replicas keep running, since
  they're never retired without a confirmed-healthy replacement) rather
  than automatically reverting to the previous image. Stalling safely is
  the correct behavior for *not making things worse*, but an operator
  currently has to notice and intervene manually by editing the spec
  back -- there's no automatic "this isn't working, revert" logic.
- **A replacement that becomes healthy and then goes unhealthy shortly
  after being retired for isn't specially detected as "this rollout
  regressed."** It's caught by ordinary Phase 3 restart/backoff on a
  later tick, the same as any other health failure, with no
  rollout-specific handling.

---

## Roadmap

**Phase 5 — a CLI** (`apply`, `status`, `scale`, `rollback`) against this
project's own spec format. Restart counts and rolling-deploy progress
(Phases 3-4) currently only exist in the reconciliation log's scrollback
-- a real `status` command is the natural place to surface them instead.

**Explicitly out of scope, named now rather than discovered later:**
true multi-node scheduling across multiple Docker hosts. The scheduler
will be built with a pluggable "node" interface so the architecture is
real, but the actual demo targets a single Docker host.

## Running it

**Phase 1 — the microservices:**

```bash
git clone https://github.com/sujanuj/mini-orchestrator.git
cd mini-orchestrator
docker compose up --build
```

```bash
curl http://localhost:8081/health
curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
  -d '{"item":"widget","quantity":5}'
```

**Phase 2 — the orchestrator** (requires a JDK 21 to build and run,
even if your default system JDK is older — see bug #2 above):

```bash
cd orchestrator
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; adjust for your platform
mvn -B -DskipTests package
$JAVA_HOME/bin/java -jar target/orchestrator.jar spec.example.yaml
```

Then, in another terminal, confirm what's running:

```bash
docker ps --filter "label=mini-orchestrator.managed=true"
```

Edit `replicas:` in `spec.example.yaml` and save — no restart needed,
the next reconciliation tick (within 5s) picks it up.

To run the pure core's self-tests directly, with no Docker daemon and
no Maven Central needed:

```bash
cd orchestrator/src/main/java
javac com/sujanuj/orchestrator/core/*.java
java com.sujanuj.orchestrator.core.ReconcilerSelfTest
```

## Project layout

```
mini-orchestrator/
├── docker-compose.yml
├── services/
│   ├── inventory-service/
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/main/
│   │       ├── java/com/sujanuj/inventory/
│   │       │   ├── InventoryApplication.java
│   │       │   └── InventoryController.java
│   │       └── resources/application.properties
│   └── orders-service/
│       ├── pom.xml
│       ├── Dockerfile
│       └── src/main/
│           ├── java/com/sujanuj/orders/
│           │   ├── OrdersApplication.java
│           │   ├── OrdersController.java
│           │   └── RestTemplateConfig.java
│           └── resources/application.properties
└── orchestrator/
    ├── pom.xml
    ├── spec.example.yaml
    └── src/main/java/com/sujanuj/orchestrator/
        ├── Main.java
        ├── core/              <- pure, dependency-free reconciliation logic
        │   ├── ServiceSpec.java
        │   ├── ManagedContainer.java
        │   ├── HealthStatus.java      <- Phase 3
        │   ├── ReconcileAction.java
        │   ├── StopReason.java         <- Phase 3
        │   ├── ReconcileResult.java     <- Phase 3
        │   ├── RestartState.java         <- Phase 3
        │   ├── Reconciler.java
        │   └── ReconcilerSelfTest.java
        ├── docker/             <- docker-java integration (Docker Engine API)
        │   └── DockerActuator.java
        └── spec/               <- YAML spec parsing
            └── SpecLoader.java
```
