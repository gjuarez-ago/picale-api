package com.metricol.api.service.metricas;

import java.util.List;
import java.util.Map;

/**
 * Lee lo que devuelve upload-post de una publicación y se queda con seis
 * números que se pueden comparar entre redes.
 *
 * <p>Según su documentación la respuesta es
 * {@code {"platforms": {"instagram": {"post_metrics": {...}}}}}, y las llaves
 * de {@code post_metrics} cambian por red: TikTok llama {@code favorites} a los
 * guardados, YouTube trae {@code views}, Instagram renombró {@code impressions}
 * a {@code views}. Aquí se aceptan los nombres conocidos de cada cosa; lo que
 * la red no da queda nulo, no en cero.
 */
public final class LecturaDeMetricas {

    /** Lo que se guarda de una publicación. Cada número puede ser nulo. */
    public record Metricas(Long vistas, Long alcance, Long meGusta, Long comentarios, Long compartidos,
            Long guardados) {

        public boolean vacias() {
            return vistas == null && alcance == null && meGusta == null && comentarios == null
                    && compartidos == null && guardados == null;
        }
    }

    private LecturaDeMetricas() {
    }

    public static Metricas leer(Map<String, Object> respuesta, String red) {
        Map<?, ?> m = metricas(respuesta, red == null ? "" : red.toLowerCase());
        if (m == null) {
            return new Metricas(null, null, null, null, null, null);
        }
        return new Metricas(
                numero(m, "views", "video_views", "plays", "play_count", "impressions"),
                numero(m, "reach", "unique_views"),
                numero(m, "likes", "like_count", "reactions"),
                numero(m, "comments", "comment_count", "replies"),
                numero(m, "shares", "share_count", "reposts", "retweets"),
                numero(m, "saves", "saved", "favorites", "bookmarks"));
    }

    /** El objeto de métricas de esa red, buscado donde suele venir. */
    private static Map<?, ?> metricas(Map<String, Object> respuesta, String red) {
        if (respuesta == null) {
            return null;
        }
        if (respuesta.get("platforms") instanceof Map<?, ?> redes) {
            Object deLaRed = redes.get(red);
            if (deLaRed == null && redes.size() == 1) {
                deLaRed = redes.values().iterator().next();
            }
            if (deLaRed instanceof Map<?, ?> r) {
                for (String llave : List.of("post_metrics", "metrics")) {
                    if (r.get(llave) instanceof Map<?, ?> m) {
                        return m;
                    }
                }
            }
        }
        for (String llave : List.of("post_metrics", "metrics")) {
            if (respuesta.get(llave) instanceof Map<?, ?> m) {
                return m;
            }
        }
        return null;
    }

    private static Long numero(Map<?, ?> m, String... llaves) {
        for (String llave : llaves) {
            Object v = m.get(llave);
            if (v instanceof Number n) {
                return Math.max(0, n.longValue());
            }
            if (v instanceof String s && s.matches("\\d+")) {
                return Long.parseLong(s);
            }
        }
        return null;
    }
}
