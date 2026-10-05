package com.metricol.api.service.social;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.repository.SocialConnectionCheckRepository;
import com.metricol.api.service.avisos.AvisosPush;

/**
 * Mientras una red siga por reconectar, un recordatorio al día al teléfono:
 * "Instagram sigue desconectada: 4 publicaciones esperan". El primer aviso
 * sale al detectarlo (ver {@link ConexionesCaducadas#marcarFallo}); este es el
 * que insiste, sin molestar: como mucho uno al día por red y de
 * {@value #DESDE} a {@value #HASTA} h.
 */
@Component
public class RecordatorioDeReconexion {

    private static final Logger log = LoggerFactory.getLogger(RecordatorioDeReconexion.class);

    static final int DESDE = 9;
    static final int HASTA = 20;

    private final SocialConnectionCheckRepository checks;
    private final ConexionesCaducadas conexiones;
    private final AvisosPush avisos;

    public RecordatorioDeReconexion(SocialConnectionCheckRepository checks, ConexionesCaducadas conexiones,
            AvisosPush avisos) {
        this.checks = checks;
        this.conexiones = conexiones;
        this.avisos = avisos;
    }

    @Scheduled(initialDelayString = "${app.reconexion.recordatorio-inicial-ms:600000}",
            fixedDelayString = "${app.reconexion.recordatorio-cada-ms:1800000}")
    public void vuelta() {
        if (!avisos.activo()) {
            return;
        }
        LocalDateTime ahora = LocalDateTime.now();
        if (ahora.getHour() < DESDE || ahora.getHour() >= HASTA) {
            return;
        }
        for (String tenant : checks.espaciosPorReconectar()) {
            try {
                TenantIdentifierResolver.comoTenant(tenant, () -> recordar(UUID.fromString(tenant), ahora));
            } catch (RuntimeException ex) {
                log.warn("No se pudo recordar la reconexión en {}: {}", tenant, ex.toString());
            }
        }
    }

    /** Un recordatorio por red, si pasó un día desde que se detectó y desde el último. */
    void recordar(UUID workspaceId, LocalDateTime ahora) {
        for (ConexionesCaducadas.PorReconectar r : conexiones.porReconectar(workspaceId)) {
            checks.findByPlatform(r.red()).ifPresent(check -> {
                LocalDateTime desde = r.desde();
                boolean yaPasoUnDia = desde == null || !desde.plusHours(20).isAfter(ahora);
                boolean noHoy = check.getUltimoRecordatorio() == null
                        || !check.getUltimoRecordatorio().plusHours(22).isAfter(ahora);
                if (!yaPasoUnDia || !noHoy) {
                    return;
                }
                String cuerpo = r.pendientes() == 0
                        ? r.nombre() + " sigue sin aceptar publicaciones de Pícale. Reconéctala en Redes."
                        : r.nombre() + " sigue desconectada: " + (r.pendientes() == 1 ? "1 publicación espera"
                                : r.pendientes() + " publicaciones esperan") + ". Reconéctala en Redes y sale"
                                + (r.pendientes() == 1 ? "" : "n") + " sola" + (r.pendientes() == 1 ? "." : "s.");
                if (avisos.avisarAlEquipo(workspaceId, r.nombre() + " sigue por reconectar", cuerpo,
                        Map.of("tipo", "reconectar", "red", r.red()))) {
                    check.setUltimoRecordatorio(ahora);
                    checks.save(check);
                }
            });
        }
    }
}
