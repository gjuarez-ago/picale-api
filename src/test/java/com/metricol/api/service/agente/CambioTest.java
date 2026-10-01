package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lo que se reconoce de "¿Le cambiamos algo?". */
class CambioTest {

    @Test
    @DisplayName("reconoce logo y diseño aunque vengan con acentos o mayúsculas")
    void reconoce() {
        Cambio c = Cambio.de("Quítale el logo y déjala TAL CUAL, sin precio");
        assertThat(c.logo()).isFalse();
        assertThat(c.diseno()).isFalse();
        assertThat(c.texto()).isEqualTo("Quítale el logo y déjala TAL CUAL, sin precio");

        Cambio d = Cambio.de("Diséñala con el logo");
        assertThat(d.logo()).isTrue();
        assertThat(d.diseno()).isTrue();
    }

    @Test
    @DisplayName("lo demás no toca la decisión: solo llega como texto")
    void soloTexto() {
        Cambio c = Cambio.de("Más cercano y menciona Cancún");
        assertThat(c.logo()).isNull();
        assertThat(c.diseno()).isNull();
        assertThat(c.vacio()).isFalse();
        assertThat(Cambio.de("   ").vacio()).isTrue();
    }

    @Test
    @DisplayName("lo pedido manda sobre el decisor y se explica")
    void mandaSobreElDecisor() {
        var decision = new DecisorDelAgente.Decision(DecisorDelAgente.Tratamiento.DISENO, true,
                DecisorDelAgente.Prioridad.ALTA, java.util.List.of("Pide diseño."));
        var hecha = AgenteService.conCambio(decision, Cambio.de("sin logo, tal cual"), 3);
        assertThat(hecha.tratamiento()).isEqualTo(DecisorDelAgente.Tratamiento.TAL_CUAL);
        assertThat(hecha.logo()).isFalse();
        assertThat(hecha.explicacion()).contains("Me pediste sin diseño").contains("Me pediste sin logo")
                .contains("«sin logo, tal cual»");
    }
}
