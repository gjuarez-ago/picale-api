package com.metricol.api.service.billing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.License;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.repository.LicenseRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Las licencias de los workspaces: cuándo se crean, cuándo se vencen y qué
 * pasa con el workspace cuando eso ocurre.
 *
 * <p>Lo que hace con un workspace sin licencia vigente es <b>archivarlo</b>, no
 * borrarlo: deja de publicar y sale de la lista, pero todo sigue ahí. Al pagar
 * se restaura solo. Con los cobros apagados este servicio no hace nada.
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    private final LicenseRepository licencias;
    private final WorkspaceRepository workspaces;
    private final CreditService creditos;
    private final BillingConfig config;

    public LicenseService(LicenseRepository licencias, WorkspaceRepository workspaces, CreditService creditos,
            BillingConfig config) {
        this.licencias = licencias;
        this.workspaces = workspaces;
        this.creditos = creditos;
        this.config = config;
    }

    /**
     * Para un workspace recién registrado: empieza su prueba gratis, con sus
     * créditos. Con los cobros apagados no hace nada; los workspaces que
     * queden sin licencia se cubren cuando se enciendan (ver
     * {@link #procesarVencimientos}).
     */
    @Transactional
    public void iniciarPruebaAlRegistrarse(Workspace workspace) {
        if (!config.habilitado() || workspace == null || workspace.getOrganization() == null
                || workspace.getOrganization().isSinLimites()) {
            return;
        }
        crearPrueba(workspace, config.diasDePruebaAlRegistrarse());
    }

    /** Crea la licencia de prueba de un workspace, o devuelve la que ya tenía. */
    @Transactional
    public License crearPrueba(Workspace workspace, int dias) {
        Optional<License> existente = licencias.findByWorkspaceId(workspace.getId());
        if (existente.isPresent()) {
            return existente.get();
        }
        LocalDateTime fin = LocalDateTime.now().plusDays(dias);
        License licencia = licencias.save(License.builder()
                .organizationId(workspace.getOrganization().getId())
                .workspaceId(workspace.getId())
                .status(LicenseStatus.TRIALING)
                .trialEndsAt(fin)
                .build());
        // Los créditos del mes también valen en la prueba; vencen con ella.
        creditos.otorgarMensuales(workspace.getId(), config.creditosMensuales(), fin, "trial:" + licencia.getId());
        return licencia;
    }

    /**
     * El barrido: cubre con prueba a los workspaces que no tienen licencia,
     * archiva los que ya no pueden usarse y restaura los que volvieron a poder.
     *
     * @return cuántos workspaces cambiaron de estado
     */
    @Transactional
    public int procesarVencimientos() {
        if (!config.habilitado()) {
            return 0;
        }
        int cambios = 0;

        // Los de antes de encender los cobros: un mes de gracia (configurable), no un corte.
        List<Workspace> sinLicencia = licencias.workspacesSinLicencia();
        int conPrueba = 0;
        for (Workspace workspace : sinLicencia) {
            // La cuenta de la casa no tiene licencia: ni prueba ni vencimiento.
            if (workspace.getOrganization() != null && workspace.getOrganization().isSinLimites()) {
                continue;
            }
            crearPrueba(workspace, config.diasDePruebaDeLosExistentes());
            conPrueba++;
        }
        cambios += conPrueba;
        if (conPrueba > 0) {
            log.info("Cobros: {} workspace(s) existentes recibieron su prueba gratis", conPrueba);
        }

        LocalDateTime ahora = LocalDateTime.now();
        for (License licencia : licencias.findAll()) {
            Optional<Workspace> encontrado = workspaces.findById(licencia.getWorkspaceId());
            if (encontrado.isEmpty()) {
                continue;
            }
            Workspace workspace = encontrado.get();
            if (workspace.getOrganization() != null && workspace.getOrganization().isSinLimites()) {
                continue; // Sin vigencia: la prueba que le tocó al registrarse no la archiva.
            }

            if (licencia.usable(ahora)) {
                // Volvió a poder usarse: si fue este proceso quien lo archivó, se restaura.
                if (licencia.isArchivedBySweep() && workspace.archivado()) {
                    workspace.setArchivedAt(null);
                    workspaces.save(workspace);
                    licencia.setArchivedBySweep(false);
                    licencia.setUpdatedAt(ahora);
                    licencias.save(licencia);
                    log.info("Cobros: workspace {} restaurado", workspace.getId());
                    cambios++;
                }
            } else if (licencia.getStatus() != LicenseStatus.ENDED) {
                licencia.setStatus(LicenseStatus.ENDED);
                if (!workspace.archivado()) {
                    workspace.setArchivedAt(ahora);
                    workspaces.save(workspace);
                    licencia.setArchivedBySweep(true);
                }
                licencia.setUpdatedAt(ahora);
                licencias.save(licencia);
                log.info("Cobros: licencia del workspace {} terminó; workspace archivado (no se borró nada)",
                        workspace.getId());
                cambios++;
            }
        }
        return cambios;
    }

    @Transactional(readOnly = true)
    public Optional<License> deWorkspace(UUID workspaceId) {
        return licencias.findByWorkspaceId(workspaceId);
    }

    @Transactional(readOnly = true)
    public List<License> deLaOrganizacion(UUID organizationId) {
        return licencias.findByOrganizationId(organizationId);
    }
}
