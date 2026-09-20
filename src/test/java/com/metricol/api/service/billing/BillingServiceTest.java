package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.config.StripeProperties;
import com.metricol.api.entity.CreditPack;
import com.metricol.api.entity.License;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.enums.ObjetivoRedes;
import com.metricol.api.enums.OrgPermission;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.WorkspaceCreateRequest;
import com.metricol.api.models.response.BillingSummaryResponse;
import com.metricol.api.models.response.BillingSummaryResponse.LicenseView;
import com.metricol.api.models.response.PlansResponse;
import com.metricol.api.repository.CreditPackRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.OrganizationService;

/** Lo que la persona hace con sus pagos: preparar la compra, mandarla a Stripe, cancelar la renovación. */
class BillingServiceTest {

    private BillingConfig config;
    private StripeProperties stripeProps;
    private StripeClient stripe;
    private LicenseService licencias;
    private CreditService creditos;
    private OrganizationService organizaciones;
    private OrganizationRepository organizacionesRepo;
    private WorkspaceRepository workspaces;
    private CreditPackRepository paquetes;
    private StripeProductCatalog catalogo;
    private BillingService servicio;

    private Organization org;
    private User usuario;
    private Workspace actual;

    @BeforeEach
    void preparar() {
        config = mock(BillingConfig.class);
        stripeProps = new StripeProperties();
        stripe = mock(StripeClient.class);
        licencias = mock(LicenseService.class);
        creditos = mock(CreditService.class);
        organizaciones = mock(OrganizationService.class);
        organizacionesRepo = mock(OrganizationRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        paquetes = mock(CreditPackRepository.class);
        catalogo = mock(StripeProductCatalog.class);
        servicio = new BillingService(config, stripeProps, stripe, licencias, creditos, organizaciones,
                organizacionesRepo, workspaces, paquetes, catalogo, "https://picale.click/");

        when(config.habilitado()).thenReturn(true);
        when(config.moneda()).thenReturn("mxn");
        when(config.listaDeLicencia()).thenReturn(34900);
        when(config.listaDeAdicional()).thenReturn(24900);
        when(config.diasDeAviso()).thenReturn(5);
        when(config.impuestoIncluido()).thenReturn(true);
        when(catalogo.productoDeLicencia(false)).thenReturn("picale_licencia");
        when(catalogo.productoDeLicencia(true)).thenReturn("picale_licencia_adicional");
        when(catalogo.productoDePaquete(any())).thenReturn("picale_paquete_pack_25");
        when(config.paquetesEnVenta()).thenReturn(List.of());
        when(stripe.disponible()).thenReturn(true);

        org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("Agencia");

        actual = workspace("Cliente actual");
        usuario = mock(User.class);
        when(usuario.getId()).thenReturn(UUID.randomUUID());
        when(usuario.getEmail()).thenReturn("dueno@agencia.com");
        when(usuario.getWorkspace()).thenReturn(actual);

        when(organizaciones.deLaSesion(usuario)).thenReturn(org);
        when(organizaciones.rolDe(any(), any())).thenReturn(OrgRole.OWNER);
        when(creditos.saldo(any())).thenReturn(new CreditService.Saldo(4, 10, null));
        when(licencias.deWorkspace(any())).thenReturn(Optional.empty());
        when(stripe.crearCompra(anyString(), any(StripeClient.Tarifa.class), anyMap(), anyString(), anyString(), any()))
                .thenReturn("https://checkout.stripe.com/c/pay/x");
    }

    /** Una tarifa con ese producto, monto y periodicidad (la moneda se prueba aparte). */
    private static StripeClient.Tarifa tarifa(String producto, long centavos, boolean mensual) {
        return argThat(t -> t != null && t.producto().equals(producto)
                && t.centavos() == centavos && t.mensual() == mensual);
    }

    private Workspace workspace(String nombre) {
        Workspace w = Workspace.builder().name(nombre).organization(org).build();
        w.setId(UUID.randomUUID());
        when(workspaces.findById(w.getId())).thenReturn(Optional.of(w));
        return w;
    }

    private License licencia(Workspace w, LicenseStatus estado, String suscripcion) {
        License l = License.builder().organizationId(org.getId()).workspaceId(w.getId()).status(estado)
                .stripeSubscriptionId(suscripcion).currentPeriodEnd(LocalDateTime.now().plusDays(10))
                .trialEndsAt(LocalDateTime.now().plusDays(10)).build();
        when(licencias.deWorkspace(w.getId())).thenReturn(Optional.of(l));
        return l;
    }

    // ------------------------------------------------------------ resumen

    @Test
    @DisplayName("con los cobros apagados el resumen lo dice y no enseña nada más")
    void resumenApagado() {
        when(config.habilitado()).thenReturn(false);

        BillingSummaryResponse r = servicio.resumen(usuario);

        assertThat(r.enabled()).isFalse();
        assertThat(r.workspace()).isNull();
        assertThat(r.licenses()).isEmpty();
    }

    @Test
    @DisplayName("el resumen trae la licencia y los créditos del espacio actual")
    void resumenDelEspacio() {
        licencia(actual, LicenseStatus.ACTIVE, "sub_1");

        BillingSummaryResponse r = servicio.resumen(usuario);

        assertThat(r.enabled()).isTrue();
        assertThat(r.workspace().name()).isEqualTo("Cliente actual");
        assertThat(r.workspace().license().status()).isEqualTo("ACTIVE");
        assertThat(r.workspace().license().usable()).isTrue();
        assertThat(r.workspace().credits().total()).isEqualTo(14);
        assertThat(r.workspace().credits().monthly()).isEqualTo(4);
    }

    @Test
    @DisplayName("quien administra ve todas las licencias de la organización; quien no, ninguna")
    void resumenSegunElRol() {
        Workspace otro = workspace("Otro cliente");
        License l = licencia(otro, LicenseStatus.TRIALING, null);
        when(licencias.deLaOrganizacion(org.getId())).thenReturn(List.of(l));

        assertThat(servicio.resumen(usuario).licenses()).hasSize(1);
        assertThat(servicio.resumen(usuario).licenses().get(0).workspaceName()).isEqualTo("Otro cliente");

        when(organizaciones.rolDe(any(), any())).thenReturn(OrgRole.MEMBER);
        assertThat(servicio.resumen(usuario).licenses()).isEmpty();
    }

    // ------------------------------------------------------------ espacio nuevo

    @Test
    @DisplayName("comprar un espacio nuevo: pide el permiso, crea el cliente, y manda a pagar con los datos del negocio")
    void espacioNuevo() {
        when(stripe.crearCliente(anyString(), anyString(), anyString())).thenReturn("cus_nuevo");
        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("  Tacos El Güero ");
        datos.setGiro("Restaurante o cafetería");
        datos.setCiudad("Mérida, Yucatán");
        datos.setObjetivo(ObjetivoRedes.VENDER_MAS);

        String url = servicio.comprarEspacioNuevo(usuario, datos);

        assertThat(url).startsWith("https://checkout.stripe.com");
        verify(organizaciones).exigirPermiso(usuario, org.getId(), OrgPermission.CREATE_WORKSPACES);
        assertThat(org.getStripeCustomerId()).isEqualTo("cus_nuevo");
        verify(organizacionesRepo).save(org);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadatos = ArgumentCaptor.forClass(Map.class);
        verify(stripe).crearCompra(eq("cus_nuevo"), tarifa("picale_licencia", 34900, true), metadatos.capture(),
                eq("https://picale.click/panel/facturacion?compra=ok&session_id={CHECKOUT_SESSION_ID}"),
                eq("https://picale.click/panel/facturacion?compra=cancelada"), any());
        assertThat(metadatos.getValue())
                .containsEntry("kind", "license")
                .containsEntry("organization_id", org.getId().toString())
                .containsEntry("user_id", usuario.getId().toString())
                .containsEntry("name", "Tacos El Güero")
                .containsEntry("giro", "Restaurante o cafetería")
                .containsEntry("objetivo", "VENDER_MAS")
                .doesNotContainKey("workspace_id")
                .doesNotContainKey("descripcion"); // vacía: no se manda
    }

    @Test
    @DisplayName("si la organización ya es cliente de Stripe se usa el mismo, no se crea otro")
    void reutilizaElCliente() {
        org.setStripeCustomerId("cus_viejo");
        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("Otro negocio");

        servicio.comprarEspacioNuevo(usuario, datos);

        verify(stripe, never()).crearCliente(any(), any(), any());
        verify(stripe).crearCompra(eq("cus_viejo"), any(), anyMap(), any(), any(), any());
    }

    @Test
    @DisplayName("sin permiso para crear espacios no se llega a Stripe")
    void sinPermiso() {
        doThrow(new IllegalStateException("sin permiso"))
                .when(organizaciones).exigirPermiso(any(), any(), any());
        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("X negocio");

        assertThatThrownBy(() -> servicio.comprarEspacioNuevo(usuario, datos)).hasMessageContaining("sin permiso");
        verify(stripe, never()).crearCompra(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sin nombre, o con los cobros apagados, o sin precio configurado, no se vende nada")
    void noSePuedeVender() {
        WorkspaceCreateRequest sinNombre = new WorkspaceCreateRequest();
        assertThatThrownBy(() -> servicio.comprarEspacioNuevo(usuario, sinNombre))
                .isInstanceOf(IllegalArgumentException.class);

        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("Con nombre");
        when(config.listaDeLicencia()).thenReturn(0);
        assertThatThrownBy(() -> servicio.comprarEspacioNuevo(usuario, datos)).hasMessageContaining("precio");

        when(config.habilitado()).thenReturn(false);
        assertThatThrownBy(() -> servicio.comprarEspacioNuevo(usuario, datos)).hasMessageContaining("todavía no");
        verify(stripe, never()).crearCompra(any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------ primer negocio y adicionales

    /** Los dos precios ya vienen en la preparación: 349 el primero y 249 cada adicional. */
    private void conDosPrecios() {
        // (nada que hacer: es solo para que las pruebas digan lo que suponen)
    }

    @Test
    @DisplayName("el primer negocio paga el precio del plan; el segundo, el adicional")
    void primeroYAdicional() {
        conDosPrecios();
        org.setStripeCustomerId("cus_1");
        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("Segundo negocio");

        // Sin nada pagado todavía: es el primero.
        servicio.comprarEspacioNuevo(usuario, datos);
        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_licencia", 34900, true), anyMap(), any(), any(), any());

        // Ya hay uno pagado y vigente: el siguiente es adicional.
        Workspace primero = workspace("Primero");
        License pagada = licencia(primero, LicenseStatus.ACTIVE, "sub_1");
        when(licencias.deLaOrganizacion(org.getId())).thenReturn(List.of(pagada));
        servicio.comprarEspacioNuevo(usuario, datos);
        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_licencia_adicional", 24900, true), anyMap(), any(), any(), any());
    }

    @Test
    @DisplayName("la compra lleva la etiqueta del precio: «base» para el primero y «extra» para un adicional")
    void etiquetaDelPrecio() {
        conDosPrecios();
        org.setStripeCustomerId("cus_1");
        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("Negocio");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadatos = ArgumentCaptor.forClass(Map.class);

        servicio.comprarEspacioNuevo(usuario, datos);
        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_licencia", 34900, true), metadatos.capture(), any(), any(), any());
        assertThat(metadatos.getValue()).containsEntry("tier", "base");

        License pagada = licencia(workspace("Primero"), LicenseStatus.ACTIVE, "sub_1");
        when(licencias.deLaOrganizacion(org.getId())).thenReturn(List.of(pagada));
        servicio.comprarEspacioNuevo(usuario, datos);
        ArgumentCaptor<Map<String, String>> segunda = ArgumentCaptor.forClass(Map.class);
        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_licencia_adicional", 24900, true), segunda.capture(), any(), any(), any());
        assertThat(segunda.getValue()).containsEntry("tier", "extra");
    }

    @Test
    @DisplayName("una prueba, una licencia terminada o el propio espacio no vuelven adicional al siguiente")
    void loQueNoCuentaComoPagado() {
        conDosPrecios();
        org.setStripeCustomerId("cus_1");
        Workspace enPrueba = workspace("En prueba");
        Workspace terminado = workspace("Terminado");
        License prueba = licencia(enPrueba, LicenseStatus.TRIALING, null);
        License fin = licencia(terminado, LicenseStatus.ENDED, "sub_viejo");
        when(licencias.deLaOrganizacion(org.getId())).thenReturn(List.of(prueba, fin));

        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("Nuevo negocio");
        servicio.comprarEspacioNuevo(usuario, datos);

        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_licencia", 34900, true), anyMap(), any(), any(), any());
    }

    @Test
    @DisplayName("contratar un espacio que ya existe: si es el único pagado, es el primero y no cuenta contra sí mismo")
    void contratarNoSeCuentaASiMismo() {
        conDosPrecios();
        org.setStripeCustomerId("cus_1");
        License propia = licencia(actual, LicenseStatus.ENDED, "sub_anterior");
        when(licencias.deLaOrganizacion(org.getId())).thenReturn(List.of(propia));

        servicio.contratarLicencia(usuario, actual.getId());

        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_licencia", 34900, true), anyMap(), any(), any(), any());
    }

    @Test
    @DisplayName("si el adicional cuesta lo mismo que el primero, cobra lo mismo (aunque sea otro producto del catálogo)")
    void mismoPrecioParaTodos() {
        when(config.listaDeAdicional()).thenReturn(34900);
        org.setStripeCustomerId("cus_1");
        License pagada = licencia(workspace("Primero"), LicenseStatus.ACTIVE, "sub_1");
        when(licencias.deLaOrganizacion(org.getId())).thenReturn(List.of(pagada));
        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("Segundo negocio");

        servicio.comprarEspacioNuevo(usuario, datos);

        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_licencia_adicional", 34900, true), anyMap(), any(), any(), any());
    }

    @Test
    @DisplayName("el monto sale de los ajustes del servidor, nunca de la petición, y en la moneda configurada")
    void montoDelServidor() {
        when(config.moneda()).thenReturn("mxn");
        org.setStripeCustomerId("cus_1");
        WorkspaceCreateRequest datos = new WorkspaceCreateRequest();
        datos.setName("Negocio");

        servicio.comprarEspacioNuevo(usuario, datos);

        ArgumentCaptor<StripeClient.Tarifa> t = ArgumentCaptor.forClass(StripeClient.Tarifa.class);
        verify(stripe).crearCompra(eq("cus_1"), t.capture(), anyMap(), any(), any(), any());
        assertThat(t.getValue().centavos()).isEqualTo(34900);
        assertThat(t.getValue().moneda()).isEqualTo("mxn");
        assertThat(t.getValue().mensual()).isTrue();
    }

    @Test
    @DisplayName("el resumen dice si el próximo espacio es adicional y con qué precio")
    void resumenConAdicional() {
        conDosPrecios();

        BillingSummaryResponse solo = servicio.resumen(usuario);
        assertThat(solo.nextLicenseIsExtra()).isFalse();
        assertThat(solo.licensePrice().amountMinor()).isEqualTo(34900);
        assertThat(solo.extraLicensePrice().amountMinor()).isEqualTo(24900);
        assertThat(solo.warnDays()).isEqualTo(5);
        assertThat(solo.taxIncluded()).isTrue();

        License pagada = licencia(workspace("Primero"), LicenseStatus.ACTIVE, "sub_1");
        when(licencias.deLaOrganizacion(org.getId())).thenReturn(List.of(pagada));
        assertThat(servicio.resumen(usuario).nextLicenseIsExtra()).isTrue();
    }

    // ------------------------------------------------------------ planes (público)

    @Test
    @DisplayName("los planes salen de los ajustes y solo trae paquetes encendidos y con precio de lista")
    void planes() {
        when(config.listaDeLicencia()).thenReturn(34900);
        when(config.listaDeAdicional()).thenReturn(24900);
        when(config.diasDePruebaAlRegistrarse()).thenReturn(14);
        when(config.creditosMensuales()).thenReturn(5);
        when(config.diasDeGracia()).thenReturn(7);
        when(config.impuestoIncluido()).thenReturn(true);
        CreditPack listo = CreditPack.builder().code("PACK_10").name("Arranque").credits(10).priceMinor(7900).active(true).build();
        CreditPack sinPrecio = CreditPack.builder().code("PACK_25").name("Constante").credits(25).active(true).build();
        CreditPack apagado = CreditPack.builder().code("PACK_50").name("A tope").credits(50).priceMinor(32900).active(false).build();
        when(paquetes.findAllByOrderBySortOrderAscCreditsAsc()).thenReturn(List.of(listo, sinPrecio, apagado));

        PlansResponse p = servicio.planes();

        assertThat(p.licenseMinor()).isEqualTo(34900);
        assertThat(p.extraMinor()).isEqualTo(24900);
        assertThat(p.trialDays()).isEqualTo(14);
        assertThat(p.monthlyCredits()).isEqualTo(5);
        assertThat(p.graceDays()).isEqualTo(7);
        assertThat(p.taxIncluded()).isTrue();

        // Con «más IVA» el cambio viaja igual: es un ajuste, no una decisión escrita en el código.
        when(config.impuestoIncluido()).thenReturn(false);
        assertThat(servicio.planes().taxIncluded()).isFalse();
        assertThat(p.currency()).isEqualTo("mxn");
        assertThat(p.packs()).extracting(PlansResponse.CreditPackPlan::code).containsExactly("PACK_10");
        assertThat(p.packs().get(0).priceMinor()).isEqualTo(7900);
    }

    @Test
    @DisplayName("los planes se ven aunque los cobros estén apagados: son solo lo que va a costar")
    void planesConCobrosApagados() {
        when(config.habilitado()).thenReturn(false);
        when(paquetes.findAllByOrderBySortOrderAscCreditsAsc()).thenReturn(List.of());

        assertThat(servicio.planes().packs()).isEmpty();
    }

    // ------------------------------------------------------------ contratar uno existente

    @Test
    @DisplayName("un espacio en prueba se puede contratar; el aviso de pago traerá su workspace_id")
    void contratarEnPrueba() {
        org.setStripeCustomerId("cus_1");
        licencia(actual, LicenseStatus.TRIALING, null);

        servicio.contratarLicencia(usuario, actual.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadatos = ArgumentCaptor.forClass(Map.class);
        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_licencia", 34900, true), metadatos.capture(), any(), any(), any());
        assertThat(metadatos.getValue()).containsEntry("workspace_id", actual.getId().toString());
        verify(organizaciones).exigirAdministrador(usuario, org.getId());
    }

    @Test
    @DisplayName("uno ya activo no se vuelve a comprar; uno con cobro pendiente se manda al portal")
    void yaTieneLicencia() {
        licencia(actual, LicenseStatus.ACTIVE, "sub_1");
        assertThatThrownBy(() -> servicio.contratarLicencia(usuario, actual.getId())).hasMessageContaining("ya tiene");

        licencia(actual, LicenseStatus.PAST_DUE, "sub_1");
        assertThatThrownBy(() -> servicio.contratarLicencia(usuario, actual.getId())).hasMessageContaining("portal");
    }

    @Test
    @DisplayName("un espacio de otra organización se responde como si no existiera")
    void espacioAjeno() {
        Organization otra = new Organization();
        otra.setId(UUID.randomUUID());
        Workspace ajeno = Workspace.builder().name("Ajeno").organization(otra).build();
        ajeno.setId(UUID.randomUUID());
        when(workspaces.findById(ajeno.getId())).thenReturn(Optional.of(ajeno));

        assertThatThrownBy(() -> servicio.contratarLicencia(usuario, ajeno.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> servicio.cancelarRenovacion(usuario, ajeno.getId(), true))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> servicio.comprarPaquete(usuario, "PACK_10", ajeno.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------ paquetes

    @Test
    @DisplayName("un paquete vendible se paga una vez, con su precio y a qué espacio va")
    void paquete() {
        org.setStripeCustomerId("cus_1");
        when(paquetes.findByCode("PACK_25")).thenReturn(Optional.of(
                CreditPack.builder().code("PACK_25").credits(25).priceMinor(17900).active(true).build()));

        servicio.comprarPaquete(usuario, "PACK_25", actual.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadatos = ArgumentCaptor.forClass(Map.class);
        verify(stripe).crearCompra(eq("cus_1"), tarifa("picale_paquete_pack_25", 17900, false), metadatos.capture(), any(), any(), any());
        assertThat(metadatos.getValue()).containsEntry("kind", "pack").containsEntry("pack_code", "PACK_25")
                .containsEntry("workspace_id", actual.getId().toString());
    }

    @Test
    @DisplayName("un paquete apagado, sin precio o inexistente no se vende")
    void paqueteNoDisponible() {
        when(paquetes.findByCode("APAGADO")).thenReturn(Optional.of(
                CreditPack.builder().code("APAGADO").credits(10).priceMinor(7900).active(false).build()));
        when(paquetes.findByCode("SIN_PRECIO")).thenReturn(Optional.of(
                CreditPack.builder().code("SIN_PRECIO").credits(10).active(true).build()));

        for (String codigo : List.of("APAGADO", "SIN_PRECIO", "NO_EXISTE")) {
            assertThatThrownBy(() -> servicio.comprarPaquete(usuario, codigo, actual.getId()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no está disponible");
        }
        verify(stripe, never()).crearCompra(any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------ cancelar

    @Test
    @DisplayName("cancelar apaga la renovación en Stripe y en la licencia; no hay reembolso ni corte")
    void cancelar() {
        License l = licencia(actual, LicenseStatus.ACTIVE, "sub_1");

        LicenseView vista = servicio.cancelarRenovacion(usuario, actual.getId(), true);

        verify(stripe).cancelarAlFinal("sub_1", true);
        assertThat(l.isCancelAtPeriodEnd()).isTrue();
        assertThat(vista.cancelAtPeriodEnd()).isTrue();
        assertThat(vista.usable()).as("se sigue usando hasta el fin de lo pagado").isTrue();
    }

    @Test
    @DisplayName("arrepentirse de cancelar la reactiva")
    void reanudar() {
        License l = licencia(actual, LicenseStatus.ACTIVE, "sub_1");
        l.setCancelAtPeriodEnd(true);

        servicio.cancelarRenovacion(usuario, actual.getId(), false);

        verify(stripe).cancelarAlFinal("sub_1", false);
        assertThat(l.isCancelAtPeriodEnd()).isFalse();
    }

    @Test
    @DisplayName("una prueba gratis no se cancela (termina sola) y una licencia terminada tampoco")
    void noSeCancela() {
        licencia(actual, LicenseStatus.TRIALING, null);
        assertThatThrownBy(() -> servicio.cancelarRenovacion(usuario, actual.getId(), true))
                .hasMessageContaining("prueba gratis");

        licencia(actual, LicenseStatus.ENDED, "sub_1");
        assertThatThrownBy(() -> servicio.cancelarRenovacion(usuario, actual.getId(), true))
                .hasMessageContaining("ya terminó");
        verify(stripe, never()).cancelarAlFinal(any(), anyBoolean());
    }

    // ------------------------------------------------------------ portal

    @Test
    @DisplayName("el portal se abre solo si ya hay un cliente de Stripe (después de la primera compra)")
    void portal() {
        assertThatThrownBy(() -> servicio.portal(usuario)).hasMessageContaining("primera compra");

        org.setStripeCustomerId("cus_1");
        when(stripe.crearPortal("cus_1", "https://picale.click/panel/facturacion"))
                .thenReturn("https://billing.stripe.com/p/x");

        assertThat(servicio.portal(usuario)).isEqualTo("https://billing.stripe.com/p/x");
        verify(organizaciones, atLeastOnce()).exigirAdministrador(usuario, org.getId());
    }
}
