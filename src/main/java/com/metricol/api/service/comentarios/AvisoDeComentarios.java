package com.metricol.api.service.comentarios;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.metricol.api.enums.Permission;
import com.metricol.api.repository.ComentarioRepository;
import com.metricol.api.service.avisos.AvisosPush;

/**
 * El aviso al teléfono cuando hay comentarios sin contestar.
 *
 * <p>Toda esta clase existe para NO avisar. Un comentario es, de lejos, lo que
 * más seguido pasa en una cuenta activa: avisar de cada uno convierte la app en
 * algo que la gente silencia la primera semana, y una app silenciada ya no
 * sirve para avisar de nada, tampoco de lo que sí importaba.
 *
 * <p>Las reglas, todas juntas:
 *
 * <ul>
 * <li><b>uno agrupado</b> por espacio, nunca uno por comentario;
 * <li>como mucho uno cada {@code app.comentarios.aviso.cada-horas} (2);
 * <li>nada entre las 22:00 y las 8:00 — lo de la noche se junta con el primero
 * de la mañana;
 * <li>tope de {@code app.comentarios.aviso.tope-diario} (4) al día;
 * <li>lo que no se avisó no se pierde: sigue en la bandeja, y el siguiente
 * aviso lo cuenta.
 * </ul>
 *
 * <p>Lo que se recuerda (cuándo se avisó por última vez a cada espacio) vive en
 * memoria a propósito: si la API se reinicia, lo peor que pasa es un aviso de
 * más, y no vale una tabla.
 */
@Service
public class AvisoDeComentarios {

    private static final Logger log = LoggerFactory.getLogger(AvisoDeComentarios.class);

    private final ComentarioRepository comentarios;
    private final AvisosPush push;

    @Value("${app.comentarios.aviso.cada-horas:2}")
    private int cadaHoras;

    @Value("${app.comentarios.aviso.tope-diario:4}")
    private int topeDiario;

    @Value("${app.comentarios.aviso.desde-hora:8}")
    private int desdeHora;

    @Value("${app.comentarios.aviso.hasta-hora:22}")
    private int hastaHora;

    /** Por espacio: cuándo se avisó por última vez y cuántas veces hoy. */
    private final Map<UUID, Ultimo> ultimos = new ConcurrentHashMap<>();

    /** Por espacio: cuándo alguien abrió la bandeja por última vez. */
    private final Map<UUID, LocalDateTime> mirando = new ConcurrentHashMap<>();

    /**
     * Cuánto se considera que alguien "está en la bandeja" desde que la pidió.
     * Avisar al teléfono de algo que la persona está leyendo en ese momento es
     * la clase de aviso que hace desinstalar una app.
     */
    private static final int MINUTOS_MIRANDO = 5;

    private record Ultimo(LocalDateTime cuando, int hoy) {
    }

    public AvisoDeComentarios(ComentarioRepository comentarios, AvisosPush push) {
        this.comentarios = comentarios;
        this.push = push;
    }

    /**
     * Mira si hay pendientes recientes y avisa a los espacios a los que toca.
     *
     * <p>Se llama al final de cada vuelta del worker, haya traído algo o no:
     * lo que no se avisó de noche tiene que salir por la mañana, y de noche no
     * hace falta que haya entrado nada nuevo para que haya algo esperando.
     */
    public void avisarDeLoNuevo() {
        if (!push.activo()) {
            return;
        }
        LocalDateTime ahora = LocalDateTime.now();
        // Se mira lo de las últimas 24 h: así lo que se calló de noche se
        // cuenta igual por la mañana, en vez de perderse por haber entrado
        // fuera de la última vuelta.
        List<Object[]> porEspacio = comentarios.nuevosSinAtenderPorEspacio(ahora.minusHours(24));
        for (Object[] fila : porEspacio) {
            UUID espacio = UUID.fromString(String.valueOf(fila[0]));
            long cuantos = ((Number) fila[1]).longValue();
            long cuentas = ((Number) fila[2]).longValue();
            if (cuantos <= 0 || !toca(espacio, ahora)) {
                continue;
            }
            // A quien puede contestar, no a quien puede programar: avisarle de
            // un comentario a quien no puede responderlo es ruido puro.
            boolean mandado = push.avisarAQuienPuede(espacio, Permission.COMMENT_REPLY, "Comentarios nuevos",
                    texto(cuantos, cuentas),
                    Map.of("tipo", "comentarios", "cuantos", String.valueOf(cuantos)));
            if (mandado) {
                apuntar(espacio, ahora);
            }
        }
    }

    /**
     * El texto, en palabras de quien lo lee. Nunca "1 comentarios", nunca el
     * nombre de la red si son varias — "en 2 cuentas" se entiende sin pensar.
     */
    static String texto(long cuantos, long cuentas) {
        String cuales = cuantos == 1 ? "1 comentario nuevo" : cuantos + " comentarios nuevos";
        if (cuentas > 1) {
            return cuales + " en " + cuentas + " cuentas, esperando respuesta.";
        }
        return cuales + " esperando respuesta.";
    }

    /** Alguien acaba de abrir la bandeja de ese espacio. Lo llama quien la sirve. */
    public void estanMirando(UUID espacio) {
        if (espacio != null) {
            mirando.put(espacio, LocalDateTime.now());
        }
    }

    /** ¿Se puede avisar a este espacio ahora mismo? */
    boolean toca(UUID espacio, LocalDateTime ahora) {
        if (deNoche(ahora.toLocalTime())) {
            return false;
        }
        LocalDateTime abierta = mirando.get(espacio);
        if (abierta != null && abierta.isAfter(ahora.minusMinutes(MINUTOS_MIRANDO))) {
            return false;
        }
        Ultimo ultimo = ultimos.get(espacio);
        if (ultimo == null) {
            return true;
        }
        boolean mismoDia = ultimo.cuando().toLocalDate().equals(ahora.toLocalDate());
        if (mismoDia && ultimo.hoy() >= topeDiario) {
            return false;
        }
        return !ultimo.cuando().isAfter(ahora.minusHours(cadaHoras));
    }

    /**
     * De noche no se avisa. El tramo se define por sus extremos y no por una
     * resta, para que {@code desde=8, hasta=22} se lea igual que se dice: "de
     * 8 de la mañana a 10 de la noche".
     */
    private boolean deNoche(LocalTime hora) {
        return hora.isBefore(LocalTime.of(desdeHora, 0)) || !hora.isBefore(LocalTime.of(hastaHora, 0));
    }

    /** Package-private para poder probar los topes sin esperar horas de verdad. */
    void apuntar(UUID espacio, LocalDateTime ahora) {
        ultimos.compute(espacio, (k, previo) -> {
            boolean mismoDia = previo != null && previo.cuando().toLocalDate().equals(ahora.toLocalDate());
            return new Ultimo(ahora, mismoDia ? previo.hoy() + 1 : 1);
        });
        log.debug("Avisado de comentarios a {}", espacio);
    }

    /** Para las pruebas: los topes a mano, sin levantar Spring. */
    void configurar(int cadaHoras, int topeDiario, int desdeHora, int hastaHora) {
        this.cadaHoras = cadaHoras;
        this.topeDiario = topeDiario;
        this.desdeHora = desdeHora;
        this.hastaHora = hastaHora;
    }
}
