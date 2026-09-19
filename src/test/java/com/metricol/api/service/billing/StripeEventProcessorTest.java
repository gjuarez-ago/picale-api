package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.CreditPack;
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
import com.metricol.api.service.billing.StripeEventProcessor.Resultado;

/**
 * Donde el dinero se vuelve derechos. Cada prueba es una regla de negocio:
 * lo pagado da acceso, lo repetido no da dos veces, lo que falla no regala nada.
 */
class StripeEventProcessorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StripeEventRepository eventos;
    private LicenseRepository licencias;
    private WorkspaceRepository workspaces;
    private OrganizationRepository organizaciones;
    private CreditPackRepository paquetes;
    private CreditService creditos;
    private BillingConfig config;
    private WorkspaceMembershipService membresias;
    private StripeClient stripe;
    private StripeEventProcessor procesador;

    private final List<License> guardadas = new ArrayList<>();
    private final List<String> eventosGuardados = new ArrayList<>();
    private Organization org;
    private UUID comprador;

    @BeforeEach
    void preparar() {
        eventos = mock(StripeEventRepository.class);
        licencias = mock(LicenseRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        organizaciones = mock(OrganizationRepository.class);
        paquetes = mock(CreditPackRepository.class);
        creditos = mock(CreditService.class);
        config = mock(BillingConfig.class);
        membresias = mock(WorkspaceMembershipService.class);
        stripe = mock(StripeClient.class);
        procesador = new StripeEventProcessor(eventos, licencias, workspaces, organizaciones, paquetes, creditos,
                config, membresias, stripe);

        when(config.creditosMensuales()).thenReturn(5);
        when(config.diasDeGracia()).thenReturn(7);

        org = new Organization();
        org.setId(UUID.randomUUID());
        comprador = UUID.randomUUID();
        when(organizaciones.findById(org.getId())).thenReturn(Optional.of(org));

        when(eventos.existsById(anyString())).thenAnswer(i -> eventosGuardados.contains((String) i.getArgument(0)));
        when(eventos.save(any(StripeEvent.class))).thenAnswer(i -> {
            StripeEvent e = i.getArgument(0);
            eventosGuardados.add(e.getId());
            return e;
        });
        when(licencias.save(any(License.class))).thenAnswer(i -> {
            License l = i.getArgument(0);
            if (l.getId() == null) {
                l.setId(UUID.randomUUID());
            }
            if (!guardadas.contains(l)) {
                guardadas.add(l);
            }
            return l;
        });
        when(licencias.findByStripeSubscriptionId(anyString())).thenAnswer(i -> guardadas.stream()
                .filter(l -> i.getArgument(0).equals(l.getStripeSubscriptionId())).findFirst());
        when(licencias.findByWorkspaceId(any())).thenAnswer(i -> guardadas.stream()
                .filter(l -> l.getWorkspaceId().equals(i.getArgument(0))).findFirst());

        // Stripe dice que la suscripción termina en una fecha fija.
        when(stripe.obtenerSuscripcion(anyString())).thenReturn(leer("{\"current_period_end\": " + FIN_EN_SEGUNDOS + "}"));
    }

    private static final long FIN_EN_SEGUNDOS = Instant.parse("2026-10-19T12:00:00Z").getEpochSecond();
    private static final LocalDateTime FIN = LocalDateTime.ofInstant(Instant.ofEpochSecond(FIN_EN_SEGUNDOS),
            ZoneId.systemDefault());

    private static JsonNode leer(String texto) {
        try {
            return JSON.readTree(texto);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Workspace workspace() {
        Workspace w = Workspace.builder().name("Cliente").organization(org).build();
        w.setId(UUID.randomUUID());
        when(workspaces.findById(w.getId())).thenReturn(Optional.of(w));
        return w;
    }

    private License licenciaPagada(Workspace w, String suscripcion, LicenseStatus estado) {
        License l = License.builder().organizationId(org.getId()).workspaceId(w.getId()).status(estado)
                .stripeSubscriptionId(suscripcion).currentPeriodEnd(LocalDateTime.now().plusDays(5)).build();
        licencias.save(l);
        return l;
    }

    private static String evento(String id, String tipo, String objeto) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + tipo + "\",\"data\":{\"object\":" + objeto + "}}";
    }

    private Resultado enviar(String id, String tipo, String objeto) {
        return procesador.procesar(leer(evento(id, tipo, objeto)));
    }

    // ================================================================ compra de un espacio nuevo

    private String sesionDeLicenciaNueva(String pago) {
        return "{\"id\":\"cs_1\",\"mode\":\"subscription\",\"payment_status\":\"" + pago + "\","
                + "\"customer\":\"cus_1\",\"subscription\":\"sub_1\",\"metadata\":{\"kind\":\"license\","
                + "\"organization_id\":\"" + org.getId() + "\",\"user_id\":\"" + comprador + "\","
                + "\"name\":\"Tacos El Güero\",\"giro\":\"Restaurante o cafetería\",\"ciudad\":\"Mérida, Yucatán\","
                + "\"descripcion\":\"Tacos\",\"objetivo\":\"MAS_CLIENTES\"}}";
    }

    @Test
    @DisplayName("una compra pagada crea el espacio con los datos del negocio y su licencia activa")
    void licenciaNuevaPagada() {
        Workspace nuevo = workspace();
        when(membresias.crearParaLicencia(eq(org.getId()), eq(comprador), any(WorkspaceCreateRequest.class)))
                .thenReturn(nuevo);

        assertThat(enviar("evt_1", "checkout.session.completed", sesionDeLicenciaNueva("paid")))
                .isEqualTo(Resultado.PROCESADO);

        ArgumentCaptor<WorkspaceCreateRequest> datos = ArgumentCaptor.forClass(WorkspaceCreateRequest.class);
        verify(membresias).crearParaLicencia(eq(org.getId()), eq(comprador), datos.capture());
        assertThat(datos.getValue().getName()).isEqualTo("Tacos El Güero");
        assertThat(datos.getValue().getGiro()).isEqualTo("Restaurante o cafetería");
        assertThat(datos.getValue().getCiudad()).isEqualTo("Mérida, Yucatán");
        assertThat(datos.getValue().getObjetivo()).isEqualTo(ObjetivoRedes.MAS_CLIENTES);

        assertThat(guardadas).hasSize(1);
        License l = guardadas.get(0);
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(l.getStripeSubscriptionId()).isEqualTo("sub_1");
        assertThat(l.getWorkspaceId()).isEqualTo(nuevo.getId());
        assertThat(l.getCurrentPeriodEnd()).isEqualTo(FIN);
        // El cliente de Stripe queda ligado a la organización para cobrarle las siguientes.
        assertThat(org.getStripeCustomerId()).isEqualTo("cus_1");
    }

    @Test
    @DisplayName("una compra SIN pagar no crea ni activa nada")
    void sinPagarNoDaNada() {
        assertThat(enviar("evt_1", "checkout.session.completed", sesionDeLicenciaNueva("unpaid")))
                .isEqualTo(Resultado.IGNORADO);

        verify(membresias, never()).crearParaLicencia(any(), any(), any());
        assertThat(guardadas).isEmpty();
    }

    @Test
    @DisplayName("el mismo aviso dos veces no crea dos espacios")
    void avisoRepetido() {
        Workspace creado = workspace();
        when(membresias.crearParaLicencia(any(), any(), any())).thenReturn(creado);

        enviar("evt_1", "checkout.session.completed", sesionDeLicenciaNueva("paid"));
        enviar("evt_1", "checkout.session.completed", sesionDeLicenciaNueva("paid"));

        verify(membresias, times(1)).crearParaLicencia(any(), any(), any());
        assertThat(guardadas).hasSize(1);
    }

    @Test
    @DisplayName("la misma suscripción en otro aviso (otro evt_) tampoco duplica el espacio")
    void mismaSuscripcionOtroAviso() {
        Workspace creado = workspace();
        when(membresias.crearParaLicencia(any(), any(), any())).thenReturn(creado);

        enviar("evt_1", "checkout.session.completed", sesionDeLicenciaNueva("paid"));
        Resultado segundo = enviar("evt_2", "checkout.session.async_payment_succeeded", sesionDeLicenciaNueva("paid"));

        assertThat(segundo).isEqualTo(Resultado.PROCESADO);
        verify(membresias, times(1)).crearParaLicencia(any(), any(), any());
    }

    @Test
    @DisplayName("una compra sin comprador o sin nombre no se puede cumplir: se ignora y se deja registro")
    void compraIncompleta() {
        String sinNombre = sesionDeLicenciaNueva("paid").replace("\"name\":\"Tacos El Güero\",", "");

        assertThat(enviar("evt_1", "checkout.session.completed", sinNombre)).isEqualTo(Resultado.IGNORADO);
        verify(membresias, never()).crearParaLicencia(any(), any(), any());
    }

    // ================================================================ contratar un espacio que ya existe

    private String sesionDeEspacioExistente(UUID espacio, UUID organizacion) {
        return "{\"id\":\"cs_2\",\"mode\":\"subscription\",\"payment_status\":\"paid\",\"customer\":\"cus_1\","
                + "\"subscription\":\"sub_2\",\"metadata\":{\"kind\":\"license\",\"organization_id\":\"" + organizacion
                + "\",\"user_id\":\"" + comprador + "\",\"workspace_id\":\"" + espacio + "\"}}";
    }

    @Test
    @DisplayName("pagar la licencia de un espacio en prueba lo activa, sin crear otro")
    void activarUnoEnPrueba() {
        Workspace w = workspace();
        License prueba = License.builder().organizationId(org.getId()).workspaceId(w.getId())
                .status(LicenseStatus.TRIALING).trialEndsAt(LocalDateTime.now().plusDays(3)).build();
        licencias.save(prueba);

        enviar("evt_1", "checkout.session.completed", sesionDeEspacioExistente(w.getId(), org.getId()));

        assertThat(prueba.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(prueba.getStripeSubscriptionId()).isEqualTo("sub_2");
        verify(membresias, never()).crearParaLicencia(any(), any(), any());
    }

    @Test
    @DisplayName("pagar un espacio que el barrido archivó por falta de pago lo abre en el acto")
    void pagarRestaura() {
        Workspace w = workspace();
        w.setArchivedAt(LocalDateTime.now().minusDays(2));
        License terminada = License.builder().organizationId(org.getId()).workspaceId(w.getId())
                .status(LicenseStatus.ENDED).archivedBySweep(true).build();
        licencias.save(terminada);

        enviar("evt_1", "checkout.session.completed", sesionDeEspacioExistente(w.getId(), org.getId()));

        assertThat(w.archivado()).isFalse();
        assertThat(terminada.isArchivedBySweep()).isFalse();
        assertThat(terminada.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
    }

    @Test
    @DisplayName("no se le pone licencia a un espacio de OTRA organización, aunque el aviso lo pida")
    void espacioAjeno() {
        Organization otra = new Organization();
        otra.setId(UUID.randomUUID());
        when(organizaciones.findById(otra.getId())).thenReturn(Optional.of(otra));
        Workspace deLaOrg = workspace(); // pertenece a org

        assertThat(enviar("evt_1", "checkout.session.completed", sesionDeEspacioExistente(deLaOrg.getId(), otra.getId())))
                .isEqualTo(Resultado.IGNORADO);
        assertThat(guardadas).isEmpty();
    }

    // ================================================================ paquetes de créditos

    private String sesionDePaquete(String codigo, UUID espacio) {
        return "{\"id\":\"cs_pack\",\"mode\":\"payment\",\"payment_status\":\"paid\",\"metadata\":{\"kind\":\"pack\","
                + "\"organization_id\":\"" + org.getId() + "\",\"workspace_id\":\"" + espacio + "\",\"pack_code\":\""
                + codigo + "\"}}";
    }

    @Test
    @DisplayName("un paquete pagado suma los créditos que trae, con la compra como referencia")
    void paquetePagado() {
        UUID espacio = UUID.randomUUID();
        when(paquetes.findByCode("PACK_25")).thenReturn(Optional.of(CreditPack.builder().code("PACK_25").credits(25).build()));

        assertThat(enviar("evt_1", "checkout.session.completed", sesionDePaquete("PACK_25", espacio)))
                .isEqualTo(Resultado.PROCESADO);

        verify(creditos).agregarPaquete(espacio, 25, "cs_pack");
    }

    @Test
    @DisplayName("un paquete que ya no existe no suma nada")
    void paqueteInexistente() {
        when(paquetes.findByCode("PACK_X")).thenReturn(Optional.empty());

        assertThat(enviar("evt_1", "checkout.session.completed", sesionDePaquete("PACK_X", UUID.randomUUID())))
                .isEqualTo(Resultado.IGNORADO);
        verify(creditos, never()).agregarPaquete(any(), anyInt(), anyString());
    }

    // ================================================================ facturas

    @Test
    @DisplayName("una factura pagada renueva el periodo, quita la gracia y reinicia los créditos del mes")
    void facturaPagada() {
        Workspace w = workspace();
        License l = licenciaPagada(w, "sub_1", LicenseStatus.PAST_DUE);
        l.setGraceUntil(LocalDateTime.now().plusDays(3));

        String factura = "{\"id\":\"in_9\",\"subscription\":\"sub_1\",\"lines\":{\"data\":[{\"period\":{\"end\":"
                + FIN_EN_SEGUNDOS + "}}]}}";
        assertThat(enviar("evt_1", "invoice.paid", factura)).isEqualTo(Resultado.PROCESADO);

        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(l.getCurrentPeriodEnd()).isEqualTo(FIN);
        assertThat(l.getGraceUntil()).isNull();
        verify(creditos).otorgarMensuales(w.getId(), 5, FIN, "inv:in_9");
    }

    @Test
    @DisplayName("la factura con la forma nueva de la API de Stripe (suscripción dentro de 'parent') también se entiende")
    void facturaFormaNueva() {
        Workspace w = workspace();
        License l = licenciaPagada(w, "sub_1", LicenseStatus.ACTIVE);

        String factura = "{\"id\":\"in_10\",\"parent\":{\"subscription_details\":{\"subscription\":\"sub_1\"}},"
                + "\"lines\":{\"data\":[{\"period\":{\"end\":" + FIN_EN_SEGUNDOS + "}}]}}";

        assertThat(enviar("evt_1", "invoice.paid", factura)).isEqualTo(Resultado.PROCESADO);
        verify(creditos).otorgarMensuales(w.getId(), 5, FIN, "inv:in_10");
        assertThat(l.getCurrentPeriodEnd()).isEqualTo(FIN);
    }

    @Test
    @DisplayName("una factura que llega ANTES que su compra pide reintento y NO se marca como procesada")
    void facturaAntesQueLaCompra() {
        String factura = "{\"id\":\"in_9\",\"subscription\":\"sub_desconocida\",\"lines\":{\"data\":[]}}";

        assertThat(enviar("evt_1", "invoice.paid", factura)).isEqualTo(Resultado.REINTENTAR);
        // Al reintentarse, tiene que poder procesarse: no puede quedar registrada.
        assertThat(eventosGuardados).doesNotContain("evt_1");
    }

    @Test
    @DisplayName("una factura que no es de una suscripción (suelta) se ignora")
    void facturaSuelta() {
        assertThat(enviar("evt_1", "invoice.paid", "{\"id\":\"in_1\"}")).isEqualTo(Resultado.IGNORADO);
    }

    @Test
    @DisplayName("un cobro fallido abre la gracia; otro fallo después NO la alarga")
    void cobroFallido() {
        Workspace w = workspace();
        License l = licenciaPagada(w, "sub_1", LicenseStatus.ACTIVE);

        enviar("evt_1", "invoice.payment_failed", "{\"id\":\"in_1\",\"subscription\":\"sub_1\"}");
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        LocalDateTime graciaOriginal = l.getGraceUntil();
        assertThat(graciaOriginal).isBetween(LocalDateTime.now().plusDays(6), LocalDateTime.now().plusDays(8));

        enviar("evt_2", "invoice.payment_failed", "{\"id\":\"in_2\",\"subscription\":\"sub_1\"}");
        assertThat(l.getGraceUntil()).isEqualTo(graciaOriginal);
    }

    // ================================================================ suscripciones

    @Test
    @DisplayName("pedir que no se renueve queda reflejado, y la licencia sigue activa hasta el fin")
    void cancelarAlFinal() {
        Workspace w = workspace();
        License l = licenciaPagada(w, "sub_1", LicenseStatus.ACTIVE);

        String sub = "{\"id\":\"sub_1\",\"status\":\"active\",\"cancel_at_period_end\":true,"
                + "\"current_period_end\":" + FIN_EN_SEGUNDOS + "}";
        enviar("evt_1", "customer.subscription.updated", sub);

        assertThat(l.isCancelAtPeriodEnd()).isTrue();
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(l.getCurrentPeriodEnd()).isEqualTo(FIN);
    }

    @Test
    @DisplayName("el fin del periodo también se lee de los artículos (forma nueva de la API)")
    void periodoEnArticulos() {
        Workspace w = workspace();
        License l = licenciaPagada(w, "sub_1", LicenseStatus.ACTIVE);

        String sub = "{\"id\":\"sub_1\",\"status\":\"active\",\"cancel_at_period_end\":false,"
                + "\"items\":{\"data\":[{\"current_period_end\":" + FIN_EN_SEGUNDOS + "}]}}";
        enviar("evt_1", "customer.subscription.updated", sub);

        assertThat(l.getCurrentPeriodEnd()).isEqualTo(FIN);
    }

    @Test
    @DisplayName("si Stripe la marca past_due entra a la gracia; si vuelve a active, sale de ella")
    void gracia() {
        Workspace w = workspace();
        License l = licenciaPagada(w, "sub_1", LicenseStatus.ACTIVE);

        enviar("evt_1", "customer.subscription.updated", "{\"id\":\"sub_1\",\"status\":\"past_due\"}");
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.PAST_DUE);
        assertThat(l.getGraceUntil()).isNotNull();

        enviar("evt_2", "customer.subscription.updated", "{\"id\":\"sub_1\",\"status\":\"active\"}");
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(l.getGraceUntil()).isNull();
    }

    @Test
    @DisplayName("unpaid o canceled terminan la licencia")
    void terminanPorEstado() {
        Workspace w = workspace();
        License l = licenciaPagada(w, "sub_1", LicenseStatus.ACTIVE);

        enviar("evt_1", "customer.subscription.updated", "{\"id\":\"sub_1\",\"status\":\"unpaid\"}");
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ENDED);
    }

    @Test
    @DisplayName("cuando la suscripción se elimina (llegó el fin de lo pagado), la licencia termina")
    void suscripcionEliminada() {
        Workspace w = workspace();
        License l = licenciaPagada(w, "sub_1", LicenseStatus.ACTIVE);
        l.setCancelAtPeriodEnd(true);

        assertThat(enviar("evt_1", "customer.subscription.deleted", "{\"id\":\"sub_1\"}"))
                .isEqualTo(Resultado.PROCESADO);

        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ENDED);
    }

    @Test
    @DisplayName("avisos de suscripciones que no son nuestras se ignoran sin error")
    void suscripcionAjena() {
        assertThat(enviar("evt_1", "customer.subscription.updated", "{\"id\":\"sub_de_otro\",\"status\":\"active\"}"))
                .isEqualTo(Resultado.IGNORADO);
        assertThat(enviar("evt_2", "customer.subscription.deleted", "{\"id\":\"sub_de_otro\"}"))
                .isEqualTo(Resultado.IGNORADO);
    }

    @Test
    @DisplayName("un tipo de aviso que no conocemos se ignora, pero se registra para no repetirlo")
    void tipoDesconocido() {
        assertThat(enviar("evt_1", "charge.refunded", "{}")).isEqualTo(Resultado.IGNORADO);
        assertThat(eventosGuardados).contains("evt_1");
    }

    @Test
    @DisplayName("un aviso sin id o sin tipo no se procesa")
    void avisoIncompleto() {
        assertThat(procesador.procesar(leer("{\"type\":\"invoice.paid\"}"))).isEqualTo(Resultado.IGNORADO);
        assertThat(procesador.procesar(leer("{\"id\":\"evt_1\"}"))).isEqualTo(Resultado.IGNORADO);
    }

    @Test
    @DisplayName("si Stripe no contesta al leer el periodo, se usa un mes y la factura pagada lo corrige")
    void stripeNoContesta() {
        when(stripe.obtenerSuscripcion(anyString())).thenThrow(new IllegalStateException("caído"));
        Workspace creado = workspace();
        when(membresias.crearParaLicencia(any(), any(), any())).thenReturn(creado);

        enviar("evt_1", "checkout.session.completed", sesionDeLicenciaNueva("paid"));

        assertThat(guardadas.get(0).getCurrentPeriodEnd()).isAfter(LocalDateTime.now().plusDays(27));
    }
}
