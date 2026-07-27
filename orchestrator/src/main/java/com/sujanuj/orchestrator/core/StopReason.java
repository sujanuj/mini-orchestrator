package com.sujanuj.orchestrator.core;

/**
 * Why a StopContainer action was decided, purely so the reconciliation
 * log (Main.logAction) can say something more useful than "stopping X"
 * -- distinguishing "this replica failed its health check" from "we
 * have more replicas than desired" from "this service was removed from
 * the spec entirely" is exactly the kind of thing an operator watching
 * the log needs to know at a glance, and none of the three cases should
 * look the same.
 */
public enum StopReason {
    /** Over-replicated: more running than desired, this one was chosen
     * (newest-first) to bring the count back down. */
    SCALE_DOWN,

    /** This container failed its Docker HEALTHCHECK and is being
     * replaced -- see Reconciler and RestartState. */
    UNHEALTHY,

    /** This service no longer appears in the desired spec at all. */
    ORPHANED
}
