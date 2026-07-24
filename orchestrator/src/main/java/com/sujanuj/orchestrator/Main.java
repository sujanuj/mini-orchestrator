package com.sujanuj.orchestrator;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.sujanuj.orchestrator.core.ReconcileAction;
import com.sujanuj.orchestrator.core.Reconciler;
import com.sujanuj.orchestrator.core.ServiceSpec;
import com.sujanuj.orchestrator.docker.DockerActuator;
import com.sujanuj.orchestrator.spec.SpecLoader;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point: `java -jar orchestrator.jar path/to/spec.yaml`.
 *
 * The spec file is re-read from disk on EVERY reconciliation tick,
 * deliberately -- this is what makes "edit replicas: 3 in spec.yaml and
 * save" a live scaling operation without restarting the orchestrator
 * process, which is by far the most demoable thing about this whole
 * project. The cost is re-parsing a small YAML file every few seconds,
 * which is negligible; the alternative (loading once at startup, or
 * watching the file for changes with a filesystem watcher) would either
 * lose the live-editing demo or add real complexity for a marginal
 * efficiency gain that doesn't matter at this scale.
 */
public final class Main {

    private static final int RECONCILE_INTERVAL_SECONDS = 5;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java -jar orchestrator.jar <path-to-spec.yaml>");
            System.exit(1);
        }
        String specPath = args[0];

        DockerClient client = buildDockerClient();
        DockerActuator actuator = new DockerActuator(client);

        System.out.println("mini-orchestrator: connecting to Docker daemon...");
        actuator.ensureNetworkExists();
        System.out.println("mini-orchestrator: watching " + specPath
                + ", reconciling every " + RECONCILE_INTERVAL_SECONDS + "s");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                () -> reconcileOnce(specPath, actuator),
                0, RECONCILE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("mini-orchestrator: shutting down (not stopping managed containers -- "
                    + "they keep running; the next orchestrator start will reconcile against them)");
            scheduler.shutdown();
        }));
    }

    /**
     * Constructs a DockerClient the current (docker-java 3.3.x) way.
     *
     * Older docker-java versions had a convenience
     * `DockerClientBuilder.getInstance().build()` one-liner; that class
     * was deprecated and has since been removed (confirmed by an actual
     * compile failure against the real 3.3.6 jar -- "cannot find symbol:
     * class DockerClientBuilder" -- not by assumption). The current
     * replacement is explicit: build a DockerClientConfig (which reads
     * standard DOCKER_HOST-style environment/config, defaulting to the
     * local Unix socket on Linux/Mac), build a DockerHttpClient
     * implementation on top of it (Apache HttpClient5, matching the
     * docker-java-transport-httpclient5 dependency already in pom.xml),
     * then hand both to DockerClientImpl.getInstance(). This is more
     * verbose than the old one-liner but also more explicit about what
     * transport is actually being used, which the old convenience
     * method hid.
     */
    private static DockerClient buildDockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * One reconciliation tick: load current desired state, observe
     * current actual state, compute the diff, apply it. Any failure
     * here (a malformed spec file, a Docker API error) is logged and
     * the loop continues to the next tick rather than crashing the
     * whole process -- a orchestrator that dies because of one
     * transient Docker API hiccup or one momentarily-invalid spec file
     * (e.g. caught mid-save by a text editor) would defeat its own
     * purpose.
     */
    private static void reconcileOnce(String specPath, DockerActuator actuator) {
        String timestamp = LocalTime.now().withNano(0).toString();
        try {
            List<ServiceSpec> desired = SpecLoader.load(specPath);
            Map<String, List<com.sujanuj.orchestrator.core.ManagedContainer>> actual =
                    actuator.listManagedContainers();

            List<ReconcileAction> actions = Reconciler.reconcile(desired, actual);

            if (actions.isEmpty()) {
                System.out.println("[" + timestamp + "] converged, no action needed");
                return;
            }

            for (ReconcileAction action : actions) {
                logAction(timestamp, action);
                actuator.apply(action);
            }
        } catch (SpecLoader.SpecParseException e) {
            System.err.println("[" + timestamp + "] spec error, skipping this tick: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[" + timestamp + "] reconciliation error, skipping this tick: " + e);
        }
    }

    private static void logAction(String timestamp, ReconcileAction action) {
        switch (action) {
            case ReconcileAction.StartContainer start ->
                    System.out.println("[" + timestamp + "] starting new replica of '"
                            + start.spec().name() + "' (" + start.spec().image() + ")");
            case ReconcileAction.StopContainer stop ->
                    System.out.println("[" + timestamp + "] stopping '" + stop.serviceName()
                            + "' replica " + stop.containerId().substring(0, Math.min(12, stop.containerId().length())));
        }
    }
}
