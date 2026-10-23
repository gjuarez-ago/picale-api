package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class AssistantReadCacheTest {
    final ObjectMapper json = new ObjectMapper();
    final UUID workspace = UUID.randomUUID(), user = UUID.randomUUID();
    final AtomicLong clock = new AtomicLong();
    final AssistantReadCache cache = new AssistantReadCache(workspace, user, Set.of("READ"), clock::get);

    @Test void sortsObjectKeysButPreservesArrayOrderAndNullMeaning() throws Exception {
        assertThat(AssistantReadCache.key("marca", json.readTree("{\"b\":2,\"a\":1}")))
                .isEqualTo(AssistantReadCache.key("marca", json.readTree("{\"a\":1,\"b\":2}")));
        assertThat(AssistantReadCache.key("contenido", json.readTree("[1,2]")))
                .isNotEqualTo(AssistantReadCache.key("contenido", json.readTree("[2,1]")));
        assertThat(AssistantReadCache.key("marca", json.readTree("{\"a\":null}")))
                .isNotEqualTo(AssistantReadCache.key("marca", json.readTree("{\"a\":{}}")));
    }
    @Test void neverCachesWritesJobsOrUnknownTools() {
        assertThat(AssistantReadCache.eligible("marca", 0)).isTrue();
        for (String name : List.of("generar_contenido", "estado_generacion", "actualizar_marca", "publicar_ahora", "unknown"))
            assertThat(AssistantReadCache.eligible(name, 0)).isFalse();
        assertThat(AssistantReadCache.eligible("marca", 1)).isFalse();
    }
    @Test void keepsCopiesAndExpiresReads() {
        var value = json.createObjectNode().put("value", "before");
        cache.put("key", value); value.put("value", "changed");
        assertThat(cache.get("key").path("value").asText()).isEqualTo("before");
        clock.set(Duration.ofSeconds(30).toNanos());
        assertThat(cache.get("key")).isNull();
    }
    @Test void invalidationDoesNotRetainOldDataAndErrorsAreNotCached() {
        cache.put("key", json.createObjectNode().put("ok", true)); cache.clear();
        assertThat(cache.get("key")).isNull();
        cache.put("error", json.createObjectNode().put("isError", true));
        assertThat(cache.get("error")).isNull();
    }
    @Test void verifiesWorkspaceUserAndPermissions() {
        assertThat(cache.owns(workspace, user, Set.of("READ"))).isTrue();
        assertThat(cache.owns(UUID.randomUUID(), user, Set.of("READ"))).isFalse();
        assertThat(cache.owns(workspace, UUID.randomUUID(), Set.of("READ"))).isFalse();
        assertThat(cache.owns(workspace, user, Set.of("WRITE"))).isFalse();
    }
    @Test void generationInvalidatesAffectedListsButNotBrandOrOriginalPhoto() {
        for (String tool : List.of("marca", "ver_archivo", "contenido", "panel"))
            cache.put(AssistantReadCache.key(tool, null), json.createObjectNode().put("ok", true));
        cache.invalidateFor("generar_contenido");
        assertThat(cache.get(AssistantReadCache.key("marca", null))).isNotNull();
        assertThat(cache.get(AssistantReadCache.key("ver_archivo", null))).isNotNull();
        assertThat(cache.get(AssistantReadCache.key("contenido", null))).isNull();
        assertThat(cache.get(AssistantReadCache.key("panel", null))).isNull();
        cache.invalidateFor("actualizar_marca");
        assertThat(cache.get(AssistantReadCache.key("marca", null))).isNull();
    }
}
