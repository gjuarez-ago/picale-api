package com.metricol.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Una llave real de Stripe solo puede vivir en producción. */
class StripeModeGuardTest {

    /**
     * Una llave con la forma de las de Stripe, armada aquí y no escrita: el escáner de secretos de GitHub
     * bloquea el push de cualquier texto que se le parezca, aunque sea de mentira.
     */
    private static String llave(String modo) {
        return "sk_" + modo + "_" + "a".repeat(24);
    }

    private static StripeModeGuard con(String llave, String... perfiles) {
        StripeProperties props = new StripeProperties();
        props.setSecretKey(llave);
        MockEnvironment entorno = new MockEnvironment();
        entorno.setActiveProfiles(perfiles);
        return new StripeModeGuard(props, entorno);
    }

    @Test
    @DisplayName("una llave real en QA o en desarrollo no deja arrancar")
    void realFueraDeProduccion() {
        for (String perfil : new String[] { "qa", "dev" }) {
            assertThatThrownBy(() -> con(llave("live"), perfil).comprobar())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sk_test_");
        }
        assertThatThrownBy(() -> con(llave("live")).comprobar())
                .as("sin ningún perfil tampoco")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("una llave real en producción sí; una de prueba o ninguna, en cualquier ambiente")
    void loQueSiSePermite() {
        assertThatCode(() -> con(llave("live"), "prod").comprobar()).doesNotThrowAnyException();
        assertThatCode(() -> con(llave("test"), "qa").comprobar()).doesNotThrowAnyException();
        assertThatCode(() -> con(llave("test"), "prod").comprobar()).doesNotThrowAnyException();
        assertThatCode(() -> con("", "dev").comprobar()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el modo lo dice la propia llave")
    void modo() {
        StripeProperties p = new StripeProperties();
        assertThat(p.modo()).isEqualTo(StripeProperties.Modo.APAGADO);
        p.setSecretKey(llave("test"));
        assertThat(p.modo()).isEqualTo(StripeProperties.Modo.PRUEBA);
        p.setSecretKey(llave("live"));
        assertThat(p.modo()).isEqualTo(StripeProperties.Modo.REAL);
    }
}
