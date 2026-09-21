package com.metricol.api.service.publishing;

import com.metricol.api.service.CuentaSinLimites;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.DailyPublishUsage;
import com.metricol.api.enums.Platform;
import com.metricol.api.repository.DailyPublishUsageRepository;
import com.metricol.api.repository.GlobalPublishUsageRepository;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.service.limits.LimitesConfigurables;

/**
 * Lleva la cuenta de cuántas publicaciones al día ha hecho cada workspace en
 * cada red, y decide si cabe una más.
 *
 * <p>La cuota se reserva ANTES de llamar al proveedor y se devuelve si la
 * llamada falla. Al revés —apuntar solo lo que salió bien— parece más simple
 * pero deja la puerta abierta a pasarse: ocho workers preguntando a la vez
 * leen todos el mismo total viejo, todos concluyen que cabe, y la red recibe
 * más de lo que acepta. Reservar primero cuesta una devolución ocasional y a
 * cambio el límite no se cruza nunca.
 *
 * <p>Tres candados, en este orden, y basta con que uno diga no:
 * <ol>
 *   <li><b>La ventana móvil.</b> Meta cuenta sus 25 en 24 horas <i>corridas</i>,
 *       no por día natural. Nuestro contador se reinicia a medianoche, así
 *       que 25 a las 23:30 y 25 a las 00:10 eran 50 para Instagram en cuarenta
 *       minutos, y la página bloqueada. Antes de tocar el contador se mira
 *       cuántas salieron de verdad en las últimas N horas.</li>
 *   <li><b>El contador del día por red y workspace</b>, atómico en la base.</li>
 *   <li><b>El contador global del día</b>, para toda la plataforma. Apagado
 *       (0) mientras el plan del proveedor no imponga uno.</li>
 * </ol>
 *
 * <p>Los topes salen de {@link LimitesConfigurables}: se editan en la tabla
 * {@code app_limits} y se hacen efectivos en medio minuto.
 */
@Service
public class PublishQuotaService {

    private static final Logger log = LoggerFactory.getLogger(PublishQuotaService.class);

    private final DailyPublishUsageRepository repository;
    private final DailyUsageRows filas;
    private final GlobalPublishUsageRepository global;
    private final GlobalUsageRows filasGlobales;
    private final PostTargetRepository destinos;
    private final LimitesConfigurables limites;
    private final CuentaSinLimites sinLimites;

    public PublishQuotaService(
            DailyPublishUsageRepository repository,
            DailyUsageRows filas,
            GlobalPublishUsageRepository global,
            GlobalUsageRows filasGlobales,
            PostTargetRepository destinos,
            LimitesConfigurables limites,
            CuentaSinLimites sinLimites) {
        this.sinLimites = sinLimites;
        this.repository = repository;
        this.filas = filas;
        this.global = global;
        this.filasGlobales = filasGlobales;
        this.destinos = destinos;
        this.limites = limites;
    }

    /** Cómo acabó una reserva. Solo {@link #OK} deja publicar. */
    public enum Reserva {
        OK,
        /** La red de ese workspace ya publicó todo lo que acepta. */
        RED_AGOTADA,
        /** La plataforma entera llegó a su tope del día. */
        GLOBAL_AGOTADO;

        public boolean concedida() {
            return this == OK;
        }
    }

    /**
     * Aparta un hueco del día para esa red.
     *
     * <p>Una red sin tope configurado siempre cabe: igual se apunta, para que
     * la pantalla pueda decir cuántas van hoy.
     */
    @Transactional
    public Reserva reservar(UUID workspaceId, Platform platform) {
        if (sinLimites.deWorkspace(workspaceId)) {
            // La cuenta de la casa no gasta cupo, ni el suyo ni el global de los demás.
            return Reserva.OK;
        }
        LocalDate hoy = LocalDate.now();
        Integer tope = limites.cuotaDiaria(platform);

        if (tope != null && excedeVentanaMovil(workspaceId, platform, tope)) {
            log.info("Cuota de {} agotada en ventana movil para el workspace {} ({} en {} h)",
                    platform, workspaceId, tope, limites.ventanaRodanteHoras());
            return Reserva.RED_AGOTADA;
        }

        if (!consumirDelDia(workspaceId, platform, hoy, tope)) {
            log.info("Cuota diaria agotada: workspace {} en {} ({} al día)", workspaceId, platform, tope);
            return Reserva.RED_AGOTADA;
        }

        if (!consumirGlobal(hoy)) {
            // El hueco de la red se devuelve: no se va a usar, y dejarlo
            // gastado le cobraria al workspace una publicacion que no salio.
            repository.devolver(workspaceId, platform, hoy);
            log.warn("Tope global diario alcanzado ({}); la publicacion del workspace {} en {} se aplaza",
                    limites.cuotaGlobalDiaria(), workspaceId, platform);
            return Reserva.GLOBAL_AGOTADO;
        }

        return Reserva.OK;
    }

    /**
     * Suelta un hueco reservado que no se llegó a usar porque la llamada al
     * proveedor falló. Se llama en su propia transacción desde el runner: la
     * del publicado ya se deshizo cuando esto hace falta.
     */
    @Transactional
    public void devolver(UUID workspaceId, Platform platform) {
        if (sinLimites.deWorkspace(workspaceId)) {
            return; // No reservó nada: devolver restaría un hueco que es de otros.
        }
        LocalDate hoy = LocalDate.now();
        repository.devolver(workspaceId, platform, hoy);
        if (limites.cuotaGlobalDiaria() > 0) {
            global.devolver(hoy);
        }
    }

    /** Lo consumido hoy por red, para enseñarlo antes de publicar. */
    @Transactional(readOnly = true)
    public List<Estado> estadoDe(UUID workspaceId) {
        Map<Platform, Integer> usados = new EnumMap<>(Platform.class);
        for (DailyPublishUsage fila : repository.findByWorkspaceIdAndDay(workspaceId, LocalDate.now())) {
            usados.put(fila.getPlatform(), fila.getUsed());
        }

        boolean sinTope = sinLimites.deWorkspace(workspaceId);
        List<Estado> estados = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            int usado = usados.getOrDefault(platform, 0);
            estados.add(new Estado(platform, usado, sinTope ? null : limites.cuotaDiaria(platform)));
        }
        return estados;
    }

    /**
     * Tira los contadores de días pasados. Corre de madrugada porque a esa
     * hora no hay nada que estorbar, y no borra el día en curso: es el único
     * que se consulta para decidir.
     */
    @Scheduled(cron = "${app.quota.cleanup-cron:0 20 3 * * *}")
    @Transactional
    public void limpiarHistorico() {
        int borradas = repository.borrarAnterioresA(LocalDate.now());
        borradas += global.borrarAnterioresA(LocalDate.now());
        if (borradas > 0) {
            log.info("Contadores de cuota de días anteriores borrados: {}", borradas);
        }
    }

    // ------------------------------------------------------------------

    private boolean excedeVentanaMovil(UUID workspaceId, Platform platform, int tope) {
        int horas = limites.ventanaRodanteHoras();
        if (horas <= 0) {
            return false;
        }
        long recientes = destinos.countPublicadasDesde(
                workspaceId.toString(), platform, LocalDateTime.now().minusHours(horas));
        return recientes >= tope;
    }

    private boolean consumirDelDia(UUID workspaceId, Platform platform, LocalDate hoy, Integer tope) {
        if (consumir(workspaceId, platform, hoy, tope) > 0) {
            return true;
        }
        // Un 0 puede querer decir dos cosas muy distintas: que la cuota se
        // agotó, o que hoy todavía no hay fila para esta red. Se distinguen
        // creando la fila y volviendo a intentar una sola vez.
        filas.asegurar(workspaceId, platform, hoy);
        return consumir(workspaceId, platform, hoy, tope) > 0;
    }

    private int consumir(UUID workspaceId, Platform platform, LocalDate hoy, Integer tope) {
        return tope == null
                ? repository.consumir(workspaceId, platform, hoy)
                : repository.consumirSiCabe(workspaceId, platform, hoy, tope);
    }

    private boolean consumirGlobal(LocalDate hoy) {
        long tope = limites.cuotaGlobalDiaria();
        if (tope <= 0) {
            return true;
        }
        if (global.consumirSiCabe(hoy, tope) > 0) {
            return true;
        }
        filasGlobales.asegurar(hoy);
        return global.consumirSiCabe(hoy, tope) > 0;
    }

    /**
     * Cómo va una red hoy. {@code limite} en {@code null} es una red sin tope
     * conocido: se sigue contando lo publicado, pero no se bloquea nada.
     */
    public record Estado(Platform platform, int used, Integer limit) {

        public Integer remaining() {
            return limit == null ? null : Math.max(0, limit - used);
        }

        public boolean exhausted() {
            return limit != null && used >= limit;
        }
    }
}
