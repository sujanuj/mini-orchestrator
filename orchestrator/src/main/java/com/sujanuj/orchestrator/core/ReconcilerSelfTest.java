package com.sujanuj.orchestrator.core;

import java.util.List;
import java.util.Map;

/**
 * A plain-Java self-test harness for Reconciler, RestartState, and
 * rolling-deploy behavior -- no JUnit, no test framework, deliberately,
 * since the point is to verify this logic is correct using only what's
 * guaranteed to be available (a JDK), the same way the rest of this
 * package has zero external dependencies. Run with: javac then java
 * ReconcilerSelfTest.
 *
 * Phase 2/3 tests are kept using the original overloads, unchanged --
 * deliberate regression proof that Phase 4's image-awareness didn't
 * alter earlier behavior for the "everything's already on the current
 * image" case. Phase 4 tests use the 4-argument overload and construct
 * ManagedContainers with an explicit image, exercising rolling-deploy
 * detection and pacing specifically.
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

        // Phase 4: rolling deploys
        testStaleImageTriggersNewReplicaStart();
        testStaleImageNotRetiredUntilReplacementConfirmedHealthy();
        testStartingReplacementNotEnoughToRetireStale();
        testStaleRetiredOncePassedHealthy();
        testOnlyOneStaleRetiredPerTick();
        testOldestStaleRetiredFirst();
        testUnknownNullImageTreatedAsCurrentNotStale();
        testUnhealthyLogicIgnoresStaleImageContainers();
        testOrdinaryScaleDownSkippedWhileRollingDeployInProgress();
        testFullRollingDeploySequenceReplicasOne();
        testOrphanCleanupStopsStaleImageContainersToo();

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
                new ManagedContainer("c1", "web", 1000L, HealthStatus.HEALTHY, "nginx:latest"));
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(spec), Map.of("web", running));

        check("scale up from 1 to 3: exactly 2 StartContainer actions",
                actions.size() == 2
                        && actions.stream().allMatch(a -> a instanceof ReconcileAction.StartContainer));
    }

    private static void testScaleDownStopsNewestFirst() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("oldest", "web", 1000L, HealthStatus.HEALTHY, "nginx:latest"),
                new ManagedContainer("middle", "web", 2000L, HealthStatus.HEALTHY, "nginx:latest"),
                new ManagedContainer("newest", "web", 3000L, HealthStatus.HEALTHY, "nginx:latest"));
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
                new ManagedContainer("c1", "web", 1000L, HealthStatus.HEALTHY, "nginx:latest"),
                new ManagedContainer("c2", "web", 2000L, HealthStatus.HEALTHY, "nginx:latest"));
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(spec), Map.of("web", running));

        check("already converged (2 desired, 2 running): zero actions",
                actions.isEmpty());
    }

    private static void testMultipleServicesIndependent() {
        ServiceSpec web = new ServiceSpec("web", "nginx:latest", 2, 80, null, "/health", Map.of());
        ServiceSpec worker = new ServiceSpec("worker", "worker:latest", 1, null, null, "/health", Map.of());

        Map<String, List<ManagedContainer>> actual = Map.of(
                "web", List.of(new ManagedContainer("w1", "web", 1000L, HealthStatus.HEALTHY, "nginx:latest")),
                "worker", List.of(
                        new ManagedContainer("k1", "worker", 1000L, HealthStatus.HEALTHY, "worker:latest"),
                        new ManagedContainer("k2", "worker", 2000L, HealthStatus.HEALTHY, "worker:latest"))
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
                "web", List.of(new ManagedContainer("w1", "web", 1000L, HealthStatus.HEALTHY, "nginx:latest")),
                "legacy-service", List.of(
                        new ManagedContainer("l1", "legacy-service", 500L, HealthStatus.HEALTHY, "legacy:v1"),
                        new ManagedContainer("l2", "legacy-service", 600L, HealthStatus.HEALTHY, "legacy:v1"))
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
                new ManagedContainer("w1", "web", 1000L, HealthStatus.HEALTHY, "nginx:latest"),
                new ManagedContainer("w2", "web", 2000L, HealthStatus.HEALTHY, "nginx:latest"));

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
                new ManagedContainer("bad", "web", 1000L, HealthStatus.UNHEALTHY, "nginx:latest"));

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
                new ManagedContainer("good", "web", 1000L, HealthStatus.HEALTHY, "nginx:latest"),
                new ManagedContainer("bad", "web", 2000L, HealthStatus.UNHEALTHY, "nginx:latest"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        long starts = result.actions().stream().filter(a -> a instanceof ReconcileAction.StartContainer).count();
        long stops = result.actions().stream().filter(a -> a instanceof ReconcileAction.StopContainer).count();

        check("desired=2, one healthy + one unhealthy: stop the bad one, start exactly 1 replacement "
                + "(not 2 -- the healthy one still counts)", starts == 1 && stops == 1);
    }

    private static void testStartingStatusCountsAsGoodEnough() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("booting", "web", 1000L, HealthStatus.STARTING, "nginx:latest"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        check("a STARTING container counts toward desired replicas and is never restarted",
                result.actions().isEmpty());
    }

    private static void testNoneStatusCountsAsGoodEnough() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("no-healthcheck", "web", 1000L, HealthStatus.NONE, "nginx:latest"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        check("a container with no HEALTHCHECK declared (NONE) is treated as fine, "
                + "matching Phase 2's original running-is-good behavior", result.actions().isEmpty());
    }

    private static void testHealthyMixedWithUnhealthyOnlyReplacesTheUnhealthyOne() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 3, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("h1", "web", 1000L, HealthStatus.HEALTHY, "nginx:latest"),
                new ManagedContainer("h2", "web", 2000L, HealthStatus.HEALTHY, "nginx:latest"),
                new ManagedContainer("bad", "web", 3000L, HealthStatus.UNHEALTHY, "nginx:latest"));

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
                        new ManagedContainer("x1", "removed-service", 500L, HealthStatus.UNHEALTHY, "img:v1"))
        );
        ReconcileResult result = Reconciler.reconcile(List.of(), actual, Map.of(), 10_000L);

        check("orphan cleanup stops containers regardless of health status",
                result.actions().size() == 1
                        && result.actions().get(0) instanceof ReconcileAction.StopContainer);
    }

    private static void testOrphanCleanupRemovesRestartState() {
        Map<String, List<ManagedContainer>> actual = Map.of(
                "removed-service", List.of(
                        new ManagedContainer("x1", "removed-service", 500L, HealthStatus.HEALTHY, "img:v1"))
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

        List<ManagedContainer> tick1Running = List.of(
                new ManagedContainer("bad-1", "web", 1000L, HealthStatus.UNHEALTHY, "nginx:latest"));
        ReconcileResult tick1 = Reconciler.reconcile(List.of(spec), Map.of("web", tick1Running), Map.of(), 10_000L);

        boolean tick1StoppedIt = tick1.actions().stream().anyMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("bad-1"));
        boolean tick1StartedReplacement = tick1.actions().stream()
                .anyMatch(a -> a instanceof ReconcileAction.StartContainer);

        List<ManagedContainer> tick2Running = List.of(
                new ManagedContainer("bad-2", "web", 10_500L, HealthStatus.UNHEALTHY, "nginx:latest"));
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
                new ManagedContainer("still-bad", "web", 1000L, HealthStatus.UNHEALTHY, "nginx:latest"));

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

        List<ManagedContainer> running = List.of(
                new ManagedContainer("recovered", "web", 6000L, HealthStatus.HEALTHY, "nginx:latest"));

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

    // -------------------------------------------------------------
    // Phase 4: rolling deploys
    // -------------------------------------------------------------

    private static void testStaleImageTriggersNewReplicaStart() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean startedNew = result.actions().stream().anyMatch(a -> a instanceof ReconcileAction.StartContainer);
        boolean didNotStopOldYet = result.actions().stream().noneMatch(a -> a instanceof ReconcileAction.StopContainer);

        check("spec image changed (v1 -> v2): a new v2 replica starts immediately, "
                + "old v1 replica is NOT touched yet (no confirmed-healthy replacement exists)",
                startedNew && didNotStopOldYet);
    }

    private static void testStaleImageNotRetiredUntilReplacementConfirmedHealthy() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());
        // Old replica still healthy; a v2 replacement exists but is UNHEALTHY
        // (e.g. a bad new version) -- must NOT retire the working old one.
        List<ManagedContainer> running = List.of(
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("new-1", "web", 2000L, HealthStatus.UNHEALTHY, "app:v2"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean oldNotRetired = result.actions().stream().noneMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("old-1"));

        check("a v2 replacement that is itself UNHEALTHY never causes the still-working "
                + "old replica to be retired -- correctness over completing the rollout on schedule",
                oldNotRetired);
    }

    private static void testStartingReplacementNotEnoughToRetireStale() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("new-1", "web", 2000L, HealthStatus.STARTING, "app:v2"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean oldNotRetired = result.actions().stream().noneMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("old-1"));

        check("a v2 replacement that is only STARTING (not yet confirmed healthy) does NOT "
                + "cause the old replica to be retired -- this is the actual zero-downtime guarantee",
                oldNotRetired);
    }

    private static void testStaleRetiredOncePassedHealthy() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("new-1", "web", 2000L, HealthStatus.HEALTHY, "app:v2"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean oldRetired = result.actions().stream().anyMatch(a ->
                a instanceof ReconcileAction.StopContainer sc
                        && sc.containerId().equals("old-1")
                        && sc.reason() == StopReason.ROLLING_DEPLOY);
        boolean noExtraStart = result.actions().stream().noneMatch(a -> a instanceof ReconcileAction.StartContainer);

        check("once the v2 replacement is genuinely HEALTHY, the old v1 replica IS retired "
                + "(reason: ROLLING_DEPLOY), and no further replicas are started (already at desired count)",
                oldRetired && noExtraStart);
    }

    private static void testOnlyOneStaleRetiredPerTick() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 3, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("old-2", "web", 1100L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("old-3", "web", 1200L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("new-1", "web", 2000L, HealthStatus.HEALTHY, "app:v2"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        long staleStops = result.actions().stream()
                .filter(a -> a instanceof ReconcileAction.StopContainer sc && sc.reason() == StopReason.ROLLING_DEPLOY)
                .count();

        check("3 old replicas still present, only ONE confirmed-healthy new replica so far: "
                + "exactly one old replica is retired this tick, not all three at once", staleStops == 1);
    }

    private static void testOldestStaleRetiredFirst() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("old-newer", "web", 2000L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("old-oldest", "web", 1000L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("new-1", "web", 3000L, HealthStatus.HEALTHY, "app:v2"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean retiredOldest = result.actions().stream().anyMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("old-oldest"));
        boolean newerSurvivedThisTick = result.actions().stream().noneMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("old-newer"));

        check("when multiple stale replicas exist, the OLDEST one is retired first",
                retiredOldest && newerSurvivedThisTick);
    }

    private static void testUnknownNullImageTreatedAsCurrentNotStale() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());
        // Simulates a container started by a pre-Phase-4 orchestrator --
        // no image label was ever set, so ManagedContainer.image() is null.
        List<ManagedContainer> running = List.of(
                new ManagedContainer("legacy-unlabeled", "web", 1000L, HealthStatus.HEALTHY, null));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        check("a container with no image label at all (null) is treated as CURRENT, not stale -- "
                + "no surprise rolling replacement of pre-Phase-4 containers", result.actions().isEmpty());
    }

    private static void testUnhealthyLogicIgnoresStaleImageContainers() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());
        // The STALE (v1) container is unhealthy -- this must NOT trigger
        // Phase 3's restart/backoff machinery; it's handled by rolling
        // retirement instead, once a healthy v2 replacement exists.
        List<ManagedContainer> running = List.of(
                new ManagedContainer("old-unhealthy", "web", 1000L, HealthStatus.UNHEALTHY, "app:v1"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean startedNewV2 = result.actions().stream().anyMatch(a -> a instanceof ReconcileAction.StartContainer);
        boolean oldNotStoppedForHealthReason = result.actions().stream().noneMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.reason() == StopReason.UNHEALTHY);

        check("an unhealthy STALE-image container doesn't trigger Phase 3's health/backoff path -- "
                + "only a v2 replica is started; the old one is left for rolling retirement, not restart logic",
                startedNewV2 && oldNotStoppedForHealthReason);
    }

    private static void testOrdinaryScaleDownSkippedWhileRollingDeployInProgress() {
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());
        // 2 healthy v2 replicas already exist (over-replicated relative to
        // desired=1) WHILE a v1 straggler is also still present. Ordinary
        // scale-down must NOT kick in and remove one of the new replicas.
        List<ManagedContainer> running = List.of(
                new ManagedContainer("new-1", "web", 2000L, HealthStatus.HEALTHY, "app:v2"),
                new ManagedContainer("new-2", "web", 2100L, HealthStatus.HEALTHY, "app:v2"),
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"));

        ReconcileResult result = Reconciler.reconcile(List.of(spec), Map.of("web", running), Map.of(), 10_000L);

        boolean noScaleDownOfNewReplicas = result.actions().stream().noneMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.reason() == StopReason.SCALE_DOWN);
        boolean oldRetiredViaRollingDeploy = result.actions().stream().anyMatch(a ->
                a instanceof ReconcileAction.StopContainer sc
                        && sc.containerId().equals("old-1")
                        && sc.reason() == StopReason.ROLLING_DEPLOY);

        check("over-provisioned on the NEW image while a stale straggler remains: "
                + "ordinary scale-down is skipped (new replicas aren't prematurely removed), "
                + "the stale one is retired via rolling deploy instead",
                noScaleDownOfNewReplicas && oldRetiredViaRollingDeploy);
    }

    private static void testFullRollingDeploySequenceReplicasOne() {
        // Simulates the full, real zero-downtime sequence for a
        // single-replica service across several ticks.
        ServiceSpec spec = new ServiceSpec("web", "app:v2", 1, 80, null, "/health", Map.of());

        // Tick 1: only the old v1 replica exists.
        List<ManagedContainer> tick1 = List.of(
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"));
        ReconcileResult r1 = Reconciler.reconcile(List.of(spec), Map.of("web", tick1), Map.of(), 10_000L);
        boolean tick1StartsNew = r1.actions().stream().anyMatch(a -> a instanceof ReconcileAction.StartContainer);
        boolean tick1KeepsOld = r1.actions().stream().noneMatch(a -> a instanceof ReconcileAction.StopContainer);

        // Tick 2: the new v2 replica exists but is still STARTING.
        List<ManagedContainer> tick2 = List.of(
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("new-1", "web", 10_100L, HealthStatus.STARTING, "app:v2"));
        ReconcileResult r2 = Reconciler.reconcile(List.of(spec), Map.of("web", tick2), r1.restartState(), 15_000L);
        boolean tick2StillKeepsOld = r2.actions().isEmpty();

        // Tick 3: the new v2 replica has now passed its healthcheck.
        List<ManagedContainer> tick3 = List.of(
                new ManagedContainer("old-1", "web", 1000L, HealthStatus.HEALTHY, "app:v1"),
                new ManagedContainer("new-1", "web", 10_100L, HealthStatus.HEALTHY, "app:v2"));
        ReconcileResult r3 = Reconciler.reconcile(List.of(spec), Map.of("web", tick3), r2.restartState(), 20_000L);
        boolean tick3RetiresOld = r3.actions().stream().anyMatch(a ->
                a instanceof ReconcileAction.StopContainer sc && sc.containerId().equals("old-1"));

        // Tick 4: old one is gone, only the healthy new one remains -- converged.
        List<ManagedContainer> tick4 = List.of(
                new ManagedContainer("new-1", "web", 10_100L, HealthStatus.HEALTHY, "app:v2"));
        ReconcileResult r4 = Reconciler.reconcile(List.of(spec), Map.of("web", tick4), r3.restartState(), 25_000L);
        boolean tick4Converged = r4.actions().isEmpty();

        check("full rolling-deploy sequence, tick 1: starts new v2 replica, keeps old v1 running",
                tick1StartsNew && tick1KeepsOld);
        check("full rolling-deploy sequence, tick 2: new replica only STARTING -> old still kept, no actions",
                tick2StillKeepsOld);
        check("full rolling-deploy sequence, tick 3: new replica now HEALTHY -> old v1 retired",
                tick3RetiresOld);
        check("full rolling-deploy sequence, tick 4: fully converged on v2, zero actions",
                tick4Converged);
    }

    private static void testOrphanCleanupStopsStaleImageContainersToo() {
        Map<String, List<ManagedContainer>> actual = Map.of(
                "removed-service", List.of(
                        new ManagedContainer("x1", "removed-service", 500L, HealthStatus.HEALTHY, "old:v1"))
        );
        ReconcileResult result = Reconciler.reconcile(List.of(), actual, Map.of(), 10_000L);

        check("orphan cleanup stops containers regardless of image identity too",
                result.actions().size() == 1
                        && result.actions().get(0) instanceof ReconcileAction.StopContainer);
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
