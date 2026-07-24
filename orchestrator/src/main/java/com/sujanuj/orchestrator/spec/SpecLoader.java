package com.sujanuj.orchestrator.spec;

import com.sujanuj.orchestrator.core.ServiceSpec;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the declarative spec file (a flat list of services under a
 * top-level `services:` key) into ServiceSpec records. SnakeYAML's
 * Yaml.load() on a plain document like this returns nested
 * Map&lt;String,Object&gt;/List&lt;Object&gt; of Java primitives -- this class's
 * only real job is walking that generic structure and turning it into
 * strongly-typed, validated ServiceSpec objects with clear error
 * messages when something's missing or the wrong shape, rather than
 * letting a ClassCastException from deep inside SnakeYAML's output
 * surface as the first thing a user sees.
 *
 * Example spec file shape (see spec.example.yaml):
 *
 * services:
 *   - name: inventory-service
 *     image: mini-orchestrator-inventory-service:latest
 *     replicas: 1
 *     containerPort: 8080
 *     hostPort: 8081
 *     healthPath: /health
 *     env:
 *       SPRING_PROFILES_ACTIVE: docker
 */
public final class SpecLoader {

    private SpecLoader() {}

    public static List<ServiceSpec> load(String path) throws IOException {
        try (InputStream in = new FileInputStream(path)) {
            return load(in, path);
        }
    }

    @SuppressWarnings("unchecked")
    static List<ServiceSpec> load(InputStream in, String sourceForErrors) {
        Yaml yaml = new Yaml();
        Object root = yaml.load(in);

        if (!(root instanceof Map)) {
            throw new SpecParseException(
                    sourceForErrors + ": expected a YAML mapping at the top level, got " + describe(root));
        }
        Map<String, Object> rootMap = (Map<String, Object>) root;

        Object servicesRaw = rootMap.get("services");
        if (servicesRaw == null) {
            throw new SpecParseException(sourceForErrors + ": missing required top-level key 'services'");
        }
        if (!(servicesRaw instanceof List)) {
            throw new SpecParseException(
                    sourceForErrors + ": 'services' must be a list, got " + describe(servicesRaw));
        }

        List<Object> servicesList = (List<Object>) servicesRaw;
        List<ServiceSpec> result = new ArrayList<>();
        List<String> seenNames = new ArrayList<>();

        for (int i = 0; i < servicesList.size(); i++) {
            Object entry = servicesList.get(i);
            if (!(entry instanceof Map)) {
                throw new SpecParseException(sourceForErrors + ": services[" + i
                        + "] must be a mapping, got " + describe(entry));
            }
            ServiceSpec spec = parseOne((Map<String, Object>) entry, sourceForErrors, i);

            if (seenNames.contains(spec.name())) {
                throw new SpecParseException(sourceForErrors + ": duplicate service name '"
                        + spec.name() + "' -- every service in the spec must have a unique name");
            }
            seenNames.add(spec.name());
            result.add(spec);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static ServiceSpec parseOne(Map<String, Object> m, String sourceForErrors, int index) {
        String where = sourceForErrors + ": services[" + index + "]";

        String name = requireString(m, "name", where);
        String image = requireString(m, "image", where + " (name='" + name + "')");
        int replicas = requireInt(m, "replicas", where + " (name='" + name + "')");

        Integer containerPort = optionalInt(m, "containerPort");
        Integer hostPort = optionalInt(m, "hostPort");
        String healthPath = (String) m.getOrDefault("healthPath", "/health");

        Map<String, String> env = Map.of();
        Object envRaw = m.get("env");
        if (envRaw != null) {
            if (!(envRaw instanceof Map)) {
                throw new SpecParseException(where + ": 'env' must be a mapping, got " + describe(envRaw));
            }
            Map<Object, Object> envMap = (Map<Object, Object>) envRaw;
            Map<String, String> converted = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : envMap.entrySet()) {
                converted.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            env = converted;
        }

        // ServiceSpec's own compact constructor re-validates name/image/replicas
        // regardless of this loader's checks -- that's intentional
        // defense in depth, not redundancy: ServiceSpec must stay valid
        // even if constructed some other way in the future (e.g. a test
        // building one directly), not only when it comes through this
        // loader.
        return new ServiceSpec(name, image, replicas, containerPort, hostPort, healthPath, env);
    }

    private static String requireString(Map<String, Object> m, String key, String where) {
        Object v = m.get(key);
        if (v == null) {
            throw new SpecParseException(where + ": missing required field '" + key + "'");
        }
        return String.valueOf(v);
    }

    private static int requireInt(Map<String, Object> m, String key, String where) {
        Object v = m.get(key);
        if (v == null) {
            throw new SpecParseException(where + ": missing required field '" + key + "'");
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw new SpecParseException(where + ": field '" + key + "' must be a number, got " + describe(v));
    }

    private static Integer optionalInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw new SpecParseException("field '" + key + "' must be a number, got " + describe(v));
    }

    private static String describe(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName() + " (" + o + ")";
    }

    public static final class SpecParseException extends RuntimeException {
        public SpecParseException(String message) {
            super(message);
        }
    }
}
