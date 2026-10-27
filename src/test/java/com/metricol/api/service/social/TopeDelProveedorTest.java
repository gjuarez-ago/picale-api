package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.service.limits.LimitesConfigurables;

/**
 * El 429 que dice "esta cuenta ya publicó todo lo de hoy" no es el que pide bajar el ritmo: no pausa a nadie
 * más y espera a que de verdad haya hueco. El 27 oct 2026 se confundían y una sola cuenta pausó la cola de
 * todos 54 veces.
 */
class TopeDelProveedorTest {

    private final UUID cuenta = UUID.randomUUID();

    @Test
    @DisplayName("reconoce el tope diario de cada red y lo distingue del ritmo")
    void reconoceElTope() {
        assertThat(TopeDelProveedor.de("{\"error\":\"You have reached the daily posting cap on facebook "
                + "(25 posts per 24 hours). The post was not sent.\"}").red()).isEqualTo(Platform.FACEBOOK);
        assertThat(TopeDelProveedor.de("daily posting cap on tiktok (15 posts per 24 hours)").red())
                .isEqualTo(Platform.TIKTOK);
        assertThat(TopeDelProveedor.de("YouTube reports that @canal has reached the number of videos it may upload today")
                .red()).isEqualTo(Platform.YOUTUBE);
        assertThat(TopeDelProveedor.de("{\"error_code\":\"daily_cap_reached\"}").red()).isNull();

        // El ritmo de la llave sigue siendo otra cosa: pausa de la cola, como siempre.
        assertThat(TopeDelProveedor.de("{\"message\":\"Too many requests\"}")).isNull();
        assertThat(TopeDelProveedor.de(null)).isNull();
    }

    @Test
    @DisplayName("vuelve a haber hueco cuando la más antigua de la ventana cumple 24 horas")
    void cuandoSeLibera() {
        LocalDateTime ahora = LocalDateTime.of(2026, 10, 27, 16, 53);
        // La primera de la ráfaga salió a las 11:50 del 27: hay hueco el 28 a las 12:00.
        TopeDelProveedor topes = conPrimera(LocalDateTime.of(2026, 10, 27, 11, 50));

        assertThat(topes.cuandoSeLibera(cuenta, Platform.FACEBOOK, ahora))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 12, 0));
    }

    @Test
    @DisplayName("si se libera de madrugada, sale a las 9; si es de noche, al día siguiente a las 9")
    void siempreDeDia() {
        assertThat(TopeDelProveedor.deDia(LocalDateTime.of(2026, 10, 28, 3, 15)))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 9, 0));
        assertThat(TopeDelProveedor.deDia(LocalDateTime.of(2026, 10, 28, 22, 40)))
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 9, 0));
        assertThat(TopeDelProveedor.deDia(LocalDateTime.of(2026, 10, 28, 15, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 15, 0));
    }

    @Test
    @DisplayName("sin saber la red o sin datos, prueba dentro de seis horas, de día")
    void sinDatos() {
        LocalDateTime ahora = LocalDateTime.of(2026, 10, 27, 10, 0);
        assertThat(conPrimera(null).cuandoSeLibera(cuenta, null, ahora)).isEqualTo(LocalDateTime.of(2026, 10, 27, 16, 0));
    }

    @Test
    @DisplayName("a la persona se le dice qué pasó y que sale sola, y cuándo")
    void mensaje() {
        String m = TopeDelProveedor.mensaje(new TopeDelProveedor.Tope(Platform.FACEBOOK),
                LocalDateTime.of(2026, 10, 28, 12, 0));
        assertThat(m).startsWith("Facebook ya llegó a su límite de publicaciones del día")
                .contains("saldrá sola").contains("12:00");
    }

    private TopeDelProveedor conPrimera(LocalDateTime primera) {
        PostTargetRepository destinos = mock(PostTargetRepository.class);
        when(destinos.primeraPublicadaDesde(eq(cuenta.toString()), any(), any())).thenReturn(primera);
        LimitesConfigurables limites = mock(LimitesConfigurables.class);
        when(limites.ventanaRodanteHoras()).thenReturn(24);
        return new TopeDelProveedor(destinos, limites);
    }
}
