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
 */
public record ManagedContainer(
        String containerId,
        String serviceName,
        long startedAtEpochMillis
) {
}
