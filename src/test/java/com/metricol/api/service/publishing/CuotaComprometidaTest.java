package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * La regla de «¿cabe una más?» al programar.
 *
 * <p>Lo comprometido y lo ya publicado hoy se suman, y la que se quiere
 * guardar cuenta como una más. Es la cuenta que decide si alguien puede
 * dejar la número 26 para el mismo martes; equivocarla por uno es dejar
 * pasar justo la que la red rechaza.
 */
class CuotaComprometidaTest {

    @Test
    void cabeMientrasNoSeLlegueAlTope() {
        assertThat(CuotaComprometida.cabe(0, 0, 25)).isTrue();
        assertThat(CuotaComprometida.cabe(24, 0, 25)).isTrue();
        assertThat(CuotaComprometida.cabe(0, 24, 25)).isTrue();
        assertThat(CuotaComprometida.cabe(10, 14, 25)).isTrue();
    }

    @Test
    void laQueLlenaElTopeYaNoCabe() {
        assertThat(CuotaComprometida.cabe(25, 0, 25)).isFalse();
        assertThat(CuotaComprometida.cabe(0, 25, 25)).isFalse();
        assertThat(CuotaComprometida.cabe(10, 15, 25)).isFalse();
    }

    @Test
    void loComprometidoYLoPublicadoSeSuman() {
        // 12 esperando salir hoy y 12 que ya salieron: la 25 cabe, la 26 no.
        assertThat(CuotaComprometida.cabe(12, 12, 25)).isTrue();
        assertThat(CuotaComprometida.cabe(13, 12, 25)).isFalse();
    }
}
