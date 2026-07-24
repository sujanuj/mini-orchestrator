package com.sujanuj.orchestrator.core;

/**
 * What the reconciler decided needs to happen to move actual state
 * toward desired state. A sealed interface rather than a generic
 * "Action" enum-with-fields, specifically so DockerActuator's dispatch
 * (Phase 2's docker package) can be an exhaustive switch pattern-match
 * -- the compiler enforces that every kind of action is handled,
 * rather than a missed case silently doing nothing at runtime.
 */
public sealed interface ReconcileAction {

    /** Start one new replica of the named service. */
    record StartContainer(ServiceSpec spec) implements ReconcileAction {}

    /**
     * Stop and remove a specific existing container. Carries the
     * service name (not a full ServiceSpec) purely for logging --
     * "stopping widget-orders-service replica abc123" reads far better
     * in the reconciliation log than "stopping abc123." A plain String
     * rather than ServiceSpec specifically because orphan cleanup (a
     * service that used to be in the spec and no longer is -- see
     * Reconciler) has no ServiceSpec left to reference at all, only the
     * name recorded on the now-orphaned container's label.
     */
    record StopContainer(String containerId, String serviceName) implements ReconcileAction {}
}
