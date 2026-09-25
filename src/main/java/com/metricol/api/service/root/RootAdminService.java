package com.metricol.api.service.root;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.ImageCredits;
import com.metricol.api.entity.License;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.root.AjusteDeCreditosRequest;
import com.metricol.api.models.request.root.LicenciaRequest;
import com.metricol.api.models.response.root.OrganizacionDetalleResponse;
import com.metricol.api.models.response.root.OrganizacionDetalleResponse.Creditos;
import com.metricol.api.models.response.root.OrganizacionDetalleResponse.Espacio;
import com.metricol.api.models.response.root.OrganizacionDetalleResponse.Licencia;
import com.metricol.api.models.response.root.OrganizacionDetalleResponse.Miembro;
import com.metricol.api.models.response.root.OrganizacionDetalleResponse.MiembroDeEspacio;
import com.metricol.api.models.response.root.OrganizacionResumenResponse;
import com.metricol.api.repository.ImageCreditsRepository;
import com.metricol.api.repository.LicenseRepository;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.billing.BillingConfig;
import com.metricol.api.service.billing.CreditService;

/**
 * Lo que la administración de la plataforma ve y toca de todas las
 * organizaciones.
 *
 * <p>Consulta por encima de cualquier workspace: las entidades que usa
 * (organizaciones, espacios, miembros, licencias, créditos) no llevan
 * {@code @TenantId}, así que aquí no hay filtro de inquilino que esquivar.
 * Quien llama ya pasó por {@link RootAccessService}; este servicio no vuelve a
 * preguntar quién es.
 *
 * <p>Las listas se arman en memoria a partir de cargas completas: la
 * plataforma tiene decenas de organizaciones, no miles, y una consulta por
 * tabla es más barata que una por fila.
 */
@Service
public class RootAdminService {

    private static final Logger log = LoggerFactory.getLogger(RootAdminService.class);

    /** Situaciones de una organización, en el orden en que preocupan. */
    static final String SIN_LIMITES = "SIN_LIMITES";
    static final String VENCIDA = "VENCIDA";
    static final String EN_PRUEBA = "EN_PRUEBA";
    static final String AL_CORRIENTE = "AL_CORRIENTE";
    static final String SIN_LICENCIA = "SIN_LICENCIA";

    private final OrganizationRepository organizaciones;
    private final OrganizationMemberRepository miembros;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository accesos;
    private final LicenseRepository licencias;
    private final ImageCreditsRepository saldos;
    private final CreditService creditos;
    private final BillingConfig config;

    public RootAdminService(OrganizationRepository organizaciones, OrganizationMemberRepository miembros,
            WorkspaceRepository workspaces, WorkspaceMemberRepository accesos, LicenseRepository licencias,
            ImageCreditsRepository saldos, CreditService creditos, BillingConfig config) {
        this.organizaciones = organizaciones;
        this.miembros = miembros;
        this.workspaces = workspaces;
        this.accesos = accesos;
        this.licencias = licencias;
        this.saldos = saldos;
        this.creditos = creditos;
        this.config = config;
    }

    // ------------------------------------------------------------------ leer

    /**
     * Todas las organizaciones, con sus números. {@code q} filtra por nombre
     * de la organización, de un espacio, o por nombre o correo de alguien de
     * ella; vacío = todas.
     */
    @Transactional(readOnly = true)
    public List<OrganizacionResumenResponse> listar(String q) {
        LocalDateTime ahora = LocalDateTime.now();
        String filtro = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);

        Map<UUID, List<Workspace>> espaciosPorOrg = workspaces.findAll().stream()
                .filter(w -> w.getOrganization() != null)
                .collect(Collectors.groupingBy(w -> w.getOrganization().getId()));
        Map<UUID, List<OrganizationMember>> miembrosPorOrg = miembros.findTodosConUsuario().stream()
                .collect(Collectors.groupingBy(m -> m.getOrganization().getId()));
        Map<UUID, License> licenciaPorEspacio = licencias.findAll().stream()
                .collect(Collectors.toMap(License::getWorkspaceId, Function.identity(), (a, b) -> a));

        List<OrganizacionResumenResponse> lista = new ArrayList<>();
        for (Organization org : organizaciones.findAll()) {
            List<Workspace> espacios = espaciosPorOrg.getOrDefault(org.getId(), List.of());
            List<OrganizationMember> gente = miembrosPorOrg.getOrDefault(org.getId(), List.of());
            if (!filtro.isEmpty() && !coincide(filtro, org, espacios, gente)) {
                continue;
            }

            int vigentes = 0;
            int vencidas = 0;
            int enPrueba = 0;
            LocalDateTime proxima = null;
            for (Workspace w : espacios) {
                License l = licenciaPorEspacio.get(w.getId());
                if (l == null) {
                    continue;
                }
                if (w.archivado() && !l.isArchivedBySweep()) {
                    continue; // Lo archivó la propia persona: no cuenta como vencido.
                }
                if (l.usable(ahora)) {
                    vigentes++;
                    if (l.getStatus() == LicenseStatus.TRIALING) {
                        enPrueba++;
                    }
                    LocalDateTime hasta = vigenteHasta(l);
                    if (hasta != null && (proxima == null || hasta.isBefore(proxima))) {
                        proxima = hasta;
                    }
                } else {
                    vencidas++;
                }
            }

            Optional<OrganizationMember> dueno = gente.stream()
                    .filter(m -> m.getRole() == OrgRole.OWNER)
                    .min(Comparator.comparing(OrganizationMember::getCreatedAt));
            if (dueno.isEmpty()) {
                dueno = gente.stream().min(Comparator.comparing(OrganizationMember::getCreatedAt));
            }

            String situacion;
            if (org.isSinLimites()) {
                situacion = SIN_LIMITES;
            } else if (vencidas > 0) {
                situacion = VENCIDA;
            } else if (vigentes == 0) {
                situacion = SIN_LICENCIA;
            } else if (enPrueba == vigentes) {
                situacion = EN_PRUEBA;
            } else {
                situacion = AL_CORRIENTE;
            }

            lista.add(new OrganizacionResumenResponse(
                    org.getId(), org.getName(), org.isSinLimites(), org.getMaxWorkspaces(),
                    org.getStripeCustomerId() != null, org.getCreatedAt(),
                    dueno.map(m -> m.getUser().getName()).orElse(null),
                    dueno.map(m -> m.getUser().getEmail()).orElse(null),
                    espacios.size(), (int) espacios.stream().filter(w -> !w.archivado()).count(), gente.size(),
                    vigentes, vencidas, enPrueba, situacion, org.isSinLimites() ? null : proxima));
        }
        lista.sort(Comparator.comparing(OrganizacionResumenResponse::createdAt).reversed());
        return lista;
    }

    private static boolean coincide(String filtro, Organization org, List<Workspace> espacios,
            List<OrganizationMember> gente) {
        if (contiene(org.getName(), filtro)) {
            return true;
        }
        if (espacios.stream().anyMatch(w -> contiene(w.getName(), filtro))) {
            return true;
        }
        return gente.stream().anyMatch(m -> contiene(m.getUser().getName(), filtro)
                || contiene(m.getUser().getEmail(), filtro));
    }

    private static boolean contiene(String texto, String filtro) {
        return texto != null && texto.toLowerCase(Locale.ROOT).contains(filtro);
    }

    @Transactional(readOnly = true)
    public OrganizacionDetalleResponse detalle(UUID organizationId) {
        Organization org = exigirOrganizacion(organizationId);
        LocalDateTime ahora = LocalDateTime.now();

        List<Miembro> gente = miembros.findDeLaOrganizacion(org.getId()).stream()
                .map(m -> new Miembro(m.getUser().getId(), m.getUser().getName(), m.getUser().getEmail(),
                        m.getRole() == null ? null : m.getRole().name(),
                        m.getPermissions().stream().map(Enum::name).sorted().toList(),
                        m.getUser().isPlatformAdmin(),
                        m.getUser().getWorkspace() == null ? null : m.getUser().getWorkspace().getId(),
                        m.getCreatedAt()))
                .toList();

        Map<UUID, License> licenciaPorEspacio = licencias.findByOrganizationId(org.getId()).stream()
                .collect(Collectors.toMap(License::getWorkspaceId, Function.identity(), (a, b) -> a));

        List<Espacio> espacios = new ArrayList<>();
        for (Workspace w : workspaces.findDeLaOrganizacion(org.getId())) {
            License l = licenciaPorEspacio.get(w.getId());
            Creditos saldo = saldos.findById(w.getId())
                    .map(c -> creditos(c, ahora))
                    .orElse(new Creditos(0, 0, 0, null));
            List<MiembroDeEspacio> quienes = accesos.findDelWorkspace(w.getId()).stream()
                    .map(a -> new MiembroDeEspacio(a.getUser().getId(), a.getUser().getName(),
                            a.getUser().getEmail(), a.getRole().name(),
                            a.permisosEfectivos().stream().map(Enum::name).sorted().toList()))
                    .toList();
            espacios.add(new Espacio(w.getId(), w.getName(), w.getGiro(), w.getCiudad(), w.getLogoUrl(),
                    w.getCreatedAt(), w.getArchivedAt(), w.perfilCompleto(),
                    l == null ? null : licencia(l, ahora), saldo, quienes));
        }

        return new OrganizacionDetalleResponse(org.getId(), org.getName(), org.isSinLimites(),
                org.getMaxWorkspaces(), org.getStripeCustomerId(), org.getCreatedAt(), gente, espacios);
    }

    // ---------------------------------------------------------------- tocar

    /**
     * Exenta (o deja de exentar) de pago a una organización, como la cuenta de
     * la casa: sin vigencia, sin cupos y sin avisos de cobro.
     *
     * <p>Al encenderla se restauran ya los espacios que el barrido de cobros
     * había archivado por licencia vencida: el barrido salta a las
     * organizaciones sin límites, así que nadie más los restauraría y la
     * cuenta exenta se quedaría fuera. Los que archivó la propia persona no se
     * tocan.
     *
     * <p>Al apagarla no se toca ninguna licencia: el barrido
     * ({@code LicenseService.procesarVencimientos}) vuelve a aplicarle las
     * reglas de todos en su siguiente vuelta, incluida la prueba gratis a los
     * espacios que nunca tuvieron licencia.
     */
    @Transactional
    public OrganizacionDetalleResponse cambiarSinLimites(UUID organizationId, boolean valor, User quien) {
        Organization org = exigirOrganizacion(organizationId);
        if (org.isSinLimites() != valor) {
            org.setSinLimites(valor);
            organizaciones.save(org);
            if (valor) {
                restaurarLosQueArchivoElBarrido(org);
            }
            log.info("Root {}: organizacion {} {} limites", quien.getEmail(), org.getId(), valor ? "SIN" : "CON");
        }
        return detalle(organizationId);
    }

    private void restaurarLosQueArchivoElBarrido(Organization org) {
        LocalDateTime ahora = LocalDateTime.now();
        for (License l : licencias.findByOrganizationId(org.getId())) {
            if (!l.isArchivedBySweep()) {
                continue;
            }
            workspaces.findById(l.getWorkspaceId()).ifPresent(w -> {
                if (w.archivado()) {
                    w.setArchivedAt(null);
                    workspaces.save(w);
                }
                l.setArchivedBySweep(false);
                l.setUpdatedAt(ahora);
                licencias.save(l);
                log.info("Root: espacio {} restaurado al exentar a su organizacion", w.getId());
            });
        }
    }

    @Transactional
    public OrganizacionDetalleResponse cambiarCupo(UUID organizationId, int maxWorkspaces, User quien) {
        Organization org = exigirOrganizacion(organizationId);
        org.setMaxWorkspaces(maxWorkspaces);
        organizaciones.save(org);
        log.info("Root {}: organizacion {} cupo de espacios = {}", quien.getEmail(), org.getId(), maxWorkspaces);
        return detalle(organizationId);
    }

    /**
     * Deja la licencia del espacio en el estado y con la vigencia que se pida.
     *
     * <p>Si no tenía licencia, se crea (y recibe los créditos mensuales de una
     * licencia nueva, como una prueba). El efecto sobre el espacio es
     * inmediato en vez de esperar al barrido: si queda vigente y el barrido lo
     * había archivado, se restaura; si queda terminada, se archiva ya —sin
     * borrar nada, igual que hace el barrido—.
     *
     * <p>Si la licencia la lleva Stripe, lo que se cambie aquí lo puede pisar
     * el siguiente aviso de Stripe (una factura pagada vuelve a poner el fin
     * del periodo). La respuesta lo dice con {@code hasSubscription} y la
     * pantalla lo advierte.
     */
    @Transactional
    public OrganizacionDetalleResponse fijarLicencia(UUID workspaceId, LicenciaRequest cambio, User quien) {
        Workspace w = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));
        if (w.getOrganization() == null) {
            throw new IllegalStateException("Ese espacio no tiene organización; no se le puede poner licencia.");
        }

        LicenseStatus estado;
        try {
            estado = LicenseStatus.valueOf(cambio.getStatus().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Estado de licencia desconocido: " + cambio.getStatus());
        }
        LocalDateTime hasta = cambio.getVigenteHasta();
        if (estado != LicenseStatus.ENDED && hasta == null) {
            throw new IllegalArgumentException("Falta hasta cuándo queda vigente.");
        }

        LocalDateTime ahora = LocalDateTime.now();
        boolean nueva = false;
        License l = licencias.findByWorkspaceId(workspaceId).orElse(null);
        if (l == null) {
            nueva = true;
            l = License.builder()
                    .organizationId(w.getOrganization().getId())
                    .workspaceId(workspaceId)
                    .status(estado)
                    .build();
        }

        l.setStatus(estado);
        switch (estado) {
            case TRIALING -> l.setTrialEndsAt(hasta);
            case ACTIVE -> l.setCurrentPeriodEnd(hasta);
            case PAST_DUE -> l.setGraceUntil(hasta);
            case ENDED -> {
                // Nada que fechar: usable() ya contesta que no.
            }
        }
        if (cambio.getCancelAtPeriodEnd() != null) {
            l.setCancelAtPeriodEnd(cambio.getCancelAtPeriodEnd());
        }
        l.setUpdatedAt(ahora);

        if (l.usable(ahora)) {
            if (w.archivado() && l.isArchivedBySweep()) {
                w.setArchivedAt(null);
                workspaces.save(w);
                l.setArchivedBySweep(false);
            }
        } else if (!w.archivado() && !w.getOrganization().isSinLimites()) {
            w.setArchivedAt(ahora);
            workspaces.save(w);
            l.setArchivedBySweep(true);
        }
        l = licencias.save(l);

        if (nueva && l.usable(ahora)) {
            creditos.otorgarMensuales(workspaceId, config.creditosMensuales(), hasta, "root:" + l.getId());
        }

        log.info("Root {}: licencia del espacio {} -> {} hasta {}{}", quien.getEmail(), workspaceId, estado, hasta,
                l.getStripeSubscriptionId() != null ? " (la lleva Stripe: puede pisarlo)" : "");
        return detalle(w.getOrganization().getId());
    }

    /** Suma o quita créditos de imagen al espacio (bolsa de paquete, que no vence). */
    @Transactional
    public OrganizacionDetalleResponse ajustarCreditos(UUID workspaceId, AjusteDeCreditosRequest ajuste, User quien) {
        Workspace w = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));
        if (w.getOrganization() == null) {
            throw new IllegalStateException("Ese espacio no tiene organización.");
        }
        if (ajuste.getDelta() == 0) {
            throw new IllegalArgumentException("El ajuste no puede ser cero.");
        }
        String motivo = ajuste.getMotivo() == null ? "" : ajuste.getMotivo().strip();
        // Con la referencia del cliente, un reintento de la misma petición no suma dos veces
        // (CreditService.ajustar es idempotente por referencia). Sin ella, cada llamada es nueva.
        String propia = ajuste.getReferencia() == null ? "" : ajuste.getReferencia().strip();
        String referencia = "root:" + quien.getId() + ":" + (propia.isEmpty() ? UUID.randomUUID().toString() : propia);
        // El motivo va al movimiento, no solo al log: es lo que el historial enseña después.
        int queda = creditos.ajustar(workspaceId, ajuste.getDelta(), referencia, motivo.isEmpty() ? null : motivo);
        log.info("Root {}: creditos del espacio {} {}{} ({}); quedan {}", quien.getEmail(), workspaceId,
                ajuste.getDelta() > 0 ? "+" : "", ajuste.getDelta(), motivo.isEmpty() ? "sin motivo" : motivo, queda);
        return detalle(w.getOrganization().getId());
    }

    // -------------------------------------------------------------- privados

    private Organization exigirOrganizacion(UUID id) {
        return organizaciones.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organización no encontrada."));
    }

    static LocalDateTime vigenteHasta(License l) {
        return switch (l.getStatus()) {
            case TRIALING -> l.getTrialEndsAt();
            case ACTIVE -> l.getCurrentPeriodEnd();
            case PAST_DUE -> l.getGraceUntil();
            case ENDED -> null;
        };
    }

    private static Licencia licencia(License l, LocalDateTime ahora) {
        return new Licencia(l.getId(), l.getStatus().name(), l.usable(ahora), l.getTrialEndsAt(),
                l.getCurrentPeriodEnd(), l.getGraceUntil(), l.isCancelAtPeriodEnd(),
                l.getStripeSubscriptionId() != null, l.isPricedAsExtra(), l.isArchivedBySweep(),
                vigenteHasta(l), l.getUpdatedAt());
    }

    private static Creditos creditos(ImageCredits c, LocalDateTime ahora) {
        int mensuales = c.mensualesVigentes(ahora);
        int paquete = Math.max(0, c.getPackBalance());
        return new Creditos(mensuales, paquete, mensuales + paquete, c.getMonthlyExpiresAt());
    }
}
