package com.sujanuj.orchestrator.core;

/**
 * One observed, currently-running container that the orchestrator
 * considers "its own" (i.e. it carries the orchestrator's management
 * label -- see DockerActuator). This is the actual-state counterpart to
 * ServiceSpec's desired state.
 *
 * startedAtEpochMillis exists specifically so Reconciler can decide
 * WHICH containers to stop when scaling down a service that currently
 * has more replicas than desired -- see Reconciler's scale-down
 * comment for why "stop the newest first" was chosen over the
 * alternative.
 *
 * health (added Phase 3) is what lets Reconciler distinguish a
 * genuinely working replica from one that's merely still running as a
 * process -- see HealthStatus for what each value means and why only
 * UNHEALTHY triggers a restart.
 *
 * image (added Phase 4) is the image reference this specific container
 * was actually started from, read back from a label DockerActuator sets
 * at creation time. This is what makes a rolling deploy detectable at
 * all: without it, Reconciler has no way to tell "this running container
 * is on the OLD image" from "this running container is already on the
 * image the spec currently wants." A null value means the label wasn't
 * present (a container started by a PRE-Phase-4 orchestrator, before
 * this label existed) -- see Reconciler for why that's deliberately
 * treated as "assume current," not "assume stale."
 */
public record ManagedContainer(
        String containerId,
        String serviceName,
        long startedAtEpochMillis,
        HealthStatus health,
        String image
) {
}

