package com.metricol.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.StripeProperties.Modo;

class StripePropertiesTest {

    private StripeProperties con(String llave, String webhook) {
        StripeProperties p = new StripeProperties();
        p.setSecretKey(llave);
        p.setWebhookSecret(webhook);
        return p;
    }

    @Test
    @DisplayName("sin llave no se cobra: es el estado de hoy")
    void sinLlaveEstaApagado() {
        StripeProperties p = new StripeProperties();
        assertThat(p.cobrosActivos()).isFalse();
        assertThat(p.modo()).isEqualTo(Modo.APAGADO);
        assertThat(con("   ", "").modo()).isEqualTo(Modo.APAGADO);
    }

    @Test
    @DisplayName("la propia llave dice si es de prueba o real")
    void elModoLoDiceLaLlave() {
        assertThat(con("sk_test_abc", "").modo()).isEqualTo(Modo.PRUEBA);
        assertThat(con("rk_test_abc", "").modo()).isEqualTo(Modo.PRUEBA);
        assertThat(con("sk_live_abc", "").modo()).isEqualTo(Modo.REAL);
        assertThat(con("rk_live_abc", "").modo()).isEqualTo(Modo.REAL);
    }

    @Test
    @DisplayName("ante la duda, real: nunca se llama prueba a algo que cobra")
    void loDesconocidoSeTrataComoReal() {
        assertThat(con("algo_raro", "").modo()).isEqualTo(Modo.REAL);
    }

    @Test
    @DisplayName("sin secreto del webhook no se recibe ningun aviso, aunque haya llave")
    void avisosPidenElSecretoDeFirma() {
        assertThat(con("sk_test_abc", "").puedeRecibirAvisos()).isFalse();
        assertThat(con("sk_test_abc", "whsec_abc").puedeRecibirAvisos()).isTrue();
        assertThat(con("", "whsec_abc").puedeRecibirAvisos()).isFalse();
    }
}
