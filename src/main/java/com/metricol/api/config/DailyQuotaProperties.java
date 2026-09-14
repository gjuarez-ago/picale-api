package com.metricol.api.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.metricol.api.enums.Platform;

import lombok.Getter;
import lombok.Setter;

/**
 * Cuántas publicaciones al día acepta cada red.
 *
 * <p>Estos topes no los inventa esta plataforma: los pone el proveedor. La
 * API de contenido de Instagram admite 25 publicaciones cada 24 horas por
 * cuenta, y las demás redes tienen su propia cifra. Pasarse no da un error
 * amable: la red empieza a rechazar todo, y con suficientes rechazos la
 * aplicación entera se queda marcada.
 *
 * <p>Por eso el contador se lleva aquí y no se descubre a golpes. Cuando una
 * red se agota, la publicación no falla: se aplaza al día siguiente (ver
 * {@code PublishJobStatus.DEFERRED}), que es lo que una persona esperaría
 * que pasara.
 *
 * <p>Va por configuración porque los proveedores mueven estas cifras y
 * porque un plan de pago distinto trae topes distintos.
 */
@Configuration
@ConfigurationProperties(prefix = "app.quota")
@Getter
@Setter
public class DailyQuotaProperties {

    /** Publicaciones al día por red. 0 o ausente = sin tope conocido. */
    private Map<Platform, Integer> daily = new EnumMap<>(Platform.class);

    /** El tope de esa red, o {@code null} si no hay ninguno configurado. */
    public Integer dailyFor(Platform platform) {
        Integer tope = daily.get(platform);
        return tope == null || tope <= 0 ? null : tope;
    }

    /**
     * Cuándo se reinicia el contador: el arranque del día siguiente.
     *
     * <p>Es medianoche local y no una ventana móvil de 24 horas contadas
     * desde cada publicación. La ventana móvil sería más fiel a lo que mide
     * la red, pero obliga a guardar cada publicación con su hora y a
     * recalcular en cada consulta; el día natural se cuenta con una fila por
     * red y se explica en una frase. El costo de la simplificación es que el
     * tope se puede sentir estricto justo después de medianoche, y a cambio
     * nunca se pasa del límite real.
     */
    public static LocalDateTime siguienteReinicio() {
        return LocalDate.now().plusDays(1).atStartOfDay();
    }

    /** Un poco después del reinicio, para reintentar sin pelearse con el reloj. */
    public static LocalDateTime cuandoReintentar() {
        return LocalDate.now().plusDays(1).atTime(LocalTime.of(0, 5));
    }
}
