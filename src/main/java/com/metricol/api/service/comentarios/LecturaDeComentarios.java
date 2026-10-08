package com.metricol.api.service.comentarios;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lee la respuesta de {@code GET /uploadposts/comments} y la deja en algo que
 * no depende de la red.
 *
 * <p>Cada red nombra lo suyo a su manera —YouTube dice {@code authorDisplayName},
 * Instagram {@code username}, Facebook {@code from.name}— y upload-post pasa
 * buena parte tal cual. Aquí se aceptan los nombres conocidos de cada cosa y lo
 * que no se reconoce se queda nulo: un comentario sin foto se ve igual de bien,
 * uno sin texto no, y por eso el texto y el id son lo único obligatorio.
 */
public final class LecturaDeComentarios {

    /** Un comentario tal como lo entendimos, antes de guardarlo. */
    public record Leido(String id, String padreId, String autor, String avatar, String texto,
            LocalDateTime escritoEn, boolean propio, String adjunto, String enlace) {
    }

    /** Lo que trajo una página: sus comentarios y por dónde seguir. */
    public record Pagina(List<Leido> comentarios, String siguienteCursor, boolean hayMas, boolean parcial) {

        public boolean vacia() {
            return comentarios.isEmpty();
        }
    }

    private LecturaDeComentarios() {
    }

    /**
     * @param cuentaPropia el nombre de nuestra cuenta en esa red, para marcar
     *                     como propios los comentarios que escribimos nosotros
     *                     (los contesta la marca, no hay que avisar de ellos)
     */
    public static Pagina leer(Map<String, Object> respuesta, String cuentaPropia) {
        List<Leido> leidos = new ArrayList<>();
        if (respuesta == null) {
            return new Pagina(leidos, null, false, false);
        }
        for (Object crudo : lista(respuesta)) {
            if (crudo instanceof Map<?, ?> c) {
                Leido uno = unComentario(c, cuentaPropia);
                if (uno != null) {
                    leidos.add(uno);
                }
            }
        }
        String cursor = null;
        boolean hayMas = false;
        if (respuesta.get("pagination") instanceof Map<?, ?> p) {
            cursor = texto(p, "next_cursor", "after", "cursor");
            hayMas = verdadero(p.get("has_next")) || verdadero(p.get("has_more"));
        }
        // Threads contesta `partial: true` cuando no pudo traerlo todo: lo que
        // vino vale, pero no se puede dar la publicación por revisada.
        boolean parcial = verdadero(respuesta.get("partial"));
        return new Pagina(leidos, cursor, hayMas && cursor != null, parcial);
    }

    /** ¿La red se quejó? El texto del error, o nulo si fue bien. */
    public static String error(Map<String, Object> respuesta) {
        if (respuesta == null) {
            return "La red no contestó.";
        }
        if (respuesta.get("success") instanceof Boolean ok && !ok) {
            String detalle = texto(respuesta, "error", "message", "detail");
            return detalle == null ? "La red no dio los comentarios." : detalle;
        }
        return null;
    }

    private static Leido unComentario(Map<?, ?> c, String cuentaPropia) {
        String id = texto(c, "id", "comment_id", "commentId");
        if (id == null) {
            return null;
        }
        String autor = autor(c);
        boolean propio = cuentaPropia != null && !cuentaPropia.isBlank() && autor != null
                && autor.equalsIgnoreCase(cuentaPropia.strip());
        if (!propio) {
            propio = verdadero(c.get("is_own")) || verdadero(c.get("from_owner")) || verdadero(c.get("is_author"));
        }
        return new Leido(
                id,
                texto(c, "parent_id", "parentId", "reply_to", "parent_comment_id"),
                autor,
                texto(c, "author_avatar", "avatar_url", "profile_picture_url", "authorProfileImageUrl",
                        "profile_image_url"),
                texto(c, "text", "message", "comment", "textOriginal", "textDisplay", "content"),
                // `created_time` es el de Facebook. Faltaba, y sin él la fecha
                // caía al momento de leerlo: un comentario de hace dos semanas
                // salía como "hace 12 min".
                fecha(texto(c, "created_time", "created_at", "createdAt", "timestamp", "create_time",
                        "publishedAt", "published_at")),
                propio,
                adjunto(c),
                texto(c, "permalink_url", "permalink", "url", "link"));
    }

    /**
     * La foto con la que comentaron, si comentaron con una.
     *
     * <p>Facebook la trae anidada en
     * {@code attachment.media.image.src}, y en ese caso {@code message} viene
     * vacío: el comentario ES la foto. Se aceptan además las formas planas por
     * si otra red la pone más a mano.
     */
    private static String adjunto(Map<?, ?> c) {
        if (c.get("attachment") instanceof Map<?, ?> adj) {
            if (adj.get("media") instanceof Map<?, ?> media
                    && media.get("image") instanceof Map<?, ?> imagen) {
                String src = texto(imagen, "src", "url");
                if (src != null) {
                    return src;
                }
            }
            String directo = texto(adj, "image", "url", "src");
            if (directo != null) {
                return directo;
            }
        }
        return texto(c, "image_url", "media_url", "photo_url");
    }

    private static String autor(Map<?, ?> c) {
        String directo = texto(c, "author", "username", "author_name", "authorDisplayName", "from_name", "nickname");
        if (directo != null) {
            return directo;
        }
        // Facebook anida el autor: {"from": {"name": "..."}}.
        for (String llave : List.of("from", "user", "owner")) {
            if (c.get(llave) instanceof Map<?, ?> a) {
                String anidado = texto(a, "name", "username", "display_name", "nickname");
                if (anidado != null) {
                    return anidado;
                }
            }
        }
        return null;
    }

    /** La lista de comentarios, buscada donde suele venir. */
    private static List<?> lista(Map<String, Object> respuesta) {
        for (String llave : List.of("comments", "data", "items", "results")) {
            Object v = respuesta.get(llave);
            if (v instanceof Map<?, ?> m && m.get("data") instanceof List<?> dentro) {
                return dentro;
            }
            if (v instanceof List<?> l) {
                return l;
            }
        }
        return List.of();
    }

    /**
     * La fecha que trae la red: ISO-8601 casi siempre, epoch en segundos en
     * TikTok. Si no se entiende se deja nula y manda la hora en que lo
     * trajimos — mejor un "hace un momento" que una fecha inventada.
     */
    static LocalDateTime fecha(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String v = valor.strip();
        if (v.matches("\\d{9,13}")) {
            long n = Long.parseLong(v);
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(v.length() > 10 ? n / 1000 : n),
                    ZoneId.systemDefault());
        }
        // Facebook escribe el huso pegado ("+0000") y eso ISO-8601 no lo come:
        // pide "+00:00". Sin esta línea la fecha quedaba nula y el comentario
        // salía con la hora en que lo leímos — uno de hace dos semanas decía
        // "hace 12 min" (visto en producción el 7 oct 2026).
        String conHuso = v.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
        try {
            return java.time.OffsetDateTime.parse(conHuso).atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignorado) {
            // Sigue abajo: puede venir sin zona horaria.
        }
        try {
            return LocalDateTime.parse(v.replace(" ", "T"));
        } catch (DateTimeParseException ignorado) {
            return null;
        }
    }

    private static String texto(Map<?, ?> m, String... llaves) {
        for (String llave : llaves) {
            Object v = m.get(llave);
            if (v instanceof String s && !s.isBlank()) {
                return s.strip();
            }
            if (v instanceof Number n) {
                return String.valueOf(n);
            }
        }
        return null;
    }

    private static boolean verdadero(Object v) {
        return v instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(v));
    }
}
