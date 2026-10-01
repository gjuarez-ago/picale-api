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
        when(config.creditosPorGeneracion()).thenReturn(5);
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
    @DisplayName("una imagen gasta 5 créditos y dice cuántas imágenes más alcanzan")
    void gastaUno() {
        creditos.otorgarMensuales(workspace, 30, enUnMes(), "inv-1");

        assertThat(creditos.consumirGeneracion(workspace, "g1")).isEqualTo(5);
        assertThat(creditos.consumirGeneracion(workspace, "g2")).isEqualTo(4);
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(20);
        assertThat(creditos.disponibles(workspace)).isEqualTo(4);
    }

    @Test
    @DisplayName("reintentar la misma generación no la cobra dos veces")
    void mismaReferenciaNoCobraDosVeces() {
        creditos.otorgarMensuales(workspace, 30, enUnMes(), "inv-1");

        creditos.consumirGeneracion(workspace, "g1");
        creditos.consumirGeneracion(workspace, "g1");

        assertThat(creditos.saldo(workspace).total()).isEqualTo(25);
    }

    @Test
    @DisplayName("los 30 del mes son 6 imágenes; la séptima se rechaza")
    void seAcaban() {
        creditos.otorgarMensuales(workspace, 30, enUnMes(), "inv-1");
        for (int i = 1; i <= 6; i++) {
            creditos.consumirGeneracion(workspace, "g" + i);
        }

        assertThat(creditos.saldo(workspace).total()).isZero();
        assertThatThrownBy(() -> creditos.consumirGeneracion(workspace, "g7"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    @DisplayName("con menos de 5 créditos no alcanza para una imagen, y no se toca el saldo")
    void noAlcanza() {
        creditos.agregarPaquete(workspace, 4, "cs_1");

        assertThatThrownBy(() -> creditos.consumirGeneracion(workspace, "g1"))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(4);
        assertThat(creditos.disponibles(workspace)).isZero();
    }

    @Test
    @DisplayName("los mensuales se gastan primero y los de paquete después")
    void primeroLosMensuales() {
        creditos.otorgarMensuales(workspace, 5, enUnMes(), "inv-1");
        creditos.agregarPaquete(workspace, 39, "cs_1");

        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).mensuales()).isZero();
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(39);

        creditos.consumirGeneracion(workspace, "g2");
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(34);
    }

    @Test
    @DisplayName("una imagen puede tomar de las dos bolsas, y al devolverla cada una recupera lo suyo")
    void deLasDosBolsas() {
        creditos.otorgarMensuales(workspace, 2, enUnMes(), "inv-1");
        creditos.agregarPaquete(workspace, 39, "cs_1");

        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).mensuales()).isZero();
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(36);

        creditos.devolverGeneracion(workspace, "g1");
        creditos.devolverGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(2);
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(39);
    }

    @Test
    @DisplayName("un paquete comprado se suma una sola vez aunque Stripe avise dos")
    void paqueteIdempotente() {
        assertThat(creditos.agregarPaquete(workspace, 79, "cs_1")).isTrue();
        assertThat(creditos.agregarPaquete(workspace, 79, "cs_1")).isFalse();

        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(79);
        // Un paquete de cero o negativo no es una compra.
        assertThat(creditos.agregarPaquete(workspace, 0, "cs_2")).isFalse();
    }

    @Test
    @DisplayName("los créditos del mes se reinician, no se acumulan; y la misma factura no los da dos veces")
    void mensualesNoSeAcumulan() {
        creditos.otorgarMensuales(workspace, 30, enUnMes(), "inv-1");
        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(25);

        // Renovación: vuelven a 30, no a 55.
        assertThat(creditos.otorgarMensuales(workspace, 30, enUnMes(), "inv-2")).isTrue();
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(30);

        // El mismo aviso repetido no los repone otra vez.
        creditos.consumirGeneracion(workspace, "g2");
        assertThat(creditos.otorgarMensuales(workspace, 30, enUnMes(), "inv-2")).isFalse();
        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(25);
    }

    @Test
    @DisplayName("los mensuales vencidos ya no valen, pero los de paquete sí")
    void mensualesVencidos() {
        creditos.otorgarMensuales(workspace, 30, LocalDateTime.now().minusDays(1), "inv-1");
        creditos.agregarPaquete(workspace, 15, "cs_1");

        assertThat(creditos.saldo(workspace).mensuales()).isZero();
        assertThat(creditos.saldo(workspace).total()).isEqualTo(15);

        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(10);
    }

    @Test
    @DisplayName("si la generación no produjo nada los créditos vuelven a su bolsa, una sola vez")
    void devolucion() {
        creditos.otorgarMensuales(workspace, 30, enUnMes(), "inv-1");
        creditos.agregarPaquete(workspace, 39, "cs_1");

        creditos.consumirGeneracion(workspace, "g1"); // sale de los mensuales
        creditos.devolverGeneracion(workspace, "g1");
        creditos.devolverGeneracion(workspace, "g1"); // repetir no duplica

        assertThat(creditos.saldo(workspace).mensuales()).isEqualTo(30);
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(39);
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
        creditos.agregarPaquete(workspace, 10, "cs_1");

        creditos.consumirGeneracion(workspace, "g1");
        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(5);
        creditos.devolverGeneracion(workspace, "g1");

        assertThat(creditos.saldo(workspace).paquete()).isEqualTo(10);
        assertThat(creditos.saldo(workspace).mensuales()).isZero();
    }

    @Test
    @DisplayName("cada workspace tiene los suyos")
    void separadosPorWorkspace() {
        UUID otro = UUID.randomUUID();
        creditos.otorgarMensuales(workspace, 30, enUnMes(), "inv-1");

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
