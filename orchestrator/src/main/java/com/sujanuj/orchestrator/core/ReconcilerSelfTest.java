package com.sujanuj.orchestrator.core;

import java.util.List;
import java.util.Map;

/**
 * A plain-Java self-test harness for Reconciler and RestartState -- no
 * JUnit, no test framework, deliberately, since the point is to verify
 * this logic is correct using only what's guaranteed to be available (a
 * JDK), the same way the rest of this package has zero external
 * dependencies. Run with: javac then java ReconcilerSelfTest.
 *
 * Phase 2 tests (scale up/down, orphan cleanup, converged, etc.) are
 * kept using the original 2-argument reconcile() overload, unchanged --
 * this is deliberate regression proof that Phase 3's health-aware
 * rewrite didn't alter Phase 2's original behavior for the
 * everything-is-healthy case. Phase 3 tests use the new 4-argument
 * overload and exercise health-awareness and backoff specifically.
 */
public final class ReconcilerSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // Phase 2 regression tests
        testScaleUpFromZero();
        testScaleUpPartial();
        testScaleDownStopsNewestFirst();
        testAlreadyConverged();
        testMultipleServicesIndependent();
        testOrphanCleanup();
        testScaleToZeroKeepsServiceInDesiredButStopsAll();
        testEmptyDesiredAndEmptyActual();

        // Phase 3: health-awareness
        testUnhealthyContainerTriggersReplacement();
        testUnhealthyDoesNotCountTowardDesiredReplicas();
        testStartingStatusCountsAsGoodEnough();
        testNoneStatusCountsAsGoodEnough();
        testHealthyMixedWithUnhealthyOnlyReplacesTheUnhealthyOne();
        testOrphanCleanupStopsUnhealthyContainersToo();
        testOrphanCleanupRemovesRestartState();

        // Phase 3: backoff
        testBackoffPreventsImmediateRepeatRestart();
        testBackoffAllowsRestartAfterWindowPasses();
        testBackoffResetsAfterHealthyTick();

        // Phase 3: RestartState itself
        testRestartStateFreshAllowsImmediateRestart();
        testRestartStateExponentialGrowth();
        testRestartStateCapsAtMaxDelay();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // -------------------------------------------------------------
    // Phase 2 regression tests (backward-compat 2-arg overload)
    // -------------------------------------------------------------

    private static void testScaleUpFromZero() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 3, 80, null, "/health", Map.of());
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(spec), Map.of());

        check("scale up from zero: 3 StartContainer actions",
                actions.size() == 3
                        && actions.stream().allMatch(a -> a instanceof ReconcileAction.StartContainer));
    }

    private static void testScaleUpPartial() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 3, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("c1", "web", 1000L, HealthStatus.HEALTHY));
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(spec), Map.of("web", running));

        check("scale up from 1 to 3: exactly 2 StartContainer actions",
                actions.size() == 2
                        && actions.stream().allMatch(a -> a instanceof ReconcileAction.StartContainer));
    }

    private static void testScaleDownStopsNewestFirst() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("oldest", "web", 1000L, HealthStatus.HEALTHY),
                new ManagedContainer("middle", "web", 2000L, HealthStatus.HEALTHY),
                new ManagedContainer("newest", "web", 3000L, HealthStatus.HEALTHY));
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(spec), Map.of("web", running));

        boolean exactlyTwoStops = actions.size() == 2
                && actions.stream().allMatch(a -> a instanceof ReconcileAction.StopContainer);
        boolean stoppedNewestAndMiddle = actions.stream()
                .map(a -> ((ReconcileAction.StopContainer) a).containerId())
                .allMatch(id -> id.equals("newest") || id.equals("middle"));
        boolean oldestSurvived = actions.stream()
                .noneMatch(a -> ((ReconcileAction.StopContainer) a).containerId().equals("oldest"));

        check("scale down from 3 to 1: stops newest+middle, keeps oldest",
                exactlyTwoStops && stoppedNewestAndMiddle && oldestSurvived);
    }

    private static void testAlreadyConverged() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 2, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("c1", "web", 1000L, HealthStatus.HEALTHY),
                new ManagedContainer("c2", "web", 2000L, HealthStatus.HEALTHY));
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(spec), Map.of("web", running));

        check("already converged (2 desired, 2 running): zero actions",
                actions.isEmpty());
    }

    private static void testMultipleServicesIndependent() {
        ServiceSpec web = new ServiceSpec("web", "nginx:latest", 2, 80, null, "/health", Map.of());
        ServiceSpec worker = new ServiceSpec("worker", "worker:latest", 1, null, null, "/health", Map.of());

        Map<String, List<ManagedContainer>> actual = Map.of(
                "web", List.of(new ManagedContainer("w1", "web", 1000L, HealthStatus.HEALTHY)),
                "worker", List.of(
                        new ManagedContainer("k1", "worker", 1000L, HealthStatus.HEALTHY),
                        new ManagedContainer("k2", "worker", 2000L, HealthStatus.HEALTHY))
        );

        List<ReconcileAction> actions = Reconciler.reconcile(List.of(web, worker), actual);

        long starts = actions.stream().filter(a -> a instanceof ReconcileAction.StartContainer).count();
        long stops = actions.stream().filter(a -> a instanceof ReconcileAction.StopContainer).count();

        check("two independent services converge simultaneously: 1 start (web) + 1 stop (worker)",
                starts == 1 && stops == 1 && actions.size() == 2);
    }

    private static void testOrphanCleanup() {
        ServiceSpec web = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        Map<String, List<ManagedContainer>> actual = Map.of(
                "web", List.of(new ManagedContainer("w1", "web", 1000L, HealthStatus.HEALTHY)),
                "legacy-service", List.of(
                        new ManagedContainer("l1", "legacy-service", 500L, HealthStatus.HEALTHY),
                        new ManagedContainer("l2", "legacy-service", 600L, HealthStatus.HEALTHY))
        );

        List<ReconcileAction> actions = Reconciler.reconcile(List.of(web), actual);

        boolean onlyLegacyStopped = actions.size() == 2
                && actions.stream().allMatch(a ->
                        a instanceof ReconcileAction.StopContainer sc
                                && sc.serviceName().equals("legacy-service"));

        check("orphaned service (removed from spec) has all containers stopped, "
                + "converged service untouched", onlyLegacyStopped);
    }

    private static void testScaleToZeroKeepsServiceInDesiredButStopsAll() {
        ServiceSpec web = new ServiceSpec("web", "nginx:latest", 0, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("w1", "web", 1000L, HealthStatus.HEALTHY),
                new ManagedContainer("w2", "web", 2000L, HealthStatus.HEALTHY));

        List<ReconcileAction> actions = Reconciler.reconcile(List.of(web), Map.of("web", running));

        check("service explicitly desired at 0 replicas: stops all running containers",
                actions.size() == 2
                        && actions.stream().allMatch(a -> a instanceof ReconcileAction.StopContainer));
    }

    private static void testEmptyDesiredAndEmptyActual() {
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(), Map.of());
        check("nothing desired, nothing running: zero actions, no crash", actions.isEmpty());
    }

    // -------------------------------------------------------------
    // Phase 3: health-awareness (4-arg overload)
    // -------------------------------------------------------------

    private static void testUnhealthyContainerTriggersReplacement() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("bad", "web", 1000L, HealthStatus.UNHEALTHY));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean stoppedBad = result.actions().stream().anyMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("bad"));
        boolean startedReplacement = result.actions().stream()
                .anyMatch(a -> a instanceof ReconcileAction.StartContainer);

        check("unhealthy container: stopped AND a replacement started",
                stoppedBad && startedReplacement && result.actions().size() == 2);
    }

    private static void testUnhealthyDoesNotCountTowardDesiredReplicas() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 2, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("good", "web", 1000L, HealthStatus.HEALTHY),
                new ManagedContainer("bad", "web", 2000L, HealthStatus.UNHEALTHY));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        long starts = result.actions().stream().filter(a -> a instanceof ReconcileAction.StartContainer).count();
        long stops = result.actions().stream().filter(a -> a instanceof ReconcileAction.StopContainer).count();

        check("desired=2, one healthy + one unhealthy: stop the bad one, start exactly 1 replacement "
                + "(not 2 -- the healthy one still counts)", starts == 1 && stops == 1);
    }

    private static void testStartingStatusCountsAsGoodEnough() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("booting", "web", 1000L, HealthStatus.STARTING));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        check("a STARTING container counts toward desired replicas and is never restarted",
                result.actions().isEmpty());
    }

    private static void testNoneStatusCountsAsGoodEnough() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("no-healthcheck", "web", 1000L, HealthStatus.NONE));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        check("a container with no HEALTHCHECK declared (NONE) is treated as fine, "
                + "matching Phase 2's original running-is-good behavior", result.actions().isEmpty());
    }

    private static void testHealthyMixedWithUnhealthyOnlyReplacesTheUnhealthyOne() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 3, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("h1", "web", 1000L, HealthStatus.HEALTHY),
                new ManagedContainer("h2", "web", 2000L, HealthStatus.HEALTHY),
                new ManagedContainer("bad", "web", 3000L, HealthStatus.UNHEALTHY));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean onlyBadStopped = result.actions().stream()
                .filter(a -> a instanceof ReconcileAction.StopContainer)
                .allMatch(a -> ((ReconcileAction.StopContainer) a).containerId().equals("bad"));

        check("3 desired, 2 healthy + 1 unhealthy: only the unhealthy one is touched, "
                + "healthy ones left alone", onlyBadStopped);
    }

    private static void testOrphanCleanupStopsUnhealthyContainersToo() {
        Map<String, List<ManagedContainer>> actual = Map.of(
                "removed-service", List.of(
                        new ManagedContainer("x1", "removed-service", 500L, HealthStatus.UNHEALTHY))
        );
        ReconcileResult result = Reconciler.reconcile(List.of(), actual, Map.of(), 10_000L);

        check("orphan cleanup stops containers regardless of health status",
                result.actions().size() == 1
                        && result.actions().get(0) instanceof ReconcileAction.StopContainer);
    }

    private static void testOrphanCleanupRemovesRestartState() {
        Map<String, List<ManagedContainer>> actual = Map.of(
                "removed-service", List.of(
                        new ManagedContainer("x1", "removed-service", 500L, HealthStatus.HEALTHY))
        );
        Map<String, RestartState> priorState = Map.of(
                "removed-service", new RestartState(3, 999_999L));

        ReconcileResult result = Reconciler.reconcile(List.of(), actual, priorState, 10_000L);

        check("orphan cleanup also removes that service's stale restart-backoff state",
                !result.restartState().containsKey("removed-service"));
    }

    // -------------------------------------------------------------
    // Phase 3: backoff behavior across simulated ticks
    // -------------------------------------------------------------

    private static void testBackoffPreventsImmediateRepeatRestart() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());

        // Tick 1: an unhealthy container is observed for the first time.
        List<ManagedContainer> tick1Running = List.of(
                new ManagedContainer("bad-1", "web", 1000L, HealthStatus.UNHEALTHY));
        ReconcileResult tick1 = Reconciler.reconcile(List.of(spec), Map.of("web", tick1Running), Map.of(), 10_000L);

        boolean tick1StoppedIt = tick1.actions().stream().anyMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("bad-1"));
        boolean tick1StartedReplacement = tick1.actions().stream()
                .anyMatch(a -> a instanceof ReconcileAction.StartContainer);

        // Tick 2: simulates the replacement (a NEW container) ALSO
        // coming up unhealthy quickly, observed only 1 second later --
        // well within the 5s base backoff window from tick 1.
        List<ManagedContainer> tick2Running = List.of(
                new ManagedContainer("bad-2", "web", 10_500L, HealthStatus.UNHEALTHY));
        ReconcileResult tick2 = Reconciler.reconcile(
                List.of(spec), Map.of("web", tick2Running), tick1.restartState(), 11_000L);

        boolean tick2DidNotStopIt = tick2.actions().stream().noneMatch(a ->
                a instanceof ReconcileAction.StopContainer);
        boolean tick2StillStartsReplacement = tick2.actions().stream()
                .anyMatch(a -> a instanceof ReconcileAction.StartContainer);

        check("tick 1: unhealthy container stopped and replacement started",
                tick1StoppedIt && tick1StartedReplacement);
        check("tick 2 (1s later, within 5s backoff): the newly-unhealthy replacement is "
                + "NOT stopped yet, but a replacement is still started to maintain capacity",
                tick2DidNotStopIt && tick2StillStartsReplacement);
    }

    private static void testBackoffAllowsRestartAfterWindowPasses() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        Map<String, RestartState> priorState = Map.of("web", new RestartState(1, 5_000L));

        List<ManagedContainer> running = List.of(
                new ManagedContainer("still-bad", "web", 1000L, HealthStatus.UNHEALTHY));

        // nowEpochMillis (6000) is PAST the backoff deadline (5000) --
        // a restart should be allowed again.
        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), priorState, 6_000L);

        boolean stopped = result.actions().stream().anyMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("still-bad"));
        boolean failureCountIncremented = result.restartState().get("web").consecutiveFailures() == 2;

        check("once the backoff window has passed, the still-unhealthy container "
                + "IS stopped and the failure count increments to 2", stopped && failureCountIncremented);
    }

    private static void testBackoffResetsAfterHealthyTick() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        Map<String, RestartState> priorState = Map.of("web", new RestartState(3, 5_000L));

        // This tick, the service is fully healthy again.
        List<ManagedContainer> running = List.of(
                new ManagedContainer("recovered", "web", 6000L, HealthStatus.HEALTHY));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), priorState, 7_000L);

        RestartState after = result.restartState().get("web");
        check("a fully-healthy tick resets consecutiveFailures back to 0",
                after != null && after.consecutiveFailures() == 0);
    }

    // -------------------------------------------------------------
    // Phase 3: RestartState in isolation
    // -------------------------------------------------------------

    private static void testRestartStateFreshAllowsImmediateRestart() {
        check("a FRESH RestartState allows a restart at any time",
                RestartState.FRESH.canRestartNow(0L) && RestartState.FRESH.canRestartNow(999_999_999L));
    }

    private static void testRestartStateExponentialGrowth() {
        RestartState s1 = RestartState.FRESH.afterRestart(0L);
        RestartState s2 = s1.afterRestart(s1.nextRetryAllowedAtEpochMillis());
        RestartState s3 = s2.afterRestart(s2.nextRetryAllowedAtEpochMillis());

        boolean delaysGrow = (s2.nextRetryAllowedAtEpochMillis())
                > (s1.nextRetryAllowedAtEpochMillis())
                && (s3.nextRetryAllowedAtEpochMillis() - s2.nextRetryAllowedAtEpochMillis())
                   >= (s2.nextRetryAllowedAtEpochMillis() - s1.nextRetryAllowedAtEpochMillis());

        check("consecutive failures produce a growing (exponential) backoff delay",
                s1.consecutiveFailures() == 1 && s2.consecutiveFailures() == 2
                        && s3.consecutiveFailures() == 3 && delaysGrow);
    }

    private static void testRestartStateCapsAtMaxDelay() {
        RestartState s = RestartState.FRESH;
        long now = 0L;
        for (int i = 0; i < 15; i++) {
            s = s.afterRestart(now);
            now = s.nextRetryAllowedAtEpochMillis();
        }
        RestartState oneMore = s.afterRestart(now);
        long finalDelay = oneMore.nextRetryAllowedAtEpochMillis() - now;

        check("after many consecutive failures, the backoff delay is capped "
                + "(never exceeds RestartState.MAX_DELAY_MILLIS)",
                finalDelay <= RestartState.MAX_DELAY_MILLIS);
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS  " + description);
        } else {
            failed++;
            System.out.println("FAIL  " + description);
        }
    }
}
