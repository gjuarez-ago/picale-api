package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.CreditMovement;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.repository.CreditMovementRepository;

/**
 * Los créditos de imagen contra la base de verdad (esquema, candado y restricción
 * única incluidos). Cada prueba se revierte al terminar: no deja filas.
 *
 * <p>La configuración va simulada: así "cobros encendidos" existe solo en el
 * contexto de esta clase, y el flag real de la base —que leen los procesos de
 * fondo de los demás contextos— no se toca.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000",
        "app.billing.sweep-initial-delay-ms=3600000"
})
class CreditServiceTest {

    @Autowired
    private CreditService creditos;

    @Autowired
    private CreditMovementRepository movimientos;

    @MockitoBean
    private BillingConfig config;

    private UUID workspace;

    @BeforeEach
    void preparar() {
        workspace = UUID.randomUUID();
        when(config.habilitado()).thenReturn(true);
    }

    private static LocalDateTime enUnMes() {
        return LocalDateTime.now().plusDays(30);
    }

    @Test
    @DisplayName("con los cobros apagados no hay créditos ni tope, y no se escribe nada")
    void apagado() {
        when(config.habilitado()).thenReturn(false);

        assertThat(creditos.consumirGeneracion(workspace, "g1")).isEqualTo(Integer.MAX_VALUE);
        assertThat(creditos.saldo(workspace).total()).isZero();
    }

    @Test
    @DisplayName("sin créditos no se puede generar, y el mensaje dice cómo seguir")
    void sinCreditos() {
        assertThatThrownBy(() -> creditos.consumirGeneracion(workspace, "g1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("soporte")
                .satisfies(ex -> assertThat(ex.getMessage().toLowerCase()).doesNotContain("compra").doesNotContain("paquete"))
                .satisfies(ex -> assertThat(((QuotaExceededException) ex).getCode()).isEqualTo("CREDITS_EXHAUSTED"));
    }

    @Test
    @DisplayName("1 crédito = 1 generación: gasta uno y dice cuántos quedan")
    void gastaUno() {
        creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-1");

        assertThat(creditos.consumirGeneracion(workspace, "g1")).isEqualTo(4);
        assertThat(creditos.consumirGeneracion(workspace, "g2")).isEqualTo(3);
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(3);
    }

    @Test
    @DisplayName("reintentar la misma generación no la cobra dos veces")
    void mismaReferenciaNoCobraDosVeces() {
        creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-1");

        creditos.consumirGeneracion(workspace, "g1");
        creditos.consumirGeneracion(workspace, "g1");

        assertThat(creditos.saldo(workspace).total()).isEqualTo(4);
    }

    @Test
    @DisplayName("se acaban a los cinco, y el sexto se rechaza")
    void seAcaban() {
        creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-1");
        for (int i = 1; i <= 5; i++) {
            creditos.consumirGeneracion(workspace, "g" + i);
        }

        assertThat(creditos.saldo(workspace).total()).isZero();
        assertThatThrownBy(() -> creditos.consumirGeneracion(workspace, "g6"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    @DisplayName("los mensuales se gastan primero y los de paquete después")
    void primeroLosMensuales() {
        creditos.otorgarMensuales(workspace, 1, enUnMes(), "inv-1");
        creditos.agregarPaquete(workspace, 10, "cs_1");

        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).mensuales()).isZero();
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(10);

        creditos.consumirGeneracion(workspace, "g2");
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(9);
    }

    @Test
    @DisplayName("un paquete comprado se suma una sola vez aunque Stripe avise dos")
    void paqueteIdempotente() {
        assertThat(creditos.agregarPaquete(workspace, 25, "cs_1")).isTrue();
        assertThat(creditos.agregarPaquete(workspace, 25, "cs_1")).isFalse();

        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(25);
        // Un paquete de cero o negativo no es una compra.
        assertThat(creditos.agregarPaquete(workspace, 0, "cs_2")).isFalse();
    }

    @Test
    @DisplayName("los créditos del mes se reinician, no se acumulan; y la misma factura no los da dos veces")
    void mensualesNoSeAcumulan() {
        creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-1");
        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(4);

        // Renovación: vuelven a 5, no a 9.
        assertThat(creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-2")).isTrue();
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(5);

        // El mismo aviso repetido no los repone otra vez.
        creditos.consumirGeneracion(workspace, "g2");
        assertThat(creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-2")).isFalse();
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(4);
    }

    @Test
    @DisplayName("los mensuales vencidos ya no valen, pero los de paquete sí")
    void mensualesVencidos() {
        creditos.otorgarMensuales(workspace, 5, LocalDateTime.now().minusDays(1), "inv-1");
        creditos.agregarPaquete(workspace, 3, "cs_1");

        assertThat(creditos.saldo(workspace).mensuales()).isZero();
        assertThat(creditos.saldo(workspace).total()).isEqualTo(3);

        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(2);
    }

    @Test
    @DisplayName("si la generación no produjo nada el crédito vuelve a su bolsa, una sola vez")
    void devolucion() {
        creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-1");
        creditos.agregarPaquete(workspace, 10, "cs_1");

        creditos.consumirGeneracion(workspace, "g1"); // sale de los mensuales
        creditos.devolverGeneracion(workspace, "g1");
        creditos.devolverGeneracion(workspace, "g1"); // repetir no duplica

        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(5);
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(10);
    }

    @Test
    @DisplayName("devolver una generación que nunca se cobró no regala créditos")
    void devolverLoQueNoSeCobro() {
        creditos.devolverGeneracion(workspace, "no-existe");
        assertThat(creditos.saldo(workspace).total()).isZero();
    }

    @Test
    @DisplayName("la devolución vuelve a la bolsa de paquete cuando de ahí salió")
    void devolucionDePaquete() {
        creditos.agregarPaquete(workspace, 2, "cs_1");

        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(1);
        creditos.devolverGeneracion(workspace, "g1");

        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(2);
        assertThat(creditos.saldo(workspace).mensuales()).isZero();
    }

    @Test
    @DisplayName("cada workspace tiene los suyos")
    void separadosPorWorkspace() {
        UUID otro = UUID.randomUUID();
        creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-1");

        assertThat(creditos.saldo(otro).total()).isZero();
        assertThatThrownBy(() -> creditos.consumirGeneracion(otro, "g1"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    @DisplayName("el ajuste a mano va a la bolsa de paquete, nunca deja saldo negativo y es idempotente")
    void ajusteAMano() {
        assertThat(creditos.ajustar(workspace, 3, "root:a")).isEqualTo(3);
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(3);
        assertThat(creditos.saldo(workspace).mensuales()).isZero();

        // Quitar más de lo que hay deja cero, no negativo.
        assertThat(creditos.ajustar(workspace, -10, "root:b")).isZero();
        assertThat(creditos.saldo(workspace).paquete()).isZero();

        // La misma referencia dos veces no suma dos veces.
        creditos.ajustar(workspace, 4, "root:c");
        creditos.ajustar(workspace, 4, "root:c");
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(4);

        // El motivo escrito a mano queda en el movimiento, no solo en el log.
        creditos.ajustar(workspace, 2, "root:d", "cortesía por la falla del lunes");
        assertThat(movimientos.findByWorkspaceIdAndMotivoAndReferencia(workspace, CreditMovement.AJUSTE, "root:d")
                .map(CreditMovement::getNota)).contains("cortesía por la falla del lunes");
    }
}
