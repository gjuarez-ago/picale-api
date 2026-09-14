package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fija el contrato de los botones de estilo entre la app y el servidor.
 *
 * <p>Esos nombres viajan como texto en una llamada HTTP: la app manda
 * {@code "SIN_EMOJIS"} y aquí se hace {@code Ajuste.valueOf}. No hay
 * compilador que una las dos puntas, así que renombrar una constante aquí
 * —o quitarla— rompe un botón de la app sin que nada se ponga rojo hasta que
 * alguien lo toque y le salga un error.
 *
 * <p>Esta lista escrita a mano es ese compilador que falta: si alguien cambia
 * el enum sin cambiar la app, falla aquí y no en el teléfono de un cliente.
 */
class AjusteTest {

    /** Lo que manda la app hoy (ver AjusteTono.codigo en Dart). */
    private static final List<String> LOS_QUE_MANDA_LA_APP = List.of(
            "CORTO", "LARGO", "VENDEDOR", "PROFESIONAL",
            "DIVERTIDO", "CERCANO", "HASHTAGS", "SIN_EMOJIS");

    @Test
    @DisplayName("todos los estilos de la app existen aqui")
    void laAppNoPideNadaQueNoExista() {
        for (String nombre : LOS_QUE_MANDA_LA_APP) {
            assertThatCode(() -> Ajuste.valueOf(nombre))
                    .as("la app manda %s y el servidor no lo conoce", nombre)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("no hay estilos aqui que la app no ofrezca")
    void noSobraNinguno() {
        // Al reves tambien importa, aunque duela menos: un estilo que existe y
        // que nadie puede tocar es codigo muerto que parece funcionalidad.
        assertThat(Ajuste.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrderElementsOf(LOS_QUE_MANDA_LA_APP);
    }

    @Test
    @DisplayName("cada estilo dice que hacer, y ninguno pide inventar")
    void todosTraenInstruccionUtil() {
        for (Ajuste ajuste : Ajuste.values()) {
            assertThat(ajuste.getInstruccion())
                    .as("%s sin instruccion", ajuste)
                    .isNotBlank()
                    .hasSizeGreaterThan(30);
        }
    }
}
