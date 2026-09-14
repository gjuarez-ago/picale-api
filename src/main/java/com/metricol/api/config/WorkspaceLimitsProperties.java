package com.metricol.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Semillas de los topes que no son «cuántas al día por red».
 *
 * <p>Semillas y no valores: solo se usan para llenar la tabla {@code app_limits}
 * la primera vez. Después manda la tabla (ver {@code LimitesConfigurables}).
 *
 * <p>Existen porque las cuotas por red protegían al proveedor pero no a la
 * plataforma: nada frenaba a un workspace que creara mil publicaciones, las
 * dejara todas pendientes o llamara a la IA en bucle. Ninguno de esos tres
 * gastos es del proveedor; son nuestros.
 */
@Configuration
@ConfigurationProperties(prefix = "app.limits")
@Getter
@Setter
public class WorkspaceLimitsProperties {

    /** Publicaciones que un workspace puede tener esperando salir (en cola o programadas). */
    private int maxPendingPosts = 200;

    /** Publicaciones que un workspace puede crear en un día natural. */
    private int maxPostsPerDay = 120;

    /** Llamadas a la IA por workspace y día. Cada una cuesta dinero en OpenAI. */
    private int maxAiCallsPerDay = 200;

    /**
     * Cuánto se pausa la cola entera cuando el proveedor contesta 429 sin
     * decir cuánto esperar. Cinco minutos: lo que tarda un tope por minuto en
     * vaciarse, y poco para lo que dura una publicación programada.
     */
    private int providerPauseSeconds = 300;

    /**
     * Horas de la ventana móvil con la que Meta cuenta sus 25. Nuestro
     * contador se reinicia a medianoche; la red no. Con esto se comprueba
     * además lo publicado en las últimas N horas.
     */
    private int rollingWindowHours = 24;

    /**
     * Publicaciones al día para TODA la plataforma, sumando workspaces. 0 =
     * sin tope, que es lo correcto con un plan de subidas ilimitadas; se deja
     * para el día que el plan cambie o el proveedor imponga uno.
     */
    private long globalDailyPublishes = 0;
}
