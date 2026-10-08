package com.metricol.api.service.imagenes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La ficha: lo que la conversación va juntando.
 *
 * <p>Lo que más importa aquí es que NO se pierda lo ya dicho. Una conversación
 * que olvida el formato que se acordó hace tres turnos se siente rota, y es la
 * forma más rápida de que alguien vuelva al formulario.
 */
class FichaDeImagenTest {

    @Test
    @DisplayName("una ficha nueva no sabe nada y lo dice en palabras de la persona")
    void vacia() {
        FichaDeImagen f = FichaDeImagen.vacia();

        assertThat(f.completa()).isFalse();
        assertThat(f.queFalta()).containsExactly(
                "qué quieres anunciar",
                "si es para el muro, una historia o un carrusel");
        assertThat(f.resumen()).contains("todavía no se sabe nada");
    }

    @Test
    @DisplayName("con lo imprescindible ya se puede crear, aunque falte lo demás")
    void completaConLoMinimo() {
        FichaDeImagen f = FichaDeImagen.vacia()
                .con(new FichaDeImagen("2x1 en tacos", "PUBLICACION", null, null, null, null, null, null, null));

        assertThat(f.completa()).isTrue();
        assertThat(f.queFalta()).isEmpty();
    }

    @Test
    @DisplayName("lo que un turno no menciona NO se pierde")
    void noSePierdeLoYaDicho() {
        FichaDeImagen antes = FichaDeImagen.vacia()
                .con(new FichaDeImagen("2x1 en tacos", "PUBLICACION", List.of("INSTAGRAM"),
                        null, "que vengan al local", null, "viernes", null, null));

        // El turno siguiente habla solo del texto de la imagen.
        FichaDeImagen despues = antes.con(
                new FichaDeImagen(null, null, null, null, null, "2x1", null, null, null));

        assertThat(despues.textoEnLaImagen()).isEqualTo("2x1");
        assertThat(despues.queSeAnuncia()).isEqualTo("2x1 en tacos");
        assertThat(despues.formato()).isEqualTo("PUBLICACION");
        assertThat(despues.redes()).containsExactly("INSTAGRAM");
        assertThat(despues.cuando()).isEqualTo("viernes");
        assertThat(despues.queQuiereQuePase()).isEqualTo("que vengan al local");
    }

    @Test
    @DisplayName("lo nuevo sí pisa a lo viejo cuando la persona se corrige")
    void corregirse() {
        FichaDeImagen antes = FichaDeImagen.vacia()
                .con(new FichaDeImagen("tacos", "PUBLICACION", null, null, null, null, null, null, null));

        FichaDeImagen despues = antes.con(
                new FichaDeImagen(null, "HISTORIA", null, null, null, null, null, null, null));

        assertThat(despues.formato()).isEqualTo("HISTORIA");
    }

    @Test
    @DisplayName("un campo vacío o en blanco no borra lo que ya había")
    void vacioNoBorra() {
        FichaDeImagen antes = FichaDeImagen.vacia()
                .con(new FichaDeImagen("tacos", "PUBLICACION", null, null, null, null, null, null, null));

        FichaDeImagen despues = antes.con(
                new FichaDeImagen("", "   ", null, null, null, null, null, null, null));

        assertThat(despues.queSeAnuncia()).isEqualTo("tacos");
        assertThat(despues.formato()).isEqualTo("PUBLICACION");
    }

    @Test
    @DisplayName("las fotos se suman, y la misma foto no entra dos veces")
    void fotosSeSuman() {
        FichaDeImagen f = FichaDeImagen.vacia()
                .conFotos(List.of("a", "b"))
                .conFotos(List.of("b", "c"));

        assertThat(f.fotos()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("el resumen solo enseña lo que se sabe, para no preguntar de nuevo")
    void resumen() {
        FichaDeImagen f = FichaDeImagen.vacia()
                .con(new FichaDeImagen("2x1 en tacos", "PUBLICACION", List.of("INSTAGRAM", "FACEBOOK"),
                        null, null, "2x1", "viernes", null, null));

        String r = f.resumen();

        assertThat(r).contains("2x1 en tacos").contains("PUBLICACION")
                .contains("INSTAGRAM, FACEBOOK").contains("viernes");
        // Lo que no se sabe no aparece: una lista de huecos invita a
        // preguntarlo todo, que es justo lo que no queremos.
        assertThat(r).doesNotContain("Qué quiere que pase");
    }

    @Test
    @DisplayName("una ficha nunca tiene listas nulas: la pantalla no se puede caer por eso")
    void listasNuncaNulas() {
        FichaDeImagen f = new FichaDeImagen("x", "PUBLICACION", null, null, null, null, null, null, null);

        assertThat(f.redes()).isEmpty();
        assertThat(f.fotos()).isEmpty();
    }
}
