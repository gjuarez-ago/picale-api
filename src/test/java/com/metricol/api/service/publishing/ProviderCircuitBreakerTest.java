package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * La pausa de la cola ante un 429.
 *
 * <p>Sin base ni contexto: es aritmética de fechas, y lo único que puede
 * fallar es el criterio de «se respeta la pausa más larga», que es justo lo
 * que evita que dos 429 seguidos acorten la espera.
 */
class ProviderCircuitBreakerTest {

    @Test
    void empiezaCerradoYSeAbreConUnaPausa() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker();
        assertThat(breaker.abierto()).isFalse();
        assertThat(breaker.hasta()).isNull();

        LocalDateTime hasta = breaker.abrir(Duration.ofMinutes(5), "prueba");

        assertThat(breaker.abierto()).isTrue();
        assertThat(breaker.hasta()).isEqualTo(hasta);
        assertThat(hasta).isAfter(LocalDateTime.now().plusMinutes(4));
    }

    @Test
    void dosPausasSeguidasSeQuedanConLaMasLarga() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker();

        LocalDateTime larga = breaker.abrir(Duration.ofMinutes(10), "primer 429");
        LocalDateTime resultado = breaker.abrir(Duration.ofMinutes(1), "segundo 429");

        assertThat(resultado).isEqualTo(larga);
    }

    @Test
    void unaPausaMasLargaSustituyeALaCorta() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker();

        breaker.abrir(Duration.ofMinutes(1), "corta");
        LocalDateTime larga = breaker.abrir(Duration.ofMinutes(10), "larga");

        assertThat(breaker.hasta()).isEqualTo(larga);
    }

    @Test
    void cerrarLevantaLaPausa() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker();
        breaker.abrir(Duration.ofMinutes(5), "prueba");

        breaker.cerrar();

        assertThat(breaker.abierto()).isFalse();
        assertThat(breaker.hasta()).isNull();
    }
}
