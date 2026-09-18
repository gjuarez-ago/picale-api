package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Cada cuánto se vuelve a preguntar por un envío abierto, y hasta cuándo.
 *
 * <p>Son dos números con consecuencias opuestas. Preguntar poco deja la
 * publicación en "publicando" más de lo necesario; preguntar mucho gasta la
 * llave del proveedor, que es una para todos los negocios y tiene límite. Y el
 * tope de espera decide cuándo una red sin dato se cierra como fallida, que es
 * la decisión que puso un TikTok publicado como "falló" cuando el tope eran
 * veinte minutos y el criterio era otro.
 */
class EsperaDeConfirmacionTest {

    @Test
    void reciénMandadoSePreguntaCadaMedioMinuto() {
        // Es cuando de verdad cambia algo: las redes cierran entre el primer
        // y el segundo minuto.
        assertThat(PostPublishStore.esperaSegun(Duration.ZERO)).isEqualTo(Duration.ofSeconds(30));
        assertThat(PostPublishStore.esperaSegun(Duration.ofSeconds(90))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void laEsperaCreceConElTiempoAbierto() {
        assertThat(PostPublishStore.esperaSegun(Duration.ofMinutes(2))).isEqualTo(Duration.ofMinutes(1));
        assertThat(PostPublishStore.esperaSegun(Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(5));
        assertThat(PostPublishStore.esperaSegun(Duration.ofHours(1))).isEqualTo(Duration.ofMinutes(30));
        assertThat(PostPublishStore.esperaSegun(Duration.ofHours(6))).isEqualTo(Duration.ofHours(1));
        assertThat(PostPublishStore.esperaSegun(Duration.ofHours(20))).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void enUnDiaEnteroCabenPocasConsultas() {
        // La suma de lo que se pregunta a lo largo del tope. Si alguien baja
        // un escalón sin mirar, esto lo hace visible: pasar de ~40 llamadas
        // a cientos por publicación se nota en la llave compartida.
        Duration abierto = Duration.ZERO;
        int consultas = 0;
        while (abierto.compareTo(PostPublishStore.ESPERA_MAXIMA) < 0) {
            abierto = abierto.plus(PostPublishStore.esperaSegun(abierto));
            consultas++;
        }
        assertThat(consultas).isLessThan(60);
    }

    @Test
    void elTopeEsUnDia() {
        // Contra el daño de cerrar como fallida una red que sí salió —y de
        // ofrecer corregirla, que la publicaría dos veces— un día en
        // "publicando" es barato.
        assertThat(PostPublishStore.ESPERA_MAXIMA).isEqualTo(Duration.ofHours(24));
    }
}
