package com.sujanuj.orchestrator.core;

/**
 * Per-service backoff state for restarting unhealthy containers.
 *
 * Why this exists at all: without it, a persistently-crash-looping
 * image would get stopped and replaced on every single 5-second
 * reconciliation tick, forever -- hammering the Docker daemon with
 * rapid create/destroy cycles for something that's never going to
 * recover on its own. Real orchestrators (Kubernetes' CrashLoopBackOff
 * is the canonical example) back off exponentially instead: restart
 * immediately the first time, then wait longer between each subsequent
 * attempt for the same persistently-failing service.
 *
 * Deliberately immutable and dependency-free, same as every other class
 * in this package -- Reconciler.reconcile() takes a Map of these in and
 * returns an updated Map out, rather than any class in this package
 * mutating hidden internal state. That's what keeps reconcile() a pure
 * function despite now needing to remember something between ticks: the
 * "memory" is explicit input/output, not a side effect.
 */
public record RestartState(int consecutiveFailures, long nextRetryAllowedAtEpochMillis) {

    public static final RestartState FRESH = new RestartState(0, 0L);

    /** Base delay before the FIRST backoff wait (after the first
     * failure, restarts are attempted immediately -- backoff only
     * kicks in from the second consecutive failure onward). */
    static final long BASE_DELAY_MILLIS = 5_000L;

    /** Ceiling so backoff doesn't grow unbounded for a service that's
     * been failing for a long time -- an operator should still see a
     * retry at least this often, not have it effectively stop trying. */
    static final long MAX_DELAY_MILLIS = 60_000L;

    /** Called when an unhealthy container for this service was just
     * stopped and is being replaced. Increments the failure count and
     * computes the next allowed retry time using exponential backoff,
     * capped at MAX_DELAY_MILLIS. */
    public RestartState afterRestart(long nowEpochMillis) {
        int newFailures = consecutiveFailures + 1;
        long delay = Math.min(BASE_DELAY_MILLIS * (1L << Math.min(newFailures, 10)), MAX_DELAY_MILLIS);
        return new RestartState(newFailures, nowEpochMillis + delay);
    }

    /** Whether a restart is currently allowed for this service, i.e.
     * whether we're past the backoff window from the last restart. */
    public boolean canRestartNow(long nowEpochMillis) {
        return nowEpochMillis >= nextRetryAllowedAtEpochMillis;
    }
}
