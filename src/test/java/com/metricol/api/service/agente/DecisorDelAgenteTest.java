package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.agente.DecisorDelAgente.Contexto;
import com.metricol.api.service.agente.DecisorDelAgente.Decision;
import com.metricol.api.service.agente.DecisorDelAgente.Diagnostico;
import com.metricol.api.service.agente.DecisorDelAgente.Intencion;
import com.metricol.api.service.agente.DecisorDelAgente.Prioridad;
import com.metricol.api.service.agente.DecisorDelAgente.Tratamiento;

/** El flujo de decisión, foto por foto. */
class DecisorDelAgenteTest {

    private static final Contexto CON_CREDITOS = new Contexto(3, 0);
    private static final Contexto SIN_CREDITOS = new Contexto(0, 0);

    private static Diagnostico foto(int calidad, boolean arreglable, int fuerza, boolean esArte, boolean necesitaTexto,
            Intencion intencion, String tipo) {
        return new Diagnostico(calidad, calidad <= 2 ? "oscura" : "", arreglable, fuerza, esArte, necesitaTexto,
                intencion, tipo);
    }

    @Test
    @DisplayName("un flyer ya hecho sale tal cual y sin logo, aunque haya créditos")
    void arteTerminado() {
        Decision d = DecisorDelAgente.decidir(foto(4, true, 2, true, true, Intencion.VENDER, "PROMOCION"), CON_CREDITOS);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.TAL_CUAL);
        assertThat(d.logo()).isFalse();
    }

    @Test
    @DisplayName("una foto de producto buena y llamativa sale tal cual, con logo")
    void productoLlamativo() {
        Decision d = DecisorDelAgente.decidir(foto(4, true, 5, false, false, Intencion.VENDER, "PRODUCTO"), CON_CREDITOS);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.TAL_CUAL);
        assertThat(d.logo()).isTrue();
        assertThat(d.explicacion()).contains("llamativa por sí sola (5/5)");
    }

    @Test
    @DisplayName("una foto oscura que se corrige lleva retoque, sin gastar créditos")
    void oscuraArreglable() {
        Decision d = DecisorDelAgente.decidir(foto(2, true, 4, false, false, Intencion.VENDER, "PRODUCTO"), SIN_CREDITOS);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.RETOQUE);
        assertThat(d.explicacion()).contains("está oscura, pero se corrige");
    }

    @Test
    @DisplayName("una promoción con precio pide diseño con prioridad alta, y lo lleva si hay un crédito")
    void promoConPrecio() {
        Decision d = DecisorDelAgente.decidir(foto(4, true, 4, false, true, Intencion.VENDER, "PROMOCION"),
                new Contexto(1, 0));
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.DISENO);
        assertThat(d.prioridad()).isEqualTo(Prioridad.ALTA);
        assertThat(d.logo()).isTrue();
    }

    @Test
    @DisplayName("sin créditos, la promoción va tal cual y lo dice")
    void promoSinCreditos() {
        Decision d = DecisorDelAgente.decidir(foto(4, true, 4, false, true, Intencion.VENDER, "PROMOCION"), SIN_CREDITOS);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.TAL_CUAL);
        assertThat(d.explicacion()).contains("No quedan créditos esta semana");
    }

    @Test
    @DisplayName("una foto plana para vender es candidata media: con un solo crédito lo guarda para algo urgente")
    void planaGuardaElCredito() {
        Diagnostico plana = foto(4, true, 3, false, false, Intencion.VENDER, "PRODUCTO");
        assertThat(DecisorDelAgente.decidir(plana, new Contexto(2, 0)).tratamiento()).isEqualTo(Tratamiento.DISENO);
        Decision uno = DecisorDelAgente.decidir(plana, new Contexto(1, 0));
        assertThat(uno.tratamiento()).isEqualTo(Tratamiento.TAL_CUAL);
        assertThat(uno.explicacion()).contains("Guardo el crédito para algo más urgente");
    }

    @Test
    @DisplayName("si la cuenta suele descartar diseños, la foto plana ya no es candidata")
    void cuentaQuePrefiereMenosDiseno() {
        Decision d = DecisorDelAgente.decidir(foto(4, true, 3, false, false, Intencion.VENDER, "PRODUCTO"),
                new Contexto(3, 1));
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.TAL_CUAL);
        assertThat(d.explicacion()).contains("prefieres menos diseño");
    }

    @Test
    @DisplayName("una foto movida de un evento no se arregla ni vende: a Observación, pide otra")
    void movidaSinMensaje() {
        Decision d = DecisorDelAgente.decidir(foto(1, false, 2, false, false, Intencion.COMUNIDAD, "EVENTO"), CON_CREDITOS);
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.OBSERVACION);
        assertThat(d.explicacion()).contains("mejor pide otra");
    }

    @Test
    @DisplayName("una foto movida de una promoción solo la rescata un diseño; sin créditos, a Observación")
    void movidaConPromo() {
        Diagnostico movida = foto(1, false, 2, false, true, Intencion.VENDER, "PROMOCION");
        assertThat(DecisorDelAgente.decidir(movida, CON_CREDITOS).tratamiento()).isEqualTo(Tratamiento.DISENO);
        assertThat(DecisorDelAgente.decidir(movida, SIN_CREDITOS).tratamiento()).isEqualTo(Tratamiento.OBSERVACION);
    }

    @Test
    @DisplayName("una foto del equipo no lleva logo")
    void equipoSinLogo() {
        Decision d = DecisorDelAgente.decidir(foto(4, true, 4, false, false, Intencion.COMUNIDAD, "EQUIPO"), CON_CREDITOS);
        assertThat(d.logo()).isFalse();
        assertThat(d.tratamiento()).isEqualTo(Tratamiento.TAL_CUAL);
    }
}
