package com.sujanuj.orchestrator.core;

import java.util.List;
import java.util.Map;

/**
 * A plain-Java self-test harness for Reconciler -- no JUnit, no test
 * framework, deliberately, since the point is to verify this logic is
 * correct using only what's guaranteed to be available (a JDK), the
 * same way the rest of Phase 2's pure core has zero external
 * dependencies. Run with: javac then java ReconcilerSelfTest.
 */
public final class ReconcilerSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testScaleUpFromZero();
        testScaleUpPartial();
        testScaleDownStopsNewestFirst();
        testAlreadyConverged();
        testMultipleServicesIndependent();
        testOrphanCleanup();
        testScaleToZeroKeepsServiceInDesiredButStopsAll();
        testEmptyDesiredAndEmptyActual();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

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
                new ManagedContainer("c1", "web", 1000L));
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(spec), Map.of("web", running));

        check("scale up from 1 to 3: exactly 2 StartContainer actions",
                actions.size() == 2
                        && actions.stream().allMatch(a -> a instanceof ReconcileAction.StartContainer));
    }

    private static void testScaleDownStopsNewestFirst() {
        ServiceSpec spec = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        // three running, started at different times; desired is 1, so 2 must stop
        List<ManagedContainer> running = List.of(
                new ManagedContainer("oldest", "web", 1000L),
                new ManagedContainer("middle", "web", 2000L),
                new ManagedContainer("newest", "web", 3000L));
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
                new ManagedContainer("c1", "web", 1000L),
                new ManagedContainer("c2", "web", 2000L));
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(spec), Map.of("web", running));

        check("already converged (2 desired, 2 running): zero actions",
                actions.isEmpty());
    }

    private static void testMultipleServicesIndependent() {
        ServiceSpec web = new ServiceSpec("web", "nginx:latest", 2, 80, null, "/health", Map.of());
        ServiceSpec worker = new ServiceSpec("worker", "worker:latest", 1, null, null, "/health", Map.of());

        Map<String, List<ManagedContainer>> actual = Map.of(
                "web", List.of(new ManagedContainer("w1", "web", 1000L)), // needs +1
                "worker", List.of(
                        new ManagedContainer("k1", "worker", 1000L),
                        new ManagedContainer("k2", "worker", 2000L))       // needs -1
        );

        List<ReconcileAction> actions = Reconciler.reconcile(List.of(web, worker), actual);

        long starts = actions.stream().filter(a -> a instanceof ReconcileAction.StartContainer).count();
        long stops = actions.stream().filter(a -> a instanceof ReconcileAction.StopContainer).count();

        check("two independent services converge simultaneously: 1 start (web) + 1 stop (worker)",
                starts == 1 && stops == 1 && actions.size() == 2);
    }

    private static void testOrphanCleanup() {
        // "web" is desired; "legacy-service" has running containers but
        // is no longer in the desired spec at all -- simulating a
        // service that was deleted from the spec file entirely.
        ServiceSpec web = new ServiceSpec("web", "nginx:latest", 1, 80, null, "/health", Map.of());
        Map<String, List<ManagedContainer>> actual = Map.of(
                "web", List.of(new ManagedContainer("w1", "web", 1000L)), // already converged
                "legacy-service", List.of(
                        new ManagedContainer("l1", "legacy-service", 500L),
                        new ManagedContainer("l2", "legacy-service", 600L))
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
        // Distinguishes "desired at 0 replicas" (still in the spec,
        // explicitly scaled down) from orphan cleanup (not in the spec
        // at all) -- both should stop every running container, but
        // they're different code paths (Part 1 vs Part 2 of
        // Reconciler.reconcile), so worth testing both explicitly.
        ServiceSpec web = new ServiceSpec("web", "nginx:latest", 0, 80, null, "/health", Map.of());
        List<ManagedContainer> running = List.of(
                new ManagedContainer("w1", "web", 1000L),
                new ManagedContainer("w2", "web", 2000L));

        List<ReconcileAction> actions = Reconciler.reconcile(List.of(web), Map.of("web", running));

        check("service explicitly desired at 0 replicas: stops all running containers",
                actions.size() == 2
                        && actions.stream().allMatch(a -> a instanceof ReconcileAction.StopContainer));
    }

    private static void testEmptyDesiredAndEmptyActual() {
        List<ReconcileAction> actions = Reconciler.reconcile(List.of(), Map.of());
        check("nothing desired, nothing running: zero actions, no crash", actions.isEmpty());
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
