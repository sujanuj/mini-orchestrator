package com.sujanuj.orchestrator.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.PortBinding;
import com.sujanuj.orchestrator.core.HealthStatus;
import com.sujanuj.orchestrator.core.ManagedContainer;
import com.sujanuj.orchestrator.core.ReconcileAction;
import com.sujanuj.orchestrator.core.ServiceSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The thin translation layer between the pure decision logic
 * (com.sujanuj.orchestrator.core.Reconciler) and real Docker Engine API
 * calls via docker-java. Deliberately "dumb": every method here does one
 * direct thing (list containers, start one, stop one) with no decision-
 * making of its own -- all the actual logic already happened in
 * Reconciler, which is where it's testable without a Docker daemon at
 * all. If this class's behavior looks wrong, the bug is more likely in
 * how a docker-java API call is being made than in what was decided.
 *
 * NOTE ON VERIFICATION: this class was written and reviewed against the
 * real, current docker-java 3.3.x API (confirmed via docker-java's own
 * javadoc and source, not assumed from memory), but could not be
 * compiled in the environment this was written in -- no network access
 * to Maven Central to fetch docker-java itself. Real verification
 * happens via `mvn package` and actually running against a live Docker
 * daemon, which needs an environment this one doesn't have. Treat this
 * class with the same "verify for real before trusting it" standard as
 * the rest of this portfolio, not as already-proven.
 */
public final class DockerActuator {

    /** Every container this orchestrator creates carries this label,
     * set to "true" -- this is how listManagedContainers() distinguishes
     * containers it owns from every other unrelated container that
     * might be running on the same Docker host (including the
     * docker-compose-managed inventory-service/orders-service baseline
     * from Phase 1, which deliberately does NOT carry this label and is
     * therefore invisible to and untouched by the orchestrator). */
    public static final String LABEL_MANAGED = "mini-orchestrator.managed";
    public static final String LABEL_SERVICE = "mini-orchestrator.service";

    private static final String NETWORK_NAME = "mini-orchestrator-net";
    private static final int STOP_TIMEOUT_SECONDS = 10;

    private final DockerClient client;

    public DockerActuator(DockerClient client) {
        this.client = client;
    }

    /**
     * Ensures the orchestrator's own dedicated Docker network exists,
     * creating it if not. Deliberately a SEPARATE network from
     * docker-compose's own `mini-orchestrator_default` network (Phase
     * 1), not a shared one -- compose networks are lifecycle-owned by
     * Compose (torn down on `docker compose down`), and mixing an
     * externally-managed network's lifecycle with the orchestrator's
     * own containers would make "which system owns this network"
     * ambiguous. Every container this orchestrator creates joins THIS
     * network, so orchestrator-managed replicas of different services
     * can resolve each other by container name, the same way
     * Phase 1's compose-managed containers already do on their own
     * separate network.
     */
    public void ensureNetworkExists() {
        List<Network> existing = client.listNetworksCmd()
                .withNameFilter(NETWORK_NAME)
                .exec();
        if (existing.isEmpty()) {
            client.createNetworkCmd()
                    .withName(NETWORK_NAME)
                    .withDriver("bridge")
                    .exec();
        }
    }

    /**
     * Lists every currently-RUNNING container this orchestrator manages
     * (carries LABEL_MANAGED=true), grouped by the service name recorded
     * on LABEL_SERVICE -- now including each one's real Docker health
     * status (Phase 3), not just the fact that it's running.
     *
     * Cost, named honestly: `listContainersCmd()` alone doesn't expose
     * structured health data -- only `inspectContainerCmd(id)` does. This
     * method therefore makes ONE list call plus one ADDITIONAL inspect
     * call per managed container, every single reconciliation tick. For
     * the handful of containers a local demo manages, this is trivially
     * cheap; it would NOT scale to hundreds of managed containers polled
     * every 5 seconds without batching or caching -- a real, deliberate
     * simplification for this project's scope, not an oversight.
     */
    public Map<String, List<ManagedContainer>> listManagedContainers() {
        List<Container> containers = client.listContainersCmd()
                .withShowAll(false) // running only
                .withLabelFilter(Map.of(LABEL_MANAGED, "true"))
                .exec();

        Map<String, List<ManagedContainer>> result = new LinkedHashMap<>();
        for (Container c : containers) {
            String serviceName = c.getLabels() == null ? null : c.getLabels().get(LABEL_SERVICE);
            if (serviceName == null) {
                // Should be unreachable given the label filter above,
                // but a container missing its own service label is
                // exactly the kind of thing worth failing loudly on
                // rather than silently grouping under a null key.
                throw new IllegalStateException(
                        "container " + c.getId() + " has " + LABEL_MANAGED
                                + "=true but no " + LABEL_SERVICE + " label -- this should be impossible "
                                + "for any container this orchestrator created");
            }
            // Container.getCreated() is epoch SECONDS, not millis --
            // and it's creation time, not exact start time. For
            // containers this orchestrator creates, create-then-start
            // always happen back to back in startContainer() below, so
            // the two are effectively the same instant for the
            // newest-first scale-down ordering Reconciler uses this
            // for; the distinction would only matter for a container
            // created long before being started, which never happens
            // here.
            long startedAtMillis = c.getCreated() == null ? 0L : c.getCreated() * 1000L;
            HealthStatus health = inspectHealth(c.getId());
            result.computeIfAbsent(serviceName, k -> new ArrayList<>())
                    .add(new ManagedContainer(c.getId(), serviceName, startedAtMillis, health));
        }
        return result;
    }

    /**
     * Queries a single container's real Docker health status. A null
     * Health object means the image has no HEALTHCHECK declared at all
     * (maps to HealthStatus.NONE) -- both this project's own Dockerfiles
     * DO declare one, so in practice this only matters for images
     * outside this project's control.
     */
    private HealthStatus inspectHealth(String containerId) {
        var inspection = client.inspectContainerCmd(containerId).exec();
        var health = inspection.getState() == null ? null : inspection.getState().getHealth();
        if (health == null || health.getStatus() == null) {
            return HealthStatus.NONE;
        }
        return switch (health.getStatus()) {
            case "healthy" -> HealthStatus.HEALTHY;
            case "unhealthy" -> HealthStatus.UNHEALTHY;
            case "starting" -> HealthStatus.STARTING;
            // An unrecognized status string from a future Docker version
            // is treated as NONE (i.e. "don't act on health for this
            // container") rather than guessed at -- silently assuming a
            // meaning for a string this code doesn't recognize is worse
            // than falling back to Phase 2's original running-is-good
            // behavior for it.
            default -> HealthStatus.NONE;
        };
    }

    public void apply(ReconcileAction action) {
        switch (action) {
            case ReconcileAction.StartContainer start -> startContainer(start.spec());
            case ReconcileAction.StopContainer stop -> stopContainer(stop.containerId(), stop.serviceName());
        }
    }

    private void startContainer(ServiceSpec spec) {
        String containerName = "mo-" + spec.name() + "-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_MANAGED, "true");
        labels.put(LABEL_SERVICE, spec.name());

        List<String> envList = spec.env().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.toList());

        var createCmd = client.createContainerCmd(spec.image())
                .withName(containerName)
                .withLabels(labels)
                .withEnv(envList);

        HostConfig hostConfig = HostConfig.newHostConfig().withNetworkMode(NETWORK_NAME);

        if (spec.containerPort() != null) {
            ExposedPort exposed = ExposedPort.tcp(spec.containerPort());
            createCmd = createCmd.withExposedPorts(exposed);

            if (spec.hostPort() != null) {
                // Publishing to a fixed host port only makes sense at
                // replicas=1 -- two containers can't both bind the same
                // host port. This orchestrator does not currently
                // validate that at spec-load time (see Known
                // limitations); it's the operator's responsibility for
                // now to only set hostPort on singleton services, the
                // same way it's the operator's responsibility today to
                // avoid two totally unrelated services sharing a
                // hostPort by mistake.
                hostConfig = hostConfig.withPortBindings(PortBinding.parse(
                        spec.hostPort() + ":" + spec.containerPort()));
            }
        }

        createCmd = createCmd.withHostConfig(hostConfig);

        CreateContainerResponse response = createCmd.exec();
        try {
            client.startContainerCmd(response.getId()).exec();
        } catch (RuntimeException startFailure) {
            // REAL BUG, found by running this against a live Docker
            // daemon: create() and start() are two separate API calls,
            // and create() can succeed even when start() is about to
            // fail (e.g. a host port already bound by something else --
            // Docker doesn't validate port availability until the
            // container actually starts, not at creation time). Without
            // this catch, a failed start left the just-created container
            // behind in Docker's "created" (never running) state
            // forever. Since listManagedContainers() only counts RUNNING
            // containers, that orphan was invisible to the next
            // reconciliation tick -- so Reconciler saw the same deficit
            // again, and Main's loop tried again, creating ANOTHER
            // container that also failed to start for the identical
            // reason, forever, once every 5 seconds, each attempt
            // leaking one more permanently-orphaned container. Cleaning
            // up the failed container here turns "leak one container per
            // failed retry, forever" into "retry against a clean slate
            // every time" -- correct either way once the actual
            // blocker (e.g. the port conflict) is resolved by an
            // operator, but only one of the two behaves sanely while
            // that blocker is still present.
            try {
                client.removeContainerCmd(response.getId()).withForce(true).exec();
            } catch (RuntimeException cleanupFailure) {
                // Best-effort: if even cleanup fails, surface both
                // exceptions rather than silently swallowing the
                // cleanup failure and only reporting the original one.
                startFailure.addSuppressed(cleanupFailure);
            }
            throw startFailure;
        }
    }

    private void stopContainer(String containerId, String serviceName) {
        try {
            client.stopContainerCmd(containerId).withTimeout(STOP_TIMEOUT_SECONDS).exec();
        } finally {
            // Always attempt removal, even if stop failed or the
            // container was already stopped (e.g. it crashed on its
            // own between the list call and this apply() call) --
            // withForce(true) handles both "already stopped, just
            // remove it" and "still running for some reason, force it."
            // An orchestrator that stops containers but never removes
            // them would leak stopped containers on every scale-down,
            // which defeats the whole point of managing replica count.
            client.removeContainerCmd(containerId).withForce(true).exec();
        }
    }
}
