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

**Phase 3 (next): health checking + auto-restart.** Not started yet. See
**Roadmap**.

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

## Roadmap

**Phase 3 — health checking + auto-restart.** Consult each container's
actual `HEALTHCHECK` status, not just "is it running." On failure,
restart with backoff; track restart counts. This is the phase that
turns the `503`s demonstrated in Phase 1 from a permanent failure into
a transient one, and closes Phase 2's biggest named limitation.

**Phase 4 — rolling deploys.** Deploy a new image version with
zero-downtime replacement: start the new container, wait for it to pass
health checks, only then stop the old one. The headline demo: `docker
kill` a container mid-traffic and watch the system self-heal live.

**Phase 5 — a CLI** (`apply`, `status`, `scale`, `rollback`) against this
project's own spec format.

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
        │   ├── ReconcileAction.java
        │   ├── Reconciler.java
        │   └── ReconcilerSelfTest.java
        ├── docker/             <- docker-java integration (Docker Engine API)
        │   └── DockerActuator.java
        └── spec/               <- YAML spec parsing
            └── SpecLoader.java
```
