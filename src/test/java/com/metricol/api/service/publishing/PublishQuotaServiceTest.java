package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.enums.Platform;
import com.metricol.api.service.limits.LimitesConfigurables;
import com.metricol.api.service.publishing.PublishQuotaService.Reserva;

/**
 * La cuota diaria por red.
 *
 * <p>Es la única rama del publicado que no se puede ejercitar a mano sin
 * publicar de verdad en las redes de alguien, y a la vez es la que más caro
 * sale equivocar: pasarse del límite de una red no da un error amable — la red
 * empieza a rechazar todo y la aplicación se queda marcada.
 *
 * <p>El tope se baja a 2 <b>en la tabla</b>, no por propiedades: desde que
 * existe {@code app_limits} la configuración solo siembra, y una propiedad de
 * prueba no cambiaría una fila que ya está sembrada. Así se ejercita además el
 * camino real: lo que se escribe en la base es lo que manda.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // El despachador y el worker de programadas se apagan: esta prueba no
        // publica nada, y dejarlos corriendo solo añade ruido y consultas.
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000"
})
class PublishQuotaServiceTest {

    @Autowired
    private PublishQuotaService cuotas;

    @Autowired
    private LimitesConfigurables limites;

    @BeforeEach
    void topesDePrueba() {
        limites.establecer(LimitesConfigurables.claveCuota(Platform.INSTAGRAM), 2, "prueba");
        // Facebook sin tope, para el caso de la red que no bloquea nunca.
        limites.establecer(LimitesConfigurables.claveCuota(Platform.FACEBOOK), 0, "prueba");
        limites.establecer(LimitesConfigurables.CUOTA_GLOBAL_DIARIA, 0, "prueba");
    }

    @AfterEach
    void restaurar() {
        // La base en memoria se comparte entre clases de prueba: se devuelven
        // los valores de fábrica para no condicionar a la siguiente.
        limites.establecer(LimitesConfigurables.claveCuota(Platform.INSTAGRAM), 25, null);
        limites.establecer(LimitesConfigurables.claveCuota(Platform.FACEBOOK), 25, null);
        limites.establecer(LimitesConfigurables.CUOTA_GLOBAL_DIARIA, 0, null);
    }

    @Test
    void reservaHastaElTopeYLuegoNiega() {
        UUID workspace = UUID.randomUUID();

        assertThat(cuotas.reservar(workspace, Platform.INSTAGRAM)).isEqualTo(Reserva.OK);
        assertThat(cuotas.reservar(workspace, Platform.INSTAGRAM)).isEqualTo(Reserva.OK);
        // La tercera se topa con el límite. Es el momento en que la
        // publicación se aplaza al día siguiente en vez de fallar.
        assertThat(cuotas.reservar(workspace, Platform.INSTAGRAM)).isEqualTo(Reserva.RED_AGOTADA);
    }

    @Test
    void devolverLiberaUnHuecoParaOtraPublicacion() {
        UUID workspace = UUID.randomUUID();

        cuotas.reservar(workspace, Platform.INSTAGRAM);
        cuotas.reservar(workspace, Platform.INSTAGRAM);
        assertThat(cuotas.reservar(workspace, Platform.INSTAGRAM)).isEqualTo(Reserva.RED_AGOTADA);

        // Esto es lo que pasa cuando la llamada al proveedor falla: la
        // publicación no salió, así que su hueco vuelve. Sin esto, un mal día
        // del proveedor le comería la cuota a alguien que no publicó nada.
        cuotas.devolver(workspace, Platform.INSTAGRAM);

        assertThat(cuotas.reservar(workspace, Platform.INSTAGRAM)).isEqualTo(Reserva.OK);
    }

    @Test
    void laCuotaDeUnWorkspaceNoGastaLaDeOtro() {
        UUID uno = UUID.randomUUID();
        UUID otro = UUID.randomUUID();

        cuotas.reservar(uno, Platform.INSTAGRAM);
        cuotas.reservar(uno, Platform.INSTAGRAM);
        assertThat(cuotas.reservar(uno, Platform.INSTAGRAM)).isEqualTo(Reserva.RED_AGOTADA);

        // El contador es por workspace y por red. Compartirlo habría hecho que
        // el tráfico de un cliente dejara sin publicar a todos los demás.
        assertThat(cuotas.reservar(otro, Platform.INSTAGRAM)).isEqualTo(Reserva.OK);
    }

    @Test
    void unaRedSinTopeConfiguradoNoBloqueaNunca() {
        UUID workspace = UUID.randomUUID();

        // Instagram agotado a la vista, para dejar claro que son contadores
        // independientes; Facebook con tope 0, que es "sin tope".
        cuotas.reservar(workspace, Platform.INSTAGRAM);
        cuotas.reservar(workspace, Platform.INSTAGRAM);

        for (int i = 0; i < 5; i++) {
            assertThat(cuotas.reservar(workspace, Platform.FACEBOOK)).isEqualTo(Reserva.OK);
        }
    }

    @Test
    void elTopeSeCambiaEnLaTablaYSeAplicaEnElActo() {
        UUID workspace = UUID.randomUUID();

        limites.establecer(LimitesConfigurables.claveCuota(Platform.INSTAGRAM), 1, "prueba");

        assertThat(cuotas.reservar(workspace, Platform.INSTAGRAM)).isEqualTo(Reserva.OK);
        assertThat(cuotas.reservar(workspace, Platform.INSTAGRAM)).isEqualTo(Reserva.RED_AGOTADA);
    }

    @Test
    void elTopeGlobalFrenaATodosLosWorkspacesYDevuelveElHuecoDeLaRed() {
        UUID uno = UUID.randomUUID();
        UUID otro = UUID.randomUUID();

        // Un solo hueco para toda la plataforma hoy. El contador global del
        // día puede traer consumo de otras pruebas de esta misma ejecución,
        // así que el tope se pone por encima de lo que ya haya gastado.
        long yaUsadas = consumoGlobalDeHoy();
        limites.establecer(LimitesConfigurables.CUOTA_GLOBAL_DIARIA, yaUsadas + 1, "prueba");

        assertThat(cuotas.reservar(uno, Platform.INSTAGRAM)).isEqualTo(Reserva.OK);
        // Otro workspace, otra red, y aun así no: el tope es de la llave.
        assertThat(cuotas.reservar(otro, Platform.TIKTOK)).isEqualTo(Reserva.GLOBAL_AGOTADO);

        // Lo que importa del rechazo global: no le cobró el hueco a la red.
        PublishQuotaService.Estado tiktok = cuotas.estadoDe(otro).stream()
                .filter(e -> e.platform() == Platform.TIKTOK)
                .findFirst()
                .orElseThrow();
        assertThat(tiktok.used()).isZero();
    }

    @Test
    void elEstadoCuentaLoConsumidoYLoQueQueda() {
        UUID workspace = UUID.randomUUID();
        cuotas.reservar(workspace, Platform.INSTAGRAM);

        PublishQuotaService.Estado instagram = cuotas.estadoDe(workspace).stream()
                .filter(e -> e.platform() == Platform.INSTAGRAM)
                .findFirst()
                .orElseThrow();

        assertThat(instagram.used()).isEqualTo(1);
        assertThat(instagram.limit()).isEqualTo(2);
        assertThat(instagram.remaining()).isEqualTo(1);
        assertThat(instagram.exhausted()).isFalse();
    }

    /**
     * Cuánto lleva hoy el contador global, si existe la fila. Se lee con un
     * tope enorme y sin consumir: reservar con tope 0 no toca la fila.
     */
    private long consumoGlobalDeHoy() {
        // Con el tope global en 0 (apagado) una reserva no toca el contador
        // global, así que la única forma de saber cuánto hay es preguntar a la
        // fila; y si no hay fila, es cero.
        return repositorioGlobal.findById(java.time.LocalDate.now())
                .map(fila -> (long) fila.getUsed())
                .orElse(0L);
    }

    @Autowired
    private com.metricol.api.repository.GlobalPublishUsageRepository repositorioGlobal;
}
