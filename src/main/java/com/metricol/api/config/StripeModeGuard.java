package com.metricol.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Una llave de Stripe REAL solo puede estar en producción.
 *
 * <p>Las llaves son de cada ambiente (ver {@code app.stripe} en los perfiles),
 * pero un descuido —copiar el .env de producción a QA, o pegar la llave real en
 * local— cobraría a tarjetas de verdad desde un ambiente de pruebas. Este
 * guardia lo convierte en un error al arrancar, que se ve en el acto, en vez de
 * un cobro que se descubre en el estado de cuenta.
 */
@Component
public class StripeModeGuard {

    private static final Logger log = LoggerFactory.getLogger(StripeModeGuard.class);

    private final StripeProperties props;
    private final Environment entorno;

    public StripeModeGuard(StripeProperties props, Environment entorno) {
        this.props = props;
        this.entorno = entorno;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verificar() {
        comprobar();
        log.info("Cobros con Stripe: {}", switch (props.modo()) {
            case APAGADO -> "apagados (sin llave)";
            case PRUEBA -> "modo PRUEBA (el dinero es de mentira)";
            case REAL -> "modo REAL (cobra de verdad)";
        });
    }

    /** Separado para poder probarlo sin levantar la aplicación. */
    void comprobar() {
        if (props.modo() == StripeProperties.Modo.REAL && !entorno.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Hay una llave REAL de Stripe (sk_live_) fuera del perfil prod. "
                    + "Las llaves reales solo van en producción: usa una sk_test_ en este ambiente.");
        }
    }
}
