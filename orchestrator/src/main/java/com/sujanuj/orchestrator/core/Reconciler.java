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
 *
 * Image-aware since Phase 4: every observed container's own image is
 * compared against the spec's currently-desired image. A container on
 * an OLD image is never mistaken for a healthy current replica, no
 * matter how healthy IT is -- it's tracked separately as "stale" and
 * retired via a paced rolling replacement (see the per-service loop
 * below), not by the ordinary scale-down path. Health-awareness and
 * image-awareness compose: Phase 3's unhealthy/backoff logic runs only
 * within the current-image group, since a stale container's health is
 * irrelevant -- it's leaving regardless of whether its healthcheck
 * currently passes.
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
        // health-aware AND image-aware. ---
        for (ServiceSpec spec : desired) {
            List<ManagedContainer> running = actualByName.getOrDefault(spec.name(), List.of());

            // Partition by image identity first. A null image (no label --
            // a container started by a pre-Phase-4 orchestrator, before
            // this label existed) is deliberately treated as "current,"
            // not "stale": defaulting an unlabeled legacy container to
            // "must be replaced" would force a surprise rolling
            // replacement of every existing container the very first time
            // this version of the orchestrator runs against them, which
            // is a worse default than simply not knowing.
            List<ManagedContainer> currentImage = running.stream()
                    .filter(c -> c.image() == null || c.image().equals(spec.image()))
                    .toList();
            List<ManagedContainer> staleImage = running.stream()
                    .filter(c -> c.image() != null && !c.image().equals(spec.image()))
                    .toList();

            // --- Health/backoff (Phase 3), operating ONLY on the
            // current-image group. A stale container's health is
            // irrelevant to this decision -- it's being retired below
            // regardless of whether its healthcheck currently passes. ---
            List<ManagedContainer> unhealthy = currentImage.stream()
                    .filter(c -> c.health() == HealthStatus.UNHEALTHY)
                    .toList();
            List<ManagedContainer> healthyEnough = currentImage.stream()
                    .filter(c -> c.health() != HealthStatus.UNHEALTHY)
                    .toList();

            RestartState state = restartState.getOrDefault(spec.name(), RestartState.FRESH);

            if (!unhealthy.isEmpty()) {
                if (state.canRestartNow(nowEpochMillis)) {
                    for (ManagedContainer bad : unhealthy) {
                        actions.add(new ReconcileAction.StopContainer(
                                bad.containerId(), spec.name(), StopReason.UNHEALTHY));
                    }
                    updatedRestartState.put(spec.name(), state.afterRestart(nowEpochMillis));
                }
            } else if (state.consecutiveFailures() > 0) {
                updatedRestartState.put(spec.name(), RestartState.FRESH);
            }

            int deficit = spec.replicas() - healthyEnough.size();

            if (deficit > 0) {
                for (int i = 0; i < deficit; i++) {
                    actions.add(new ReconcileAction.StartContainer(spec));
                }
            } else if (deficit < 0 && staleImage.isEmpty()) {
                // Ordinary over-replicated scale-down, newest-first --
                // unchanged from Phase 2/3. Deliberately gated on
                // staleImage being empty: mid-rollout, currentImage
                // legitimately exceeding `replicas` is expected and
                // temporary (new replicas surging in while old ones are
                // still being retired below), not something to correct
                // by removing brand-new replacements.
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

            // --- Rolling retirement of stale-image containers (Phase 4).
            // Paced at ONE retirement per tick, and only once at least one
            // CURRENT-image replacement is CONFIRMED healthy -- not merely
            // STARTING. This is the entire point of a rolling deploy being
            // zero-downtime: retiring an old, working replica the instant
            // a replacement merely begins starting (before its healthcheck
            // has actually passed) could momentarily leave less healthy
            // capacity than before the deploy began, which defeats the
            // purpose. STARTING counting as "good enough" for ordinary
            // scale-up (above) and STARTING NOT counting as "ready to
            // retire the old one for" here are deliberately different
            // bars for deliberately different decisions. ---
            if (!staleImage.isEmpty()) {
                boolean hasConfirmedHealthyReplacement = currentImage.stream()
                        .anyMatch(c -> c.health() == HealthStatus.HEALTHY || c.health() == HealthStatus.NONE);
                if (hasConfirmedHealthyReplacement) {
                    List<ManagedContainer> sortedStaleOldestFirst = new ArrayList<>(staleImage);
                    sortedStaleOldestFirst.sort(
                            Comparator.comparingLong(ManagedContainer::startedAtEpochMillis));
                    ManagedContainer retiring = sortedStaleOldestFirst.get(0);
                    actions.add(new ReconcileAction.StopContainer(
                            retiring.containerId(), spec.name(), StopReason.ROLLING_DEPLOY));
                }
                // else: no confirmed-healthy replacement exists yet --
                // retire nothing this tick. The deficit branch above will
                // have already started new-image replicas if needed; wait
                // for one of them to pass health before touching anything
                // still serving on the old image.
            }
        }

        // --- Part 2: orphan cleanup -- unchanged from Phase 2/3. Every
        // container (healthy or not, current-image or stale) for a
        // service no longer in the desired spec gets stopped, regardless
        // of backoff state -- backoff only paces restarts of services we
        // still want running; a removed service should be cleaned up
        // immediately. ---
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
     * health/backoff/image-tracking -- treats every observed container
     * as healthy and on the current image, and ignores restart state.
     * This is exactly Phase 2's original behavior, preserved so pure
     * scale-up/scale-down/orphan-cleanup logic can still be tested in
     * isolation.
     */
    public static List<ReconcileAction> reconcile(
            List<ServiceSpec> desired, Map<String, List<ManagedContainer>> actualByName) {
        return reconcile(desired, actualByName, Map.of(), 0L).actions();
    }
}
