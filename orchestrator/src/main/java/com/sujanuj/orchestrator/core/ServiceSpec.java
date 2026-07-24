package com.sujanuj.orchestrator.core;

import java.util.Map;

/**
 * Desired state for one service: what image, how many replicas, and how
 * to run it. This is the orchestrator's equivalent of a Kubernetes
 * Deployment spec -- deliberately much smaller (no rolling-update
 * strategy fields yet, that's Phase 4; no resource limits; no volumes)
 * because Phase 2's whole job is "keep N replicas of this image
 * running," nothing more.
 *
 * Deliberately dependency-free -- no docker-java imports, no YAML
 * library imports. That's what lets Reconciler (which consumes this)
 * be testable with plain javac/java and no Docker daemon or Maven
 * Central access at all.
 */
public record ServiceSpec(
        String name,
        String image,
        int replicas,
        Integer containerPort,   // nullable: null means "no port exposed"
        Integer hostPort,        // nullable: null means "not published to the host"
        String healthPath,       // reserved for Phase 3; unused by Phase 2's reconciler
        Map<String, String> env  // never null; SpecLoader normalizes missing env to Map.of()
) {
    public ServiceSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("service name must not be blank");
        }
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("service '" + name + "': image must not be blank");
        }
        if (replicas < 0) {
            throw new IllegalArgumentException(
                    "service '" + name + "': replicas must be >= 0, got " + replicas);
        }
        if (env == null) {
            env = Map.of();
        }
    }
}
