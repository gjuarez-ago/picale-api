package com.metricol.api.service.comentarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leer lo que contesta upload-post. Cada red nombra lo suyo distinto y la
 * bandeja tiene que verse igual con todas (C-05, C-11, C-16).
 */
class LecturaDeComentariosTest {

    @Test
    @DisplayName("Instagram: username y texto en 'text'")
    void instagram() {
        Map<String, Object> respuesta = Map.of("success", true, "comments", List.of(Map.of(
                "id", "17890", "username", "maria_lopez", "text", "¿Cuánto cuesta?",
                "created_at", "2026-10-07T15:30:00Z")));

        LecturaDeComentarios.Pagina pagina = LecturaDeComentarios.leer(respuesta, "tacos_el_guero");

        assertThat(pagina.comentarios()).hasSize(1);
        LecturaDeComentarios.Leido uno = pagina.comentarios().get(0);
        assertThat(uno.id()).isEqualTo("17890");
        assertThat(uno.autor()).isEqualTo("maria_lopez");
        assertThat(uno.texto()).isEqualTo("¿Cuánto cuesta?");
        assertThat(uno.escritoEn()).isNotNull();
        assertThat(uno.propio()).isFalse();
    }

    @Test
    @DisplayName("Facebook: el autor viene anidado en 'from'")
    void facebookAnidado() {
        Map<String, Object> respuesta = Map.of("comments", List.of(Map.of(
                "id", "99", "from", Map.of("name", "Juan Pérez"), "message", "Me encantó")));

        LecturaDeComentarios.Leido uno = LecturaDeComentarios.leer(respuesta, null).comentarios().get(0);

        assertThat(uno.autor()).isEqualTo("Juan Pérez");
        assertThat(uno.texto()).isEqualTo("Me encantó");
    }

    @Test
    @DisplayName("YouTube: authorDisplayName y textOriginal")
    void youtube() {
        Map<String, Object> respuesta = Map.of("comments", List.of(Map.of(
                "id", "Ug123", "authorDisplayName", "Ana", "textOriginal", "Buen video",
                "publishedAt", "2026-10-06T10:00:00Z")));

        LecturaDeComentarios.Leido uno = LecturaDeComentarios.leer(respuesta, null).comentarios().get(0);

        assertThat(uno.autor()).isEqualTo("Ana");
        assertThat(uno.texto()).isEqualTo("Buen video");
    }

    @Test
    @DisplayName("TikTok: la fecha viene en segundos desde 1970")
    void tiktokEpoch() {
        Map<String, Object> respuesta = Map.of("comments", List.of(Map.of(
                "id", "t1", "nickname", "pepe", "text", "🔥🔥", "create_time", 1759800000L)));

        LecturaDeComentarios.Leido uno = LecturaDeComentarios.leer(respuesta, null).comentarios().get(0);

        assertThat(uno.escritoEn()).isNotNull();
        assertThat(uno.texto()).isEqualTo("🔥🔥");
    }

    @Test
    @DisplayName("C-04: lo que escribió la propia cuenta se marca propio")
    void loNuestroEsPropio() {
        Map<String, Object> respuesta = Map.of("comments", List.of(
                Map.of("id", "1", "username", "Tacos_El_Guero", "text", "¡Gracias!"),
                Map.of("id", "2", "username", "alguien", "text", "Qué rico")));

        List<LecturaDeComentarios.Leido> leidos = LecturaDeComentarios.leer(respuesta, "tacos_el_guero")
                .comentarios();

        assertThat(leidos.get(0).propio()).isTrue();
        assertThat(leidos.get(1).propio()).isFalse();
    }

    @Test
    @DisplayName("C-05: una respuesta apunta a su comentario padre")
    void hilo() {
        Map<String, Object> respuesta = Map.of("comments", List.of(Map.of(
                "id", "2", "parent_id", "1", "username", "x", "text", "yo también")));

        assertThat(LecturaDeComentarios.leer(respuesta, null).comentarios().get(0).padreId()).isEqualTo("1");
    }

    @Test
    @DisplayName("C-12: hay más páginas solo si además vino el cursor")
    void paginacion() {
        Map<String, Object> conCursor = Map.of("comments", List.of(Map.of("id", "1", "text", "a")),
                "pagination", Map.of("has_next", true, "next_cursor", "abc"));
        Map<String, Object> sinCursor = Map.of("comments", List.of(Map.of("id", "1", "text", "a")),
                "pagination", Map.of("has_next", true));

        assertThat(LecturaDeComentarios.leer(conCursor, null).hayMas()).isTrue();
        assertThat(LecturaDeComentarios.leer(conCursor, null).siguienteCursor()).isEqualTo("abc");
        // Sin por dónde seguir, decir que hay más solo serviría para girar en vacío.
        assertThat(LecturaDeComentarios.leer(sinCursor, null).hayMas()).isFalse();
    }

    @Test
    @DisplayName("C-16: Threads avisa que trajo solo una parte")
    void parcial() {
        Map<String, Object> respuesta = Map.of("partial", true,
                "comments", List.of(Map.of("id", "1", "text", "hola")));

        assertThat(LecturaDeComentarios.leer(respuesta, null).parcial()).isTrue();
    }

    @Test
    @DisplayName("Un comentario sin id no se puede guardar ni deduplicar: se descarta")
    void sinIdSeDescarta() {
        Map<String, Object> respuesta = Map.of("comments", List.of(
                Map.of("text", "sin id"), Map.of("id", "1", "text", "con id")));

        assertThat(LecturaDeComentarios.leer(respuesta, null).comentarios()).hasSize(1);
    }

    @Test
    @DisplayName("C-07: la red se queja y se dice por qué")
    void error() {
        assertThat(LecturaDeComentarios.error(Map.of("success", false, "error", "token expired")))
                .isEqualTo("token expired");
        assertThat(LecturaDeComentarios.error(Map.of("success", true, "comments", List.of()))).isNull();
        assertThat(LecturaDeComentarios.error(null)).isNotNull();
    }

    @Test
    @DisplayName("Una respuesta vacía o rara no revienta: no trae comentarios y ya")
    void respuestaRara() {
        assertThat(LecturaDeComentarios.leer(null, null).vacia()).isTrue();
        assertThat(LecturaDeComentarios.leer(Map.of(), null).vacia()).isTrue();
        assertThat(LecturaDeComentarios.leer(Map.of("comments", "nada"), null).vacia()).isTrue();
    }

    @Test
    @DisplayName("Una fecha que no se entiende queda nula, no inventada")
    void fechaRara() {
        assertThat(LecturaDeComentarios.fecha("ayer por la tarde")).isNull();
        assertThat(LecturaDeComentarios.fecha("2026-10-07 15:30:00")).isNotNull();
    }
}
