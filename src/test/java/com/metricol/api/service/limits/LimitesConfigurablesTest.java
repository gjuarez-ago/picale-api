package com.metricol.api.service.limits;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.enums.Platform;

/**
 * Los topes que se editan en la base.
 *
 * <p>Lo que hay que garantizar es la regla de precedencia: la configuración
 * siembra, la tabla manda. Si un despliegue pisara lo que alguien cambió a
 * mano, el cambio se perdería sin aviso justo cuando más falta hace — que es
 * cuando hubo que bajarlo deprisa.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000"
})
class LimitesConfigurablesTest {

    @Autowired
    private LimitesConfigurables limites;

    @AfterEach
    void restaurar() {
        // La base en memoria se comparte entre clases de prueba: lo que se
        // toque aquí lo vería la siguiente.
        limites.establecer(LimitesConfigurables.claveCuota(Platform.TIKTOK), 15, null);
    }

    @Test
    void sembroLasClavesAlArrancar() {
        assertThat(limites.valor(LimitesConfigurables.MAX_PENDIENTES, -1)).isGreaterThan(0);
        assertThat(limites.valor(LimitesConfigurables.MAX_IA_POR_DIA, -1)).isGreaterThan(0);
        assertThat(limites.valor(LimitesConfigurables.claveCuota(Platform.INSTAGRAM), -1)).isGreaterThan(0);
    }

    @Test
    void loQueSeEscribeEnLaTablaMandaSobreLaConfiguracion() {
        limites.establecer(LimitesConfigurables.claveCuota(Platform.TIKTOK), 3, "prueba");
        assertThat(limites.cuotaDiaria(Platform.TIKTOK)).isEqualTo(3);

        // Un 0 en la tabla apaga el tope: la red deja de bloquear.
        limites.establecer(LimitesConfigurables.claveCuota(Platform.TIKTOK), 0, null);
        assertThat(limites.cuotaDiaria(Platform.TIKTOK)).isNull();
    }

    @Test
    void unaClaveQueNoExisteCaeAlValorPorDefecto() {
        assertThat(limites.valor("no.existe.esta.clave", 42)).isEqualTo(42);
    }
}
