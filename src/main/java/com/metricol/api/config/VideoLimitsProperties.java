package com.metricol.api.config;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.metricol.api.enums.Platform;

import lombok.Getter;
import lombok.Setter;

/**
 * Cuánto video acepta cada red, en segundos.
 *
 * <p>Va por configuración y no como constantes en el código porque estas
 * cifras las mueven las redes cada temporada —Instagram pasó de 60 a 90
 * segundos en Reels, TikTok de 3 a 10 minutos— y ajustarlas no debería
 * requerir un despliegue.
 *
 * <p><b>Esto es una guía, no la autoridad.</b> Quien decide de verdad es la
 * red: si un límite de aquí se queda viejo y deja pasar un video más largo,
 * upload-post lo rechaza y el motivo acaba en
 * {@code PostTarget.errorMessage} igual que cualquier otro fallo. El valor de
 * tenerlo aquí es avisar antes de subir un archivo grande para nada, y poder
 * enseñar el tope en la pantalla de publicar.
 */
@Configuration
@ConfigurationProperties(prefix = "app.video")
@Getter
@Setter
public class VideoLimitsProperties {

    /**
     * Tope por red. Un valor de 0 o ausente significa "sin límite conocido":
     * no se bloquea nada, porque inventar un tope sería peor que no tenerlo.
     */
    private Map<Platform, Integer> maxSeconds = new EnumMap<>(Platform.class);

    /** El tope de esa red, o {@code null} si no hay ninguno configurado. */
    public Integer maxSecondsFor(Platform platform) {
        Integer tope = maxSeconds.get(platform);
        return tope == null || tope <= 0 ? null : tope;
    }

    /**
     * ¿Este video excede lo que acepta la red?
     *
     * <p>Con duración desconocida contesta que no: la app todavía no siempre
     * la manda, y bloquear una publicación por falta de un dato opcional
     * sería peor que dejar que la red opine.
     */
    public boolean excede(Platform platform, Integer duracionSegundos) {
        if (duracionSegundos == null || duracionSegundos <= 0) {
            return false;
        }
        Integer tope = maxSecondsFor(platform);
        return tope != null && duracionSegundos > tope;
    }

    /**
     * "45 s", "1 min", "1:30 min": como lo lee la persona.
     *
     * <p>Antes 90 segundos salían como "90 s", que se lee como un número y no
     * como un tiempo: nadie piensa un reel en segundos. Con minutos y segundos
     * se lee de un vistazo, y la app usa este mismo texto en su tarjeta de
     * formatos — el servidor lo manda ya formateado para que los dos digan lo
     * mismo.
     */
    public static String legible(int segundos) {
        if (segundos < 60) {
            return segundos + " s";
        }
        int minutos = segundos / 60;
        int resto = segundos % 60;
        return resto == 0 ? minutos + " min" : String.format("%d:%02d min", minutos, resto);
    }
}
