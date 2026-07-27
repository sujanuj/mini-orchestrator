package com.sujanuj.orchestrator.core;

/**
 * A container's health, as reported by Docker's own HEALTHCHECK
 * mechanism (already declared in both services' Dockerfiles since
 * Phase 1). This is the missing signal Phase 2 never consulted: Phase
 * 2's reconciliation only asked "is this container RUNNING," which
 * can't distinguish a genuinely working replica from one that's still
 * alive as a process but internally broken (deadlocked, its dependency
 * connection pool exhausted, stuck in a loop) -- exactly the case a
 * HEALTHCHECK exists to catch.
 */
public enum HealthStatus {
    /** Docker's HEALTHCHECK is passing. */
    HEALTHY,

    /** Docker's HEALTHCHECK is failing. This is the ONLY status that
     * triggers a restart -- see Reconciler. */
    UNHEALTHY,

    /** Still within the Dockerfile's HEALTHCHECK start_period (15s for
     * both services here) -- Docker itself hasn't finished giving the
     * container a chance to boot yet. Treated as "good enough to count
     * toward desired replicas" but never restarted, since restarting
     * something that hasn't even finished starting would fight against
     * its own startup instead of waiting for it. */
    STARTING,

    /** The image has no HEALTHCHECK declared at all (Health is null on
     * the Docker API response). Treated the same as HEALTHY -- the
     * orchestrator has no basis to distinguish "fine" from "broken" for
     * such a container, so it falls back to Phase 2's original
     * behavior (running == good) for it specifically. */
    NONE
}
