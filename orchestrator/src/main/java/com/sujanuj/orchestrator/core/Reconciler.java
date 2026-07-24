package com.sujanuj.orchestrator.core;

import java.util.ArrayList;
import java.util.Comparator;
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
     * @return the ordered list of actions needed to converge actual
     *         toward desired. Order matters for readability of the
     *         reconciliation log, not for correctness -- every action
     *         in the returned list is independent of every other one.
     */
    public static List<ReconcileAction> reconcile(
            List<ServiceSpec> desired, Map<String, List<ManagedContainer>> actualByName) {

        List<ReconcileAction> actions = new ArrayList<>();

        // --- Part 1: for every DESIRED service, converge its replica count ---
        for (ServiceSpec spec : desired) {
            List<ManagedContainer> running = actualByName.getOrDefault(spec.name(), List.of());
            int deficit = spec.replicas() - running.size();

            if (deficit > 0) {
                // Under-replicated: start `deficit` new containers.
                // Each is an independent StartContainer action rather
                // than one action carrying a count, so the actuator
                // layer (and its logging) treats every container start
                // uniformly whether it's 1 of 1 or 1 of 10.
                for (int i = 0; i < deficit; i++) {
                    actions.add(new ReconcileAction.StartContainer(spec));
                }
            } else if (deficit < 0) {
                // Over-replicated: stop (-deficit) of the currently
                // running containers.
                //
                // Which ones to stop: newest-first. Reasoning -- a
                // container that was JUST started (e.g. by a scale-up
                // decision that's being immediately reversed on the
                // very next reconciliation tick, or by a human typo in
                // the spec that's about to be corrected) is the
                // container that's had the least chance to do useful
                // work and is least likely to be mid-request under any
                // future load-balancing. Stopping the oldest,
                // longest-running containers first would instead
                // preferentially kill whichever replicas have been
                // most stable -- the opposite of what a "prefer
                // stability, absorb churn on the newest" policy wants.
                // This is a real, named design choice, not an arbitrary
                // sort direction.
                List<ManagedContainer> sortedNewestFirst = new ArrayList<>(running);
                sortedNewestFirst.sort(
                        Comparator.comparingLong(ManagedContainer::startedAtEpochMillis).reversed());

                int toStop = -deficit;
                for (int i = 0; i < toStop; i++) {
                    ManagedContainer victim = sortedNewestFirst.get(i);
                    actions.add(new ReconcileAction.StopContainer(
                            victim.containerId(), spec.name()));
                }
            }
            // deficit == 0: already converged, nothing to do for this service.
        }

        // --- Part 2: orphan cleanup -- managed containers for a service
        // that no longer appears in the desired spec at all (removed
        // entirely, not just scaled to 0). Without this, a service
        // deleted from the spec file would leave its containers running
        // forever, since Part 1 only ever looks at services that ARE
        // still in `desired`. This is exactly the kind of gap a real
        // reconciliation loop has to close explicitly -- "not
        // mentioned" and "desired at zero replicas" are different
        // things, and only the orphan-cleanup pass here handles the
        // former. ---
        for (Map.Entry<String, List<ManagedContainer>> entry : actualByName.entrySet()) {
            String serviceName = entry.getKey();
            boolean stillDesired = desired.stream().anyMatch(s -> s.name().equals(serviceName));
            if (stillDesired) {
                continue; // already handled by Part 1, whether converged or not
            }
            for (ManagedContainer orphan : entry.getValue()) {
                actions.add(new ReconcileAction.StopContainer(orphan.containerId(), serviceName));
            }
        }

        return actions;
    }
}
