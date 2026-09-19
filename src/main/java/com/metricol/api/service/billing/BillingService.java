package com.metricol.api.service.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricol.api.config.StripeProperties;
import com.metricol.api.entity.CreditPack;
import com.metricol.api.entity.License;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.enums.OrgPermission;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.WorkspaceCreateRequest;
import com.metricol.api.models.response.BillingSummaryResponse;
import com.metricol.api.models.response.BillingSummaryResponse.Credits;
import com.metricol.api.models.response.BillingSummaryResponse.LicenseView;
import com.metricol.api.models.response.BillingSummaryResponse.Pack;
import com.metricol.api.models.response.BillingSummaryResponse.Price;
import com.metricol.api.models.response.BillingSummaryResponse.WorkspaceBilling;
import com.metricol.api.repository.CreditPackRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.OrganizationService;

/**
 * Lo que la persona hace con sus pagos, siempre desde la web: contratar la
 * licencia de un espacio, comprar uno nuevo, cancelar la renovación, comprar
 * créditos y abrir el portal de facturación.
 *
 * <p>Este servicio solo <b>prepara</b> la compra y manda a la persona a la página
 * de Stripe. Nada se activa aquí: las licencias y los créditos nacen cuando
 * Stripe avisa que se pagó ({@link StripeEventProcessor}). Así lo que se cobra
 * y lo que se otorga no pueden separarse.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final Duration VIGENCIA_DEL_PRECIO = Duration.ofMinutes(10);

    private final BillingConfig config;
    private final StripeProperties stripeProps;
    private final StripeClient stripe;
    private final LicenseService licencias;
    private final CreditService creditos;
    private final OrganizationService organizaciones;
    private final OrganizationRepository organizacionesRepo;
    private final WorkspaceRepository workspaces;
    private final CreditPackRepository paquetes;
    private final String urlDelSitio;

    /** Los precios leídos de Stripe, con la hora en que se leyeron. */
    private record PrecioLeido(Price precio, Instant leido) {
    }

    private final Map<String, PrecioLeido> precios = new ConcurrentHashMap<>();

    public BillingService(BillingConfig config, StripeProperties stripeProps, StripeClient stripe,
            LicenseService licencias, CreditService creditos, OrganizationService organizaciones,
            OrganizationRepository organizacionesRepo, WorkspaceRepository workspaces,
            CreditPackRepository paquetes, @Value("${app.web-url:https://picale.click}") String urlDelSitio) {
        this.config = config;
        this.stripeProps = stripeProps;
        this.stripe = stripe;
        this.licencias = licencias;
        this.creditos = creditos;
        this.organizaciones = organizaciones;
        this.organizacionesRepo = organizacionesRepo;
        this.workspaces = workspaces;
        this.paquetes = paquetes;
        this.urlDelSitio = urlDelSitio.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    @Transactional
    public BillingSummaryResponse resumen(User usuario) {
        if (!config.habilitado()) {
            return new BillingSummaryResponse(false, null, config.moneda(), null, null, List.of(), List.of());
        }
        Organization organizacion = organizaciones.deLaSesion(usuario);
        UUID espacioId = usuario.getWorkspace().getId();
        Workspace espacio = workspaces.findById(espacioId)
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));

        LocalDateTime ahora = LocalDateTime.now();
        LicenseView deEsteEspacio = licencias.deWorkspace(espacioId).map(l -> vista(l, espacio.getName(), ahora)).orElse(null);
        CreditService.Saldo saldo = creditos.saldo(espacioId);
        WorkspaceBilling actual = new WorkspaceBilling(espacioId, espacio.getName(), deEsteEspacio,
                new Credits(saldo.mensuales(), saldo.paquete(), saldo.total(), saldo.vencenLosMensuales()));

        List<LicenseView> todas = new ArrayList<>();
        if (organizaciones.rolDe(usuario.getId(), organizacion.getId()).administraLaOrganizacion()) {
            for (License l : licencias.deLaOrganizacion(organizacion.getId())) {
                String nombre = workspaces.findById(l.getWorkspaceId()).map(Workspace::getName).orElse("(sin nombre)");
                todas.add(vista(l, nombre, ahora));
            }
        }

        List<Pack> packs = config.paquetesEnVenta().stream()
                .map(p -> new Pack(p.getCode(), p.getName(), p.getCredits(), precio(p.getStripePriceId()))).toList();
        String modo = stripeProps.modo() == StripeProperties.Modo.APAGADO ? null : stripeProps.modo().name();
        return new BillingSummaryResponse(true, modo, config.moneda(), precio(config.precioDeLicencia()), actual, todas, packs);
    }

    // ------------------------------------------------------------------
    // Comprar
    // ------------------------------------------------------------------

    /** Un espacio nuevo: se cuentan los datos del negocio, se paga, y nace cuando Stripe avisa. */
    @Transactional
    public String comprarEspacioNuevo(User usuario, WorkspaceCreateRequest datos) {
        exigirCobros();
        if (datos == null || datos.getName() == null || datos.getName().isBlank()) {
            throw new IllegalArgumentException("El espacio de trabajo necesita un nombre.");
        }
        Organization organizacion = organizaciones.deLaSesion(usuario);
        // Quien puede dar de alta espacios (el que administra, o a quien se le dio ese permiso).
        organizaciones.exigirPermiso(usuario, organizacion.getId(), OrgPermission.CREATE_WORKSPACES);

        Map<String, String> compra = new LinkedHashMap<>();
        compra.put("kind", "license");
        compra.put("organization_id", organizacion.getId().toString());
        compra.put("user_id", usuario.getId().toString());
        compra.put("name", datos.getName().trim());
        poner(compra, "giro", datos.getGiro());
        poner(compra, "ciudad", datos.getCiudad());
        poner(compra, "descripcion", datos.getDescripcion());
        if (datos.getObjetivo() != null) {
            compra.put("objetivo", datos.getObjetivo().name());
        }
        return iniciarCompra(organizacion, usuario, config.precioDeLicencia(), true, compra);
    }

    /** Ponerle licencia a un espacio que ya existe: uno en prueba, o uno que se archivó por falta de pago. */
    @Transactional
    public String contratarLicencia(User usuario, UUID workspaceId) {
        exigirCobros();
        Organization organizacion = organizaciones.deLaSesion(usuario);
        organizaciones.exigirAdministrador(usuario, organizacion.getId());
        Workspace espacio = espacioDeLaOrganizacion(workspaceId, organizacion);

        Optional<License> actual = licencias.deWorkspace(workspaceId);
        if (actual.isPresent() && (actual.get().getStatus() == LicenseStatus.ACTIVE
                || actual.get().getStatus() == LicenseStatus.PAST_DUE)) {
            throw new IllegalStateException(actual.get().getStatus() == LicenseStatus.PAST_DUE
                    ? "Ese espacio tiene un cobro pendiente: actualiza tu tarjeta en el portal de facturación."
                    : "Ese espacio ya tiene su licencia activa.");
        }

        Map<String, String> compra = new LinkedHashMap<>();
        compra.put("kind", "license");
        compra.put("organization_id", organizacion.getId().toString());
        compra.put("user_id", usuario.getId().toString());
        compra.put("workspace_id", espacio.getId().toString());
        return iniciarCompra(organizacion, usuario, config.precioDeLicencia(), true, compra);
    }

    /** Un paquete suelto de créditos para un espacio. */
    @Transactional
    public String comprarPaquete(User usuario, String codigo, UUID workspaceId) {
        exigirCobros();
        Organization organizacion = organizaciones.deLaSesion(usuario);
        organizaciones.exigirAdministrador(usuario, organizacion.getId());
        Workspace espacio = espacioDeLaOrganizacion(workspaceId, organizacion);

        CreditPack paquete = paquetes.findByCode(codigo == null ? "" : codigo.trim())
                .filter(CreditPack::vendible)
                .orElseThrow(() -> new IllegalArgumentException("Ese paquete no está disponible."));

        Map<String, String> compra = new LinkedHashMap<>();
        compra.put("kind", "pack");
        compra.put("organization_id", organizacion.getId().toString());
        compra.put("user_id", usuario.getId().toString());
        compra.put("workspace_id", espacio.getId().toString());
        compra.put("pack_code", paquete.getCode());
        return iniciarCompra(organizacion, usuario, paquete.getStripePriceId(), false, compra);
    }

    // ------------------------------------------------------------------
    // Cancelar, reanudar, portal
    // ------------------------------------------------------------------

    /**
     * Que la licencia no se renueve (o que sí, si se arrepintió). Se sigue
     * usando hasta el fin de lo ya pagado; no hay reembolso.
     */
    @Transactional
    public LicenseView cancelarRenovacion(User usuario, UUID workspaceId, boolean cancelar) {
        exigirCobros();
        Organization organizacion = organizaciones.deLaSesion(usuario);
        organizaciones.exigirAdministrador(usuario, organizacion.getId());
        Workspace espacio = espacioDeLaOrganizacion(workspaceId, organizacion);

        License licencia = licencias.deWorkspace(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Ese espacio no tiene licencia."));
        if (licencia.getStripeSubscriptionId() == null) {
            throw new IllegalStateException("Es una prueba gratis: termina sola el día que se acaba. "
                    + "No hay nada que cancelar.");
        }
        if (licencia.getStatus() == LicenseStatus.ENDED) {
            throw new IllegalStateException("Esa licencia ya terminó.");
        }

        stripe.cancelarAlFinal(licencia.getStripeSubscriptionId(), cancelar);
        // Se refleja ya; el aviso de Stripe lo confirma.
        licencia.setCancelAtPeriodEnd(cancelar);
        licencia.setUpdatedAt(LocalDateTime.now());
        return vista(licencia, espacio.getName(), LocalDateTime.now());
    }

    /** La página de Stripe donde se cambia la tarjeta y se ven las facturas. */
    @Transactional
    public String portal(User usuario) {
        exigirCobros();
        Organization organizacion = organizaciones.deLaSesion(usuario);
        organizaciones.exigirAdministrador(usuario, organizacion.getId());
        if (organizacion.getStripeCustomerId() == null) {
            throw new IllegalStateException("Todavía no tienes pagos: el portal se abre después de tu primera compra.");
        }
        return stripe.crearPortal(organizacion.getStripeCustomerId(), urlDelSitio + "/panel/facturacion");
    }

    // ------------------------------------------------------------------
    // Piezas
    // ------------------------------------------------------------------

    private String iniciarCompra(Organization organizacion, User usuario, String precio, boolean suscripcion,
            Map<String, String> datos) {
        if (precio == null || precio.isBlank()) {
            throw new IllegalStateException("Falta configurar el precio en los ajustes de cobros.");
        }
        String cliente = clienteDe(organizacion, usuario);
        String exito = urlDelSitio + "/panel/facturacion?compra=ok&session_id={CHECKOUT_SESSION_ID}";
        String cancelado = urlDelSitio + "/panel/facturacion?compra=cancelada";
        return stripe.crearCompra(cliente, precio, suscripcion, datos, exito, cancelado, null);
    }

    /** El cliente de Stripe de la organización; se crea la primera vez que compra. */
    private String clienteDe(Organization organizacion, User usuario) {
        if (organizacion.getStripeCustomerId() != null && !organizacion.getStripeCustomerId().isBlank()) {
            return organizacion.getStripeCustomerId();
        }
        String creado = stripe.crearCliente(usuario.getEmail(), organizacion.getName(),
                organizacion.getId().toString());
        organizacion.setStripeCustomerId(creado);
        organizacionesRepo.save(organizacion);
        return creado;
    }

    private void exigirCobros() {
        if (!config.habilitado() || !stripe.disponible()) {
            throw new IllegalStateException("Los pagos todavía no están habilitados.");
        }
    }

    private Workspace espacioDeLaOrganizacion(UUID workspaceId, Organization organizacion) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));
        if (espacio.getOrganization() == null || !espacio.getOrganization().getId().equals(organizacion.getId())) {
            // Mismo mensaje que si no existiera: no se confirma que exista en otra organización.
            throw new ResourceNotFoundException("Espacio de trabajo no encontrado.");
        }
        return espacio;
    }

    private static LicenseView vista(License l, String nombre, LocalDateTime ahora) {
        return new LicenseView(l.getWorkspaceId(), nombre, l.getStatus().name(), l.usable(ahora), l.getTrialEndsAt(),
                l.getCurrentPeriodEnd(), l.getGraceUntil(), l.isCancelAtPeriodEnd(), l.getStripeSubscriptionId() != null);
    }

    private static void poner(Map<String, String> mapa, String clave, String valor) {
        if (valor != null && !valor.isBlank()) {
            mapa.put(clave, valor.trim());
        }
    }

    /** El precio tal como está en Stripe, con una caché corta: es un dato que casi no cambia. */
    private Price precio(String id) {
        if (id == null || id.isBlank() || !stripe.disponible()) {
            return null;
        }
        PrecioLeido guardado = precios.get(id);
        if (guardado != null && Duration.between(guardado.leido(), Instant.now()).compareTo(VIGENCIA_DEL_PRECIO) < 0) {
            return guardado.precio();
        }
        try {
            JsonNode precio = stripe.obtenerPrecio(id);
            Price leido = new Price(precio.path("unit_amount").asLong(0), precio.path("currency").asText(""),
                    precio.path("recurring").path("interval").asText(""));
            precios.put(id, new PrecioLeido(leido, Instant.now()));
            return leido;
        } catch (RuntimeException ex) {
            log.warn("No se pudo leer el precio {} de Stripe: {}", id, ex.getMessage());
            // Mejor un precio un poco viejo que ninguno.
            return guardado == null ? null : guardado.precio();
        }
    }
}
