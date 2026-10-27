package com.metricol.api.service.social;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.metricol.api.enums.Platform;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.service.limits.LimitesConfigurables;

/**
 * Un 429 de upload-post que no es "baja el ritmo" sino "esta cuenta ya publicó todo lo que puede hoy en
 * esta red": el tope diario (Facebook 25, TikTok 15 en 24 h corridas) o el límite de subidas de un canal
 * de YouTube.
 *
 * <p>Son dos cosas distintas y se tratan distinto. El ritmo es de la llave, compartida por todos: se pausa
 * la cola entera unos minutos. El tope es de UNA cuenta y no se libera en minutos sino en horas: pausar a
 * todos no sirve de nada y reintentar cada cinco minutos tampoco. El 27 oct 2026 dos publicaciones de una
 * sola cuenta pausaron la cola de todos los negocios 54 veces en cinco horas, y cada una iba ya por su
 * sexto intento de cuatro.
 *
 * <p>Aquí se reconoce el tope y se calcula cuándo vuelve a haber hueco: cuando la publicación más antigua
 * de esa red en la ventana cumpla sus horas. Si eso cae de noche, se pasa a la mañana.
 */
@Component
public class TopeDelProveedor {

    /** Un tope reconocido. {@code red} es nula si el proveedor no dijo de qué red. */
    public record Tope(Platform red) {
    }

    private static final Pattern RED_DEL_TOPE = Pattern.compile("daily posting cap on (\\w+)", Pattern.CASE_INSENSITIVE);
    private static final LocalTime DESDE = LocalTime.of(9, 0);
    private static final LocalTime HASTA = LocalTime.of(21, 0);
    private static final DateTimeFormatter CUANDO =
            DateTimeFormatter.ofPattern("EEE d MMM 'a las' HH:mm", Locale.forLanguageTag("es-MX"));

    private final PostTargetRepository destinos;
    private final LimitesConfigurables limites;

    public TopeDelProveedor(PostTargetRepository destinos, LimitesConfigurables limites) {
        this.destinos = destinos;
        this.limites = limites;
    }

    /** El tope que dice esta respuesta de upload-post, o {@code null} si es otra cosa (el ritmo, por ejemplo). */
    public static Tope de(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        String c = cuerpo.toLowerCase(Locale.ROOT);
        if (c.contains("may upload today")) {
            return new Tope(Platform.YOUTUBE);
        }
        Matcher red = RED_DEL_TOPE.matcher(cuerpo);
        if (red.find()) {
            return new Tope(plataforma(red.group(1)));
        }
        return c.contains("daily_cap_reached") ? new Tope(null) : null;
    }

    /**
     * Cuándo vuelve a haber hueco en esa red para ese espacio: la más antigua de la ventana más sus horas,
     * con diez minutos de margen. Sin red o sin datos, dentro de seis horas. Siempre de día.
     */
    public LocalDateTime cuandoSeLibera(UUID workspace, Platform red, LocalDateTime ahora) {
        int horas = Math.max(1, limites.ventanaRodanteHoras());
        LocalDateTime cuando = null;
        if (red != null) {
            LocalDateTime primera = destinos.primeraPublicadaDesde(workspace.toString(), red, ahora.minusHours(horas));
            if (primera != null) {
                cuando = primera.plusHours(horas).plusMinutes(10);
            }
        }
        if (cuando == null || !cuando.isAfter(ahora)) {
            cuando = ahora.plusHours(6);
        }
        return deDia(cuando);
    }

    /** Lo que lee la persona: qué pasó y que saldrá sola, y cuándo. */
    public static String mensaje(Tope tope, LocalDateTime cuando) {
        String red = tope.red() == null ? "La red" : tope.red().getLabel();
        return red + " ya llegó a su límite de publicaciones del día en esta cuenta. Esta saldrá sola el "
                + CUANDO.format(cuando) + ", cuando vuelva a haber espacio.";
    }

    /** Antes de las 9, a las 9; después de las 21, a las 9 del día siguiente. */
    static LocalDateTime deDia(LocalDateTime cuando) {
        LocalTime hora = cuando.toLocalTime();
        if (hora.isBefore(DESDE)) {
            return cuando.toLocalDate().atTime(DESDE);
        }
        if (hora.isAfter(HASTA)) {
            return cuando.toLocalDate().plusDays(1).atTime(DESDE);
        }
        return cuando;
    }

    private static Platform plataforma(String nombre) {
        try {
            return Platform.valueOf(nombre.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
