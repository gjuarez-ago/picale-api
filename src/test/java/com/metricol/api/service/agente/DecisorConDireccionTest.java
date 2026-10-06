package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.agente.DecisorDelAgente.Contexto;
import com.metricol.api.service.agente.DecisorDelAgente.Decision;
import com.metricol.api.service.agente.DecisorDelAgente.Diagnostico;
import com.metricol.api.service.agente.DecisorDelAgente.Intencion;
import com.metricol.api.service.agente.DecisorDelAgente.Tratamiento;

/** Lo que vio el director de foto cambia el tratamiento de la foto (CMRG, 5 oct 2026). */
class DecisorConDireccionTest {

    private static final Contexto SIN_CREDITOS = new Contexto(0, 0);

    /** Una obra terminada, de teléfono: buena, pero plana. */
    private static Decision obra(int calidad) {
        return DecisorDelAgente.decidir(new Diagnostico(calidad, calidad <= 2 ? "oscura" : "", true, 4, false, false,
                Intencion.CONFIANZA, "OBRA"), SIN_CREDITOS);
    }

    @Test
    @DisplayName("una obra lleva logo: es el portafolio del negocio")
    void obraConLogo() {
        Decision d = obra(3);
        assertThat(d.logo()).isTrue();
        assertThat(d.explicacion()).contains("es un trabajo tuyo");
    }

    @Test
    @DisplayName("si el director ve que mejora, y hay cupo: MEJORA, y lo dice antes del logo")
    void mejora() {
        Decision d = DecisorDelAgente.conDireccion(obra(3), true, "líneas chuecas y sombras oscuras", true);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.MEJORA);
        assertThat(d.logo()).isTrue();
        assertThat(d.pasos().get(d.pasos().size() - 2)).contains("Le falta líneas chuecas y sombras oscuras");
        assertThat(d.pasos().get(d.pasos().size() - 1)).startsWith("Lleva tu logo");
    }

    @Test
    @DisplayName("una foto oscura que iba a retoque sube a mejora y ya no anuncia el retoque")
    void retoqueSubeAMejora() {
        Decision antes = obra(2);
        assertThat(antes.tratamiento()).isEqualTo(Tratamiento.RETOQUE);
        Decision d = DecisorDelAgente.conDireccion(antes, true, "sombras tapadas", true);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.MEJORA);
        assertThat(d.explicacion()).doesNotContain("le hago un retoque");
    }

    @Test
    @DisplayName("sin cupo de mejoras: se queda como iba y lo explica")
    void sinCupo() {
        Decision d = DecisorDelAgente.conDireccion(obra(2), true, "sombras tapadas", false);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.RETOQUE);
        assertThat(d.explicacion()).contains("hoy ya no me quedan").contains("retoque sencillo");
    }

    @Test
    @DisplayName("si ya se ve profesional, tal cual y se dice que se revisó")
    void yaProfesional() {
        Decision d = DecisorDelAgente.conDireccion(obra(4), false, "", true);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.TAL_CUAL);
        assertThat(d.explicacion()).contains("ya se ve profesional");
    }

    @Test
    @DisplayName("un diseño o una observación no cambian con la dirección")
    void disenoIntacto() {
        Decision diseno = DecisorDelAgente.decidir(new Diagnostico(4, "", true, 2, false, true, Intencion.VENDER,
                "PROMOCION"), new Contexto(3, 0));
        assertThat(diseno.tratamiento()).isEqualTo(Tratamiento.DISENO);
        assertThat(DecisorDelAgente.conDireccion(diseno, true, "x", true)).isSameAs(diseno);
    }

    @Test
    @DisplayName("el logo según cómo trabaja: la obra firma el portafolio solo de quien trabaja por proyecto")
    void logoPorRasgo() {
        var proyecto = java.util.EnumSet.of(com.metricol.api.enums.RasgoDelNegocio.POR_PROYECTO);
        var tienda = java.util.EnumSet.of(com.metricol.api.enums.RasgoDelNegocio.PRODUCTO,
                com.metricol.api.enums.RasgoDelNegocio.LOCAL);
        assertThat(DecisorDelAgente.conLogo("OBRA", proyecto)).isTrue();
        assertThat(DecisorDelAgente.conLogo("OBRA", tienda)).isFalse();
        assertThat(DecisorDelAgente.conLogo("PRODUCTO", tienda)).isTrue();
        assertThat(DecisorDelAgente.conLogo("PRODUCTO", proyecto)).isTrue();
        assertThat(DecisorDelAgente.conLogo("PROMOCION", tienda)).isTrue();
        assertThat(DecisorDelAgente.conLogo("LUGAR", tienda)).isFalse();
        // Sin perfil todavía: la lista de siempre.
        assertThat(DecisorDelAgente.conLogo("OBRA", null)).isTrue();
    }

    @Test
    @DisplayName("acabado: lo que pida la persona manda; si en su cuenta no gustan los adornos, va limpia")
    void acabadoAprendido() {
        assertThat(DecisorDelAgente.acabado("FRANJA", 0, null).estilo()).isEqualTo("FRANJA");
        assertThat(DecisorDelAgente.acabado("MARCO", 1, null).estilo()).isEqualTo("LIMPIO");
        assertThat(DecisorDelAgente.acabado("FRANJA", 1, null).estilo()).isEqualTo("FRANJA");
        assertThat(DecisorDelAgente.acabado("FRANJA", 2, null).estilo()).isEqualTo("LIMPIO");
        assertThat(DecisorDelAgente.acabado("FRANJA", 2, null).paso()).contains("sin adornos");
        assertThat(DecisorDelAgente.acabado("LIMPIO", 2, "MARCO").estilo()).isEqualTo("MARCO");
        assertThat(DecisorDelAgente.acabado("FRANJA", 0, "LIMPIO").paso()).contains("Me pediste sin adornos");
    }

    @Test
    @DisplayName("las frases de acabado se reconocen sin importar acentos ni mayúsculas")
    void frasesDeAcabado() {
        assertThat(Cambio.de("Quítale la franja por favor").acabado()).isEqualTo("LIMPIO");
        assertThat(Cambio.de("más sencilla").acabado()).isEqualTo("LIMPIO");
        assertThat(Cambio.de("ponle un marco").acabado()).isEqualTo("MARCO");
        assertThat(Cambio.de("Con franja").acabado()).isEqualTo("FRANJA");
        assertThat(Cambio.de("cambia el texto").acabado()).isNull();
    }

    @Test
    @DisplayName("frase: se pide con palabras, se quita igual, y con la cuenta harta de adornos va limpia")
    void fraseAprendida() {
        assertThat(Cambio.de("ponle una frase motivadora").acabado()).isEqualTo("FRASE");
        assertThat(Cambio.de("quítale la frase").acabado()).isEqualTo("LIMPIO");
        assertThat(DecisorDelAgente.acabado("FRASE", 2, null).estilo()).isEqualTo("LIMPIO");
        assertThat(DecisorDelAgente.acabado("FRASE", 1, null).estilo()).isEqualTo("FRASE");
        assertThat(DecisorDelAgente.acabado("LIMPIO", 2, "FRASE").paso()).contains("una frase");
    }
}
