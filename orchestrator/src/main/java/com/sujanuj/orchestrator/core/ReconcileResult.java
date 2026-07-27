package com.sujanuj.orchestrator.core;

import java.util.List;
import java.util.Map;

/**
 * What one reconciliation tick produced: the actions to actually
 * perform, and the updated per-service restart-backoff state to carry
 * into the NEXT tick. Bundling both in one return value (rather than
 * Reconciler mutating a Map passed in by reference) is what keeps
 * reconcile() a pure function -- see RestartState's comment for why
 * that matters.
 */
public record ReconcileResult(List<ReconcileAction> actions, Map<String, RestartState> restartState) {
}
