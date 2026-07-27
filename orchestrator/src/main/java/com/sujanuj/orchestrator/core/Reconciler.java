package com.sujanuj.orchestrator.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The actual core of an orchestrator: compare desired state to observed
 * actual state, and decide what has to change to close the gap. This is
 * the same idea every real controller (Kubernetes' ReplicaSet
 * controller included) is built around -- it's just usually hidden
 * behind a lot more machinery.
 *
 * Deliberately a pure function with no side effects, no Docker calls,
 * no I/O of any kind: reconcile() takes plain data in, returns plain
 * data out. This is what makes it possible to actually test the
 * decision-making with zero external dependencies -- no Docker daemon
 * running, no network access, just plain javac/java. The layer that
 * turns these decisions into real docker-java API calls
 * (com.sujanuj.orchestrator.docker.DockerActuator) is a separate,
 * thin, deliberately "dumb" translation step.
 *
 * Health-aware since Phase 3: a container counting as "running" is no
 * longer sufficient to count toward a service's desired replicas --
 * see HealthStatus and RestartState. "Restarting" an unhealthy
 * container isn't a distinct kind of action; it's implemented as
 * stopping the bad container and letting the ordinary scale-up path
 * (which now counts only non-UNHEALTHY containers) naturally start its
 * replacement, subject to exponential backoff so a persistently
 * crash-looping image doesn't get thrashed every single tick.
 */
public final class Reconciler {

    private Reconciler() {}

    /**
     * @param desired      every service the spec currently wants running
     * @param actualByName currently-observed, currently-RUNNING managed
     *                     containers, grouped by the service name
     *                     recorded on their orchestrator label. A
     *                     service with zero running containers may
     *                     simply be absent from this map's keys -- see
     *                     the empty-list fallback below.
     * @param restartState per-service backoff state from the previous
     *                     tick (empty map on the very first tick). See
     *                     RestartState for what this tracks and why.
     * @param nowEpochMillis the current time, passed in explicitly
     *                     (rather than read via System.currentTimeMillis()
     *                     inside this method) specifically so backoff
     *                     timing is deterministically testable -- a test
     *                     can simulate "5 seconds later" by passing a
     *                     different nowEpochMillis, without a real clock
     *                     or a Thread.sleep() anywhere in the test suite.
     * @return the actions needed this tick, plus the updated backoff
     *         state to carry into the next tick.
     */
    public static ReconcileResult reconcile(
            List<ServiceSpec> desired,
            Map<String, List<ManagedContainer>> actualByName,
            Map<String, RestartState> restartState,
            long nowEpochMillis) {

        List<ReconcileAction> actions = new ArrayList<>();
        Map<String, RestartState> updatedRestartState = new HashMap<>(restartState);

        // --- Part 1: for every DESIRED service, converge its replica count,
        // now health-aware. ---
        for (ServiceSpec spec : desired) {
            List<ManagedContainer> running = actualByName.getOrDefault(spec.name(), List.of());

            List<ManagedContainer> unhealthy = running.stream()
                    .filter(c -> c.health() == HealthStatus.UNHEALTHY)
                    .toList();
            // HEALTHY, STARTING, and NONE all count as "good enough" --
            // see HealthStatus for why STARTING and NONE aren't treated
            // as failures.
            List<ManagedContainer> healthyEnough = running.stream()
                    .filter(c -> c.health() != HealthStatus.UNHEALTHY)
                    .toList();

            RestartState state = restartState.getOrDefault(spec.name(), RestartState.FRESH);

            if (!unhealthy.isEmpty()) {
                if (state.canRestartNow(nowEpochMillis)) {
                    // Stop every currently-unhealthy container for this
                    // service. Deliberately NOT emitting a StartContainer
                    // here directly -- the deficit calculation just below
                    // (against healthyEnough.size(), which already
                    // excludes these) naturally emits exactly the right
                    // number of replacements, the same code path as an
                    // ordinary scale-up. Restarting an unhealthy
                    // container is just "stop the bad one, let the
                    // normal deficit logic replace it" -- not a
                    // special-cased third kind of action.
                    for (ManagedContainer bad : unhealthy) {
                        actions.add(new ReconcileAction.StopContainer(
                                bad.containerId(), spec.name(), StopReason.UNHEALTHY));
                    }
                    updatedRestartState.put(spec.name(), state.afterRestart(nowEpochMillis));
                }
                // else: still within backoff for this service -- leave
                // the unhealthy container(s) running for now rather than
                // thrashing. They're already excluded from
                // healthyEnough, so the deficit logic below still starts
                // replacements to maintain desired capacity even while
                // the bad one sits there awaiting cleanup on a later
                // tick. This can briefly leave MORE containers running
                // than `replicas` (the good replacements plus the
                // not-yet-stopped bad one) -- a deliberate choice:
                // prefer momentarily having one extra unhealthy
                // container over having a capacity deficit while
                // replacing it.
            } else if (state.consecutiveFailures() > 0) {
                // No unhealthy containers this tick, and this service HAD
                // a failure history -- it's recovered. Reset backoff so a
                // FUTURE failure (a new, unrelated incident) is treated
                // as a fresh first failure, not as a continuation of an
                // old one that already healed.
                updatedRestartState.put(spec.name(), RestartState.FRESH);
            }

            int deficit = spec.replicas() - healthyEnough.size();

            if (deficit > 0) {
                for (int i = 0; i < deficit; i++) {
                    actions.add(new ReconcileAction.StartContainer(spec));
                }
            } else if (deficit < 0) {
                // Over-replicated: stop (-deficit) of the currently
                // healthy-enough containers, newest-first -- see the
                // original Phase 2 reasoning, unchanged: a just-started
                // container has had the least chance to do useful work
                // and is least likely to be mid-request under any future
                // load-balancing.
                List<ManagedContainer> sortedNewestFirst = new ArrayList<>(healthyEnough);
                sortedNewestFirst.sort(
                        Comparator.comparingLong(ManagedContainer::startedAtEpochMillis).reversed());

                int toStop = -deficit;
                for (int i = 0; i < toStop; i++) {
                    ManagedContainer victim = sortedNewestFirst.get(i);
                    actions.add(new ReconcileAction.StopContainer(
                            victim.containerId(), spec.name(), StopReason.SCALE_DOWN));
                }
            }
        }

        // --- Part 2: orphan cleanup -- unchanged from Phase 2. Every
        // container (healthy or not) for a service no longer in the
        // desired spec gets stopped, regardless of backoff state --
        // backoff only paces restarts of services we still want running;
        // a removed service should be cleaned up immediately. ---
        for (Map.Entry<String, List<ManagedContainer>> entry : actualByName.entrySet()) {
            String serviceName = entry.getKey();
            boolean stillDesired = desired.stream().anyMatch(s -> s.name().equals(serviceName));
            if (stillDesired) {
                continue;
            }
            for (ManagedContainer orphan : entry.getValue()) {
                actions.add(new ReconcileAction.StopContainer(
                        orphan.containerId(), serviceName, StopReason.ORPHANED));
            }
            updatedRestartState.remove(serviceName);
        }

        return new ReconcileResult(actions, Map.copyOf(updatedRestartState));
    }

    /**
     * Convenience overload for callers (and tests) that don't care about
     * health/backoff -- treats every observed container as healthy and
     * ignores restart state. This is exactly Phase 2's original
     * behavior, preserved so pure scale-up/scale-down/orphan-cleanup
     * logic can still be tested in isolation from health-awareness.
     */
    public static List<ReconcileAction> reconcile(
            List<ServiceSpec> desired, Map<String, List<ManagedContainer>> actualByName) {
        return reconcile(desired, actualByName, Map.of(), 0L).actions();
    }
}
