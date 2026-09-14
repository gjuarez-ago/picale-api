package com.metricol.api.service.limits;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.DailyQuotaProperties;
import com.metricol.api.config.WorkspaceLimitsProperties;
import com.metricol.api.entity.AppLimit;
import com.metricol.api.enums.Platform;
import com.metricol.api.repository.AppLimitRepository;

/**
 * Los topes de la plataforma, leídos de la tabla {@code app_limits}.
 *
 * <p>Es el único sitio por donde se pregunta «¿cuánto es el máximo de…?». Antes
 * cada tope vivía en su clase de propiedades y cambiar uno era redesplegar;
 * ahora es un {@code UPDATE} en la base y en medio minuto lo ven todos los
 * procesos.
 *
 * <p><b>Cómo se llena.</b> Al arrancar se siembra cada clave que falte con el
 * valor de las propiedades ({@code app.quota.daily.*}, {@code app.limits.*}).
 * Una fila que ya existe no se toca jamás: si alguien la editó en la base, eso
 * es lo que vale, y un despliegue no puede pisarlo. Para volver al valor de
 * configuración basta borrar la fila y reiniciar.
 *
 * <p><b>Cómo se lee.</b> Con una caché en memoria que se renueva cada
 * {@value #TTL_SEGUNDOS} segundos. Los workers preguntan por la cuota en cada
 * publicación, y una consulta a la base por cada pregunta sería pagar por
 * algo que casi nunca cambia. Si la base no contesta, se sigue con lo último
 * leído: un tope viejo es mejor que ninguno.
 */
@Service
public class LimitesConfigurables {

    private static final Logger log = LoggerFactory.getLogger(LimitesConfigurables.class);

    static final int TTL_SEGUNDOS = 30;

    // Las claves, tal como quedan en la tabla. Se leen igual desde SQL:
    //   update app_limits set valor = 30, updated_at = now() where clave = 'quota.daily.INSTAGRAM';
    public static final String CUOTA_DIARIA = "quota.daily.";
    public static final String CUOTA_GLOBAL_DIARIA = "quota.global.daily";
    public static final String VENTANA_RODANTE_HORAS = "quota.rolling.hours";
    public static final String MAX_PENDIENTES = "limits.posts.max_pending";
    public static final String MAX_POR_DIA = "limits.posts.max_per_day";
    public static final String MAX_IA_POR_DIA = "limits.ai.max_calls_per_day";
    public static final String PAUSA_PROVEEDOR_SEGUNDOS = "limits.provider.pause_seconds";

    private final AppLimitRepository repository;
    private final DailyQuotaProperties cuotas;
    private final WorkspaceLimitsProperties limites;

    private volatile Map<String, Long> cache = Map.of();
    private volatile Instant cargado = Instant.EPOCH;

    public LimitesConfigurables(
            AppLimitRepository repository,
            DailyQuotaProperties cuotas,
            WorkspaceLimitsProperties limites) {
        this.repository = repository;
        this.cuotas = cuotas;
        this.limites = limites;
    }

    /** La clave de la cuota diaria de una red: {@code quota.daily.INSTAGRAM}. */
    public static String claveCuota(Platform platform) {
        return CUOTA_DIARIA + platform.name();
    }

    // ------------------------------------------------------------------
    // Lo que preguntan los demás
    // ------------------------------------------------------------------

    /** Publicaciones al día en esa red para un workspace, o {@code null} si no hay tope. */
    public Integer cuotaDiaria(Platform platform) {
        long valor = valor(claveCuota(platform), semillaCuota(platform));
        return valor <= 0 ? null : (int) valor;
    }

    /** Publicaciones al día para toda la plataforma. 0 = sin tope. */
    public long cuotaGlobalDiaria() {
        return Math.max(0, valor(CUOTA_GLOBAL_DIARIA, limites.getGlobalDailyPublishes()));
    }

    public int ventanaRodanteHoras() {
        return (int) Math.max(0, valor(VENTANA_RODANTE_HORAS, limites.getRollingWindowHours()));
    }

    public int maxPendientes() {
        return (int) Math.max(0, valor(MAX_PENDIENTES, limites.getMaxPendingPosts()));
    }

    public int maxPorDia() {
        return (int) Math.max(0, valor(MAX_POR_DIA, limites.getMaxPostsPerDay()));
    }

    public int maxIaPorDia() {
        return (int) Math.max(0, valor(MAX_IA_POR_DIA, limites.getMaxAiCallsPerDay()));
    }

    public int pausaProveedorSegundos() {
        return (int) Math.max(1, valor(PAUSA_PROVEEDOR_SEGUNDOS, limites.getProviderPauseSeconds()));
    }

    /**
     * El valor de una clave, o {@code porDefecto} si la tabla no la tiene.
     *
     * <p>Público para que un tope nuevo se pueda consultar sin añadir un
     * método aquí; los de arriba existen para que el resto del código no
     * tenga que saber cómo se llama cada clave.
     */
    public long valor(String clave, long porDefecto) {
        refrescarSiToca();
        Long guardado = cache.get(clave);
        return guardado == null ? porDefecto : guardado;
    }

    /**
     * Escribe un tope y lo hace efectivo en el acto para este proceso.
     *
     * <p>Los demás procesos lo ven en cuanto venza su caché. Lo usan las
     * pruebas y quedará para un panel de operación; a mano, un {@code UPDATE}
     * hace lo mismo.
     */
    @Transactional
    public void establecer(String clave, long valor, String descripcion) {
        AppLimit fila = repository.findById(clave).orElseGet(() -> AppLimit.builder().clave(clave).build());
        fila.setValor(valor);
        if (descripcion != null) {
            fila.setDescripcion(descripcion);
        }
        fila.setUpdatedAt(LocalDateTime.now());
        repository.save(fila);
        invalidar();
    }

    /** Obliga a releer la tabla en la próxima consulta. */
    public void invalidar() {
        cargado = Instant.EPOCH;
    }

    // ------------------------------------------------------------------
    // Semilla y caché
    // ------------------------------------------------------------------

    /**
     * Llena lo que falte. Corre cuando la aplicación ya está lista para
     * atender, y no antes, para que un fallo aquí no impida arrancar: sin
     * filas se sigue con los valores de configuración, que es lo que había.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void sembrar() {
        try {
            int nuevas = 0;
            for (Map.Entry<String, Semilla> entrada : semillas().entrySet()) {
                if (repository.existsById(entrada.getKey())) {
                    continue;
                }
                repository.save(AppLimit.builder()
                        .clave(entrada.getKey())
                        .valor(entrada.getValue().valor())
                        .descripcion(entrada.getValue().descripcion())
                        .updatedAt(LocalDateTime.now())
                        .build());
                nuevas++;
            }
            if (nuevas > 0) {
                log.info("Topes sembrados en app_limits: {} clave(s) nueva(s)", nuevas);
            }
        } catch (Exception ex) {
            log.warn("No se pudo sembrar app_limits; se sigue con los valores de configuracion: {}",
                    ex.getMessage());
        } finally {
            invalidar();
        }
    }

    private Map<String, Semilla> semillas() {
        Map<String, Semilla> semillas = new LinkedHashMap<>();
        for (Platform platform : Platform.values()) {
            semillas.put(claveCuota(platform), new Semilla(semillaCuota(platform),
                    "Publicaciones al dia por workspace en " + platform.getLabel() + ". 0 = sin tope."));
        }
        semillas.put(CUOTA_GLOBAL_DIARIA, new Semilla(limites.getGlobalDailyPublishes(),
                "Publicaciones al dia de TODA la plataforma, sumando workspaces. 0 = sin tope."));
        semillas.put(VENTANA_RODANTE_HORAS, new Semilla(limites.getRollingWindowHours(),
                "Horas de la ventana movil con la que Meta cuenta su tope (25 en 24 h). 0 = no comprobar."));
        semillas.put(MAX_PENDIENTES, new Semilla(limites.getMaxPendingPosts(),
                "Publicaciones que un workspace puede tener esperando salir. 0 = sin tope."));
        semillas.put(MAX_POR_DIA, new Semilla(limites.getMaxPostsPerDay(),
                "Publicaciones que un workspace puede crear por dia. 0 = sin tope."));
        semillas.put(MAX_IA_POR_DIA, new Semilla(limites.getMaxAiCallsPerDay(),
                "Llamadas a la IA por workspace y dia. 0 = sin tope."));
        semillas.put(PAUSA_PROVEEDOR_SEGUNDOS, new Semilla(limites.getProviderPauseSeconds(),
                "Segundos que se pausa la cola tras un 429 del proveedor sin Retry-After."));
        return semillas;
    }

    private long semillaCuota(Platform platform) {
        Integer tope = cuotas.dailyFor(platform);
        return tope == null ? 0 : tope;
    }

    private void refrescarSiToca() {
        if (Instant.now().isBefore(cargado.plus(Duration.ofSeconds(TTL_SEGUNDOS)))) {
            return;
        }
        synchronized (this) {
            if (Instant.now().isBefore(cargado.plus(Duration.ofSeconds(TTL_SEGUNDOS)))) {
                return;
            }
            try {
                Map<String, Long> nuevo = new HashMap<>();
                for (AppLimit fila : repository.findAll()) {
                    nuevo.put(fila.getClave(), fila.getValor());
                }
                cache = Map.copyOf(nuevo);
            } catch (Exception ex) {
                // Se sigue con lo ultimo leido. Un tope viejo protege; ninguno, no.
                log.warn("No se pudo releer app_limits; se sigue con la copia anterior: {}", ex.getMessage());
            }
            cargado = Instant.now();
        }
    }

    private record Semilla(long valor, String descripcion) {
    }
}
