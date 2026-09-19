package com.metricol.api.service.billing;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.metricol.api.entity.License;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.StripeEvent;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.enums.ObjetivoRedes;
import com.metricol.api.models.request.WorkspaceCreateRequest;
import com.metricol.api.repository.CreditPackRepository;
import com.metricol.api.repository.LicenseRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.StripeEventRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.WorkspaceMembershipService;

/**
 * Convierte los avisos de Stripe en licencias y créditos.
 *
 * <p>Aquí es donde el dinero se vuelve derechos, así que las reglas son
 * estrictas:
 * <ul>
 *   <li><b>Nada se hace dos veces.</b> Cada {@code evt_...} se recuerda; y lo
 *       que se otorga (créditos, paquetes) lleva su propia referencia única.</li>
 *   <li><b>Solo lo pagado cuenta.</b> Una compra que Stripe no marca como pagada
 *       no crea ni activa nada.</li>
 *   <li><b>Si falta contexto, se pide reintento.</b> Un aviso de factura puede
 *       llegar antes que el de la compra; en vez de perderlo se contesta que se
 *       vuelva a mandar (Stripe reintenta durante días).</li>
 * </ul>
 *
 * <p>Tolerante con la forma de los avisos: Stripe movió campos entre versiones
 * de su API (la fecha de fin del periodo pasó de la suscripción a sus
 * artículos), y aquí se leen las dos.
 */
@Service
public class StripeEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(StripeEventProcessor.class);

    /** Qué hacer con el aviso tras procesarlo. */
    public enum Resultado {
        /** Se aplicó (o ya estaba aplicado). Contestar 200. */
        PROCESADO,
        /** No nos toca. Contestar 200 para que Stripe no lo reintente. */
        IGNORADO,
        /** Falta algo que llegará en otro aviso. Contestar error para que Stripe lo vuelva a mandar. */
        REINTENTAR
    }

    private final StripeEventRepository eventos;
    private final LicenseRepository licencias;
    private final WorkspaceRepository workspaces;
    private final OrganizationRepository organizaciones;
    private final CreditPackRepository paquetes;
    private final CreditService creditos;
    private final BillingConfig config;
    private final WorkspaceMembershipService membresias;
    private final StripeClient stripe;

    public StripeEventProcessor(StripeEventRepository eventos, LicenseRepository licencias,
            WorkspaceRepository workspaces, OrganizationRepository organizaciones, CreditPackRepository paquetes,
            CreditService creditos, BillingConfig config, WorkspaceMembershipService membresias,
            StripeClient stripe) {
        this.eventos = eventos;
        this.licencias = licencias;
        this.workspaces = workspaces;
        this.organizaciones = organizaciones;
        this.paquetes = paquetes;
        this.creditos = creditos;
        this.config = config;
        this.membresias = membresias;
        this.stripe = stripe;
    }

    @Transactional
    public Resultado procesar(JsonNode evento) {
        String id = evento.path("id").asText("");
        String tipo = evento.path("type").asText("");
        if (id.isBlank() || tipo.isBlank()) {
            return Resultado.IGNORADO;
        }
        if (eventos.existsById(id)) {
            return Resultado.PROCESADO;
        }

        JsonNode objeto = evento.path("data").path("object");
        Resultado resultado = switch (tipo) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> compraCompletada(objeto);
            case "invoice.paid" -> facturaPagada(objeto);
            case "invoice.payment_failed" -> cobroFallido(objeto);
            case "customer.subscription.updated" -> suscripcionActualizada(objeto);
            case "customer.subscription.deleted" -> suscripcionTerminada(objeto);
            default -> Resultado.IGNORADO;
        };

        if (resultado != Resultado.REINTENTAR) {
            eventos.save(StripeEvent.builder().id(id).type(tipo).build());
        }
        log.info("Stripe {} {} -> {}", tipo, id, resultado);
        return resultado;
    }

    // ------------------------------------------------------------------
    // Compras (Checkout)
    // ------------------------------------------------------------------

    private Resultado compraCompletada(JsonNode sesion) {
        JsonNode datos = sesion.path("metadata");
        String pagado = sesion.path("payment_status").asText("");
        // Una compra sin pagar (un método de pago asíncrono, por ejemplo) no da nada todavía: cuando
        // se pague llega "async_payment_succeeded".
        if (!pagado.equals("paid") && !pagado.equals("no_payment_required")) {
            return Resultado.IGNORADO;
        }

        String tipo = datos.path("kind").asText("");
        return switch (tipo) {
            case "license" -> licenciaComprada(sesion, datos);
            case "pack" -> paqueteComprado(sesion, datos);
            default -> Resultado.IGNORADO;
        };
    }

    private Resultado licenciaComprada(JsonNode sesion, JsonNode datos) {
        String suscripcion = sesion.path("subscription").asText("");
        UUID organizacionId = uuid(datos.path("organization_id").asText(""));
        if (suscripcion.isBlank() || organizacionId == null) {
            log.warn("Compra de licencia sin suscripción u organización: {}", sesion.path("id").asText(""));
            return Resultado.IGNORADO;
        }
        if (licencias.findByStripeSubscriptionId(suscripcion).isPresent()) {
            return Resultado.PROCESADO; // el mismo cobro, ya aplicado
        }

        Optional<Organization> organizacion = organizaciones.findById(organizacionId);
        if (organizacion.isEmpty()) {
            log.error("Se cobró una licencia de una organización que no existe: {}", organizacionId);
            return Resultado.IGNORADO;
        }
        String cliente = sesion.path("customer").asText("");
        if (!cliente.isBlank() && organizacion.get().getStripeCustomerId() == null) {
            organizacion.get().setStripeCustomerId(cliente);
            organizaciones.save(organizacion.get());
        }

        LocalDateTime finDePeriodo = finDePeriodoDe(suscripcion);

        UUID workspaceId = uuid(datos.path("workspace_id").asText(""));
        if (workspaceId != null) {
            return activarLicenciaDeUnEspacio(workspaceId, organizacionId, suscripcion, finDePeriodo);
        }
        return crearEspacioConLicencia(datos, organizacionId, suscripcion, finDePeriodo);
    }

    /** El espacio ya existía (en prueba, o archivado por falta de pago): se le pone su suscripción. */
    private Resultado activarLicenciaDeUnEspacio(UUID workspaceId, UUID organizacionId, String suscripcion,
            LocalDateTime finDePeriodo) {
        Optional<Workspace> encontrado = workspaces.findById(workspaceId);
        if (encontrado.isEmpty() || encontrado.get().getOrganization() == null
                || !encontrado.get().getOrganization().getId().equals(organizacionId)) {
            log.error("Se cobró una licencia para un espacio que no es de la organización: {}", workspaceId);
            return Resultado.IGNORADO;
        }
        License licencia = licencias.findByWorkspaceId(workspaceId).orElseGet(() -> License.builder()
                .organizationId(organizacionId).workspaceId(workspaceId).status(LicenseStatus.ACTIVE).build());
        activar(licencia, suscripcion, finDePeriodo);
        restaurarSiLoArchivoElBarrido(licencia, encontrado.get());
        return Resultado.PROCESADO;
    }

    /** Un espacio nuevo: nace ahora que se pagó, con los datos del negocio que se contaron al comprar. */
    private Resultado crearEspacioConLicencia(JsonNode datos, UUID organizacionId, String suscripcion,
            LocalDateTime finDePeriodo) {
        UUID compradorId = uuid(datos.path("user_id").asText(""));
        String nombre = datos.path("name").asText("").trim();
        if (compradorId == null || nombre.isEmpty()) {
            log.error("Compra de licencia nueva sin comprador o sin nombre de espacio ({})", organizacionId);
            return Resultado.IGNORADO;
        }

        WorkspaceCreateRequest peticion = new WorkspaceCreateRequest();
        peticion.setName(nombre);
        peticion.setGiro(vacioANulo(datos.path("giro").asText("")));
        peticion.setCiudad(vacioANulo(datos.path("ciudad").asText("")));
        peticion.setDescripcion(vacioANulo(datos.path("descripcion").asText("")));
        peticion.setObjetivo(objetivo(datos.path("objetivo").asText("")));

        Workspace nuevo = membresias.crearParaLicencia(organizacionId, compradorId, peticion);
        activar(License.builder().organizationId(organizacionId).workspaceId(nuevo.getId())
                .status(LicenseStatus.ACTIVE).build(), suscripcion, finDePeriodo);
        return Resultado.PROCESADO;
    }

    private Resultado paqueteComprado(JsonNode sesion, JsonNode datos) {
        UUID workspaceId = uuid(datos.path("workspace_id").asText(""));
        String codigo = datos.path("pack_code").asText("");
        String referencia = sesion.path("id").asText("");
        if (workspaceId == null || codigo.isBlank() || referencia.isBlank()) {
            return Resultado.IGNORADO;
        }
        // Los créditos son los del paquete HOY en la tabla: el precio se cobró en Stripe, pero cuántos
        // créditos trae lo decidimos nosotros.
        Optional<Integer> cantidad = paquetes.findByCode(codigo).map(p -> p.getCredits());
        if (cantidad.isEmpty()) {
            log.error("Se cobró un paquete que ya no existe: {}", codigo);
            return Resultado.IGNORADO;
        }
        creditos.agregarPaquete(workspaceId, cantidad.get(), referencia);
        return Resultado.PROCESADO;
    }

    // ------------------------------------------------------------------
    // Suscripciones
    // ------------------------------------------------------------------

    private Resultado facturaPagada(JsonNode factura) {
        String suscripcion = suscripcionDeLaFactura(factura);
        if (suscripcion.isBlank()) {
            return Resultado.IGNORADO; // una factura suelta, no de una licencia
        }
        Optional<License> encontrada = licencias.findByStripeSubscriptionId(suscripcion);
        if (encontrada.isEmpty()) {
            return Resultado.REINTENTAR; // la compra todavía no se procesó
        }
        License licencia = encontrada.get();

        LocalDateTime fin = finDePeriodoDeLaFactura(factura);
        if (fin == null) {
            fin = finDePeriodoDe(suscripcion);
        }
        licencia.setStatus(LicenseStatus.ACTIVE);
        licencia.setCurrentPeriodEnd(fin);
        licencia.setGraceUntil(null);
        licencia.setUpdatedAt(LocalDateTime.now());
        licencias.save(licencia);

        // Los créditos del mes: se reinician con cada factura pagada, no se acumulan.
        creditos.otorgarMensuales(licencia.getWorkspaceId(), config.creditosMensuales(), fin,
                "inv:" + factura.path("id").asText(""));
        workspaces.findById(licencia.getWorkspaceId()).ifPresent(w -> restaurarSiLoArchivoElBarrido(licencia, w));
        return Resultado.PROCESADO;
    }

    private Resultado cobroFallido(JsonNode factura) {
        String suscripcion = suscripcionDeLaFactura(factura);
        Optional<License> encontrada = suscripcion.isBlank() ? Optional.empty()
                : licencias.findByStripeSubscriptionId(suscripcion);
        if (encontrada.isEmpty()) {
            return Resultado.IGNORADO;
        }
        ponerEnGracia(encontrada.get());
        return Resultado.PROCESADO;
    }

    private Resultado suscripcionActualizada(JsonNode sub) {
        Optional<License> encontrada = licencias.findByStripeSubscriptionId(sub.path("id").asText(""));
        if (encontrada.isEmpty()) {
            return Resultado.IGNORADO; // la compra la establece al completarse
        }
        License licencia = encontrada.get();

        licencia.setCancelAtPeriodEnd(sub.path("cancel_at_period_end").asBoolean(false));
        LocalDateTime fin = finDePeriodoDeLaSuscripcion(sub);
        if (fin != null) {
            licencia.setCurrentPeriodEnd(fin);
        }

        switch (sub.path("status").asText("")) {
            case "active", "trialing" -> {
                if (licencia.getStatus() == LicenseStatus.PAST_DUE) {
                    licencia.setStatus(LicenseStatus.ACTIVE);
                    licencia.setGraceUntil(null);
                }
            }
            case "past_due" -> ponerEnGracia(licencia);
            case "unpaid", "canceled", "incomplete_expired" -> licencia.setStatus(LicenseStatus.ENDED);
            default -> {
            }
        }
        licencia.setUpdatedAt(LocalDateTime.now());
        licencias.save(licencia);
        return Resultado.PROCESADO;
    }

    private Resultado suscripcionTerminada(JsonNode sub) {
        Optional<License> encontrada = licencias.findByStripeSubscriptionId(sub.path("id").asText(""));
        if (encontrada.isEmpty()) {
            return Resultado.IGNORADO;
        }
        License licencia = encontrada.get();
        // Terminó (por cancelación pedida o por falta de pago): el barrido archiva el espacio.
        licencia.setStatus(LicenseStatus.ENDED);
        licencia.setUpdatedAt(LocalDateTime.now());
        licencias.save(licencia);
        return Resultado.PROCESADO;
    }

    // ------------------------------------------------------------------
    // Piezas
    // ------------------------------------------------------------------

    private void activar(License licencia, String suscripcion, LocalDateTime finDePeriodo) {
        licencia.setStatus(LicenseStatus.ACTIVE);
        licencia.setStripeSubscriptionId(suscripcion);
        licencia.setCurrentPeriodEnd(finDePeriodo);
        licencia.setGraceUntil(null);
        licencia.setCancelAtPeriodEnd(false);
        licencia.setUpdatedAt(LocalDateTime.now());
        licencias.save(licencia);
    }

    /** Si el barrido lo había archivado por falta de pago, pagar lo abre en el acto. */
    private void restaurarSiLoArchivoElBarrido(License licencia, Workspace workspace) {
        if (licencia.isArchivedBySweep() && workspace.archivado()) {
            workspace.setArchivedAt(null);
            workspaces.save(workspace);
            licencia.setArchivedBySweep(false);
            licencias.save(licencia);
        }
    }

    private void ponerEnGracia(License licencia) {
        // La gracia cuenta desde el PRIMER fallo: reintentos fallidos no la alargan.
        if (licencia.getStatus() != LicenseStatus.PAST_DUE) {
            licencia.setStatus(LicenseStatus.PAST_DUE);
            licencia.setGraceUntil(LocalDateTime.now().plusDays(config.diasDeGracia()));
        }
        licencia.setUpdatedAt(LocalDateTime.now());
        licencias.save(licencia);
    }

    /** Stripe cambió dónde va la suscripción de una factura entre versiones de su API. */
    private static String suscripcionDeLaFactura(JsonNode factura) {
        String directa = factura.path("subscription").asText("");
        if (!directa.isBlank()) {
            return directa;
        }
        return factura.path("parent").path("subscription_details").path("subscription").asText("");
    }

    private static LocalDateTime finDePeriodoDeLaFactura(JsonNode factura) {
        long mayor = 0;
        for (JsonNode linea : factura.path("lines").path("data")) {
            mayor = Math.max(mayor, linea.path("period").path("end").asLong(0));
        }
        return mayor > 0 ? aFecha(mayor) : null;
    }

    private static LocalDateTime finDePeriodoDeLaSuscripcion(JsonNode sub) {
        long directo = sub.path("current_period_end").asLong(0);
        if (directo > 0) {
            return aFecha(directo);
        }
        long enArticulo = sub.path("items").path("data").path(0).path("current_period_end").asLong(0);
        return enArticulo > 0 ? aFecha(enArticulo) : null;
    }

    /** El fin del periodo, preguntándole a Stripe; si no se puede, un mes: la factura pagada lo corrige. */
    private LocalDateTime finDePeriodoDe(String suscripcion) {
        try {
            LocalDateTime fin = finDePeriodoDeLaSuscripcion(stripe.obtenerSuscripcion(suscripcion));
            if (fin != null) {
                return fin;
            }
        } catch (RuntimeException ex) {
            log.warn("No se pudo leer la suscripción {} en Stripe: {}", suscripcion, ex.getMessage());
        }
        return LocalDateTime.now().plusMonths(1);
    }

    private static LocalDateTime aFecha(long segundos) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(segundos), ZoneId.systemDefault());
    }

    private static UUID uuid(String texto) {
        try {
            return texto == null || texto.isBlank() ? null : UUID.fromString(texto.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String vacioANulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    private static ObjetivoRedes objetivo(String texto) {
        try {
            return texto == null || texto.isBlank() ? null : ObjetivoRedes.valueOf(texto.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
