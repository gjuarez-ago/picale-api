package com.metricol.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Lo que la API necesita para cobrar con Stripe. Las tres son secretos o ids de
 * la cuenta de Stripe y viven solo en el {@code .env.vps} del servidor.
 *
 * <p>Vacia la llave = cobros apagados: la app funciona igual y no se le cobra a
 * nadie. Es el estado de hoy, y el de cualquier entorno de desarrollo.
 *
 * <p>El modo (prueba o real) no se configura aparte: lo dice la propia llave.
 * Una {@code sk_test_} solo mueve dinero de mentira, una {@code sk_live_} mueve
 * el de verdad, y por eso {@link #modo()} existe: para que lo primero que diga
 * el log y la pantalla de cobros sea con cuál se esta hablando.
 */
@Configuration
@ConfigurationProperties(prefix = "app.stripe")
@Getter
@Setter
public class StripeProperties {

    public enum Modo {
        /** Sin llave: no se cobra. */
        APAGADO,
        /** Llave de prueba: el dinero es de mentira. */
        PRUEBA,
        /** Llave real: cobra de verdad. */
        REAL
    }

    /** {@code sk_test_...} / {@code sk_live_...}, o una restringida {@code rk_...}. */
    private String secretKey = "";

    /**
     * El secreto con el que Stripe firma cada aviso que manda a
     * {@code /api/v1/billing/webhook} ({@code whsec_...}). Sin el no se puede
     * creer ningun aviso: cualquiera podria decir que pago.
     */
    private String webhookSecret = "";

    /**
     * El precio mensual de un espacio de trabajo ({@code price_...}). Es un id y
     * no un monto: el monto, la moneda y el ciclo se cambian en Stripe sin
     * tocar el codigo.
     */
    private String priceWorkspace = "";

    public boolean cobrosActivos() {
        return secretKey != null && !secretKey.isBlank();
    }

    /** Puede recibir avisos de Stripe: hay llave y hay con que comprobar su firma. */
    public boolean puedeRecibirAvisos() {
        return cobrosActivos() && webhookSecret != null && !webhookSecret.isBlank();
    }

    public Modo modo() {
        if (!cobrosActivos()) {
            return Modo.APAGADO;
        }
        // "sk_test_", "rk_test_": el segundo bloque manda.
        return secretKey.strip().contains("_test_") ? Modo.PRUEBA : Modo.REAL;
    }
}
