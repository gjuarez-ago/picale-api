package com.metricol.api.service.assistant;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Caché efímera de lecturas de UN turno. No contiene confirmaciones ni resultados de escrituras. */
final class AssistantReadCache {
    private static final Set<String> READS = Set.of("marca", "identidad_visual", "panel", "ver_archivo",
            "cuenta_actual", "contenido", "publicaciones", "ver_publicacion");
    private static final long TTL = Duration.ofSeconds(30).toNanos();
    private static final int MAX_BYTES = 8_000_000;
    private final UUID workspace;
    private final UUID user;
    private final Set<?> permissions;
    private final LongSupplier clock;
    private final Map<String, Entry> values = new HashMap<>();
    private int bytes;
    private record Entry(JsonNode value, long at, int size) {}

    AssistantReadCache(UUID workspace, UUID user, Set<?> permissions) {
        this(workspace, user, permissions, System::nanoTime);
    }
    AssistantReadCache(UUID workspace, UUID user, Set<?> permissions, LongSupplier clock) {
        this.workspace = workspace; this.user = user; this.permissions = Set.copyOf(permissions); this.clock = clock;
    }
    boolean owns(UUID workspace, UUID user, Set<?> permissions) {
        return this.workspace.equals(workspace) && this.user.equals(user) && this.permissions.equals(permissions);
    }
    static boolean eligible(String name, int risk) { return risk == 0 && READS.contains(name); }
    static String key(String name, JsonNode args) { return name + ":" + canonical(args == null ? JsonNodeFactory.instance.objectNode() : args); }
    private static JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull()) return JsonNodeFactory.instance.nullNode();
        if (value.isObject()) {
            var sorted = JsonNodeFactory.instance.objectNode();
            var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode(); value.forEach(v -> result.add(canonical(v))); return result;
        }
        return value;
    }
    synchronized JsonNode get(String key) {
        Entry entry = values.get(key);
        if (entry == null || clock.getAsLong() - entry.at() >= TTL) return null;
        return entry.value().deepCopy();
    }
    synchronized void put(String key, JsonNode value) {
        if (value == null || value.path("isError").asBoolean(false)) return;
        int size = value.toString().length() * 2;
        if (size > MAX_BYTES) return;
        if (values.size() >= 16 || bytes + size > MAX_BYTES) clear();
        Entry old = values.put(key, new Entry(value.deepCopy(), clock.getAsLong(), size));
        bytes += size - (old == null ? 0 : old.size());
    }
    synchronized Set<String> invalidateFor(String tool) {
        Set<String> affected = switch (tool) {
            case "generar_contenido", "subir_archivo" -> Set.of("panel", "contenido");
            case "crear_publicacion", "editar_publicacion", "publicar_ahora", "cancelar_publicacion",
                    "archivar_publicacion", "reintentar_publicacion" -> Set.of("panel", "publicaciones", "ver_publicacion", "contenido");
            default -> READS;
        };
        Set<String> removed = new HashSet<>();
        var entries = values.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (affected.stream().anyMatch(name -> entry.getKey().startsWith(name + ":"))) {
                removed.add(entry.getKey()); bytes -= entry.getValue().size(); entries.remove();
            }
        }
        return removed;
    }
    synchronized void clear() { values.clear(); bytes = 0; }
}
