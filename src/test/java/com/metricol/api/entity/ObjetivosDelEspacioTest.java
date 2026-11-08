package com.metricol.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.ObjetivoRedes;

class ObjetivosDelEspacioTest {

    @Test
    @DisplayName("se guardan en orden, sin repetidos y hasta tres; el principal es el primero")
    void ordenYTope() {
        Workspace w = new Workspace();
        w.ponerObjetivos(List.of(ObjetivoRedes.PEDIDOS, ObjetivoRedes.PEDIDOS, ObjetivoRedes.VISITAS,
                ObjetivoRedes.EXPERTO, ObjetivoRedes.COMUNIDAD));

        assertThat(w.objetivos()).containsExactly(ObjetivoRedes.PEDIDOS, ObjetivoRedes.VISITAS, ObjetivoRedes.EXPERTO);
        assertThat(w.getObjetivo()).isEqualTo(ObjetivoRedes.PEDIDOS);
        assertThat(w.getObjetivosElegidos()).isEqualTo("PEDIDOS,VISITAS,EXPERTO");
    }

    @Test
    @DisplayName("lo de antes, con un solo objetivo y sin lista, se lee como lista de uno")
    void loDeAntes() {
        Workspace w = new Workspace();
        w.setObjetivo(ObjetivoRedes.COMUNIDAD);

        assertThat(w.objetivos()).containsExactly(ObjetivoRedes.COMUNIDAD);
    }

    @Test
    @DisplayName("la app instalada devuelve el equivalente viejo: no borra lo elegido en la web")
    void laAppNoBorraLoElegido() {
        Workspace w = new Workspace();
        w.ponerObjetivos(List.of(ObjetivoRedes.VISITAS, ObjetivoRedes.QUE_REGRESEN));

        // La app recibió MAS_CLIENTES (el legado de VISITAS) y lo manda tal cual al guardar otra cosa.
        w.ponerObjetivosDe(null, ObjetivoRedes.MAS_CLIENTES);
        assertThat(w.objetivos()).containsExactly(ObjetivoRedes.VISITAS, ObjetivoRedes.QUE_REGRESEN);

        // Si de verdad elige otro en la app, se cambia.
        w.ponerObjetivosDe(null, ObjetivoRedes.VENDER_MAS);
        assertThat(w.objetivos()).containsExactly(ObjetivoRedes.VENDER_MAS);

        // La lista, si viene, manda.
        w.ponerObjetivosDe(List.of(ObjetivoRedes.CONFIANZA), ObjetivoRedes.MAS_CLIENTES);
        assertThat(w.objetivos()).containsExactly(ObjetivoRedes.CONFIANZA);
    }

    @Test
    @DisplayName("todo objetivo nuevo equivale a uno de los cinco que conoce la app instalada")
    void legadoSiempreDeLosCinco() {
        List<ObjetivoRedes> cinco = List.of(ObjetivoRedes.MAS_CLIENTES, ObjetivoRedes.VENDER_MAS,
                ObjetivoRedes.DAR_A_CONOCER, ObjetivoRedes.COMUNIDAD, ObjetivoRedes.CONSTANCIA);
        assertThat(Arrays.stream(ObjetivoRedes.values()).map(ObjetivoRedes::getLegado)).allMatch(cinco::contains);
    }

    @Test
    @DisplayName("lo guardado que ya no existe se ignora en vez de romper")
    void leerTolerante() {
        assertThat(ObjetivoRedes.leer("PEDIDOS, NO_EXISTE ,VISITAS")).containsExactly(ObjetivoRedes.PEDIDOS,
                ObjetivoRedes.VISITAS);
        assertThat(ObjetivoRedes.leer(null)).isEmpty();
    }
}
