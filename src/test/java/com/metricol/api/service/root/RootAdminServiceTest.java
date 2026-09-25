package com.metricol.api.service.root;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.entity.License;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.enums.Role;
import com.metricol.api.models.request.root.AjusteDeCreditosRequest;
import com.metricol.api.models.request.root.LicenciaRequest;
import com.metricol.api.models.response.root.OrganizacionResumenResponse;
import com.metricol.api.repository.ImageCreditsRepository;
import com.metricol.api.repository.LicenseRepository;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.billing.BillingConfig;
import com.metricol.api.service.billing.CreditService;

/** La administración de la plataforma: qué ve de cada organización y qué hace al tocar licencias y créditos. */
class RootAdminServiceTest {

    private OrganizationRepository organizaciones;
    private OrganizationMemberRepository miembros;
    private WorkspaceRepository workspaces;
    private WorkspaceMemberRepository accesos;
    private LicenseRepository licencias;
    private ImageCreditsRepository saldos;
    private CreditService creditos;
    private BillingConfig config;
    private RootAdminService servicio;

    private User raiz;
    private Organization org;
    private Workspace espacio;

    @BeforeEach
    void preparar() {
        organizaciones = mock(OrganizationRepository.class);
        miembros = mock(OrganizationMemberRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        accesos = mock(WorkspaceMemberRepository.class);
        licencias = mock(LicenseRepository.class);
        saldos = mock(ImageCreditsRepository.class);
        creditos = mock(CreditService.class);
        config = mock(BillingConfig.class);
        servicio = new RootAdminService(organizaciones, miembros, workspaces, accesos, licencias, saldos, creditos,
                config);

        when(config.creditosMensuales()).thenReturn(5);
        when(licencias.save(any(License.class))).thenAnswer(i -> {
            License l = i.getArgument(0);
            if (l.getId() == null) {
                l.setId(UUID.randomUUID());
            }
            return l;
        });

        raiz = User.builder().id(UUID.randomUUID()).email("root@picale.test").name("Raíz").role(Role.ADMIN)
                .platformAdmin(true).build();

        org = Organization.builder().name("Cliente S.A.").build();
        org.setId(UUID.randomUUID());
        when(organizaciones.findById(org.getId())).thenReturn(Optional.of(org));
        when(organizaciones.findAll()).thenReturn(List.of(org));

        espacio = Workspace.builder().name("Tienda").organization(org).build();
        espacio.setId(UUID.randomUUID());
        when(workspaces.findById(espacio.getId())).thenReturn(Optional.of(espacio));
        when(workspaces.findAll()).thenReturn(List.of(espacio));
        when(workspaces.findDeLaOrganizacion(org.getId())).thenReturn(List.of(espacio));

        User dueno = User.builder().id(UUID.randomUUID()).email("dueno@cliente.test").name("Dueño").role(Role.ADMIN)
                .workspace(espacio).build();
        OrganizationMember membresia = OrganizationMember.de(dueno, org, OrgRole.OWNER);
        when(miembros.findTodosConUsuario()).thenReturn(List.of(membresia));
        when(miembros.findDeLaOrganizacion(org.getId())).thenReturn(List.of(membresia));
    }

    private License conLicencia(LicenseStatus estado, LocalDateTime hasta) {
        License l = License.builder().organizationId(org.getId()).workspaceId(espacio.getId()).status(estado).build();
        l.setId(UUID.randomUUID());
        switch (estado) {
            case TRIALING -> l.setTrialEndsAt(hasta);
            case ACTIVE -> l.setCurrentPeriodEnd(hasta);
            case PAST_DUE -> l.setGraceUntil(hasta);
            default -> {
            }
        }
        when(licencias.findByWorkspaceId(espacio.getId())).thenReturn(Optional.of(l));
        when(licencias.findAll()).thenReturn(List.of(l));
        when(licencias.findByOrganizationId(org.getId())).thenReturn(List.of(l));
        return l;
    }

    // ------------------------------------------------------------- listar

    @Test
    @DisplayName("sin licencia, la organización aparece como SIN_LICENCIA y con su dueño")
    void listaSinLicencia() {
        List<OrganizacionResumenResponse> lista = servicio.listar(null);

        assertThat(lista).hasSize(1);
        OrganizacionResumenResponse fila = lista.get(0);
        assertThat(fila.situacion()).isEqualTo(RootAdminService.SIN_LICENCIA);
        assertThat(fila.duenoEmail()).isEqualTo("dueno@cliente.test");
        assertThat(fila.espacios()).isEqualTo(1);
        assertThat(fila.personas()).isEqualTo(1);
    }

    @Test
    @DisplayName("en prueba vigente es EN_PRUEBA y dice hasta cuándo; vencida es VENCIDA")
    void situaciones() {
        LocalDateTime fin = LocalDateTime.now().plusDays(3);
        conLicencia(LicenseStatus.TRIALING, fin);
        OrganizacionResumenResponse enPrueba = servicio.listar("").get(0);
        assertThat(enPrueba.situacion()).isEqualTo(RootAdminService.EN_PRUEBA);
        assertThat(enPrueba.vigenteHasta()).isEqualTo(fin);

        conLicencia(LicenseStatus.ACTIVE, LocalDateTime.now().minusDays(5));
        assertThat(servicio.listar("").get(0).situacion()).isEqualTo(RootAdminService.VENCIDA);
    }

    @Test
    @DisplayName("la casa (sin límites) siempre es SIN_LIMITES, tenga la licencia como la tenga")
    void sinLimites() {
        org.setSinLimites(true);
        conLicencia(LicenseStatus.ENDED, null);

        OrganizacionResumenResponse fila = servicio.listar(null).get(0);
        assertThat(fila.situacion()).isEqualTo(RootAdminService.SIN_LIMITES);
        assertThat(fila.vigenteHasta()).isNull();
    }

    @Test
    @DisplayName("el filtro busca por organización, espacio y gente; lo que no coincide no sale")
    void filtro() {
        assertThat(servicio.listar("tienda")).hasSize(1);
        assertThat(servicio.listar("DUENO@cliente")).hasSize(1);
        assertThat(servicio.listar("otra cosa")).isEmpty();
    }

    // ------------------------------------------------------------ licencia

    @Test
    @DisplayName("un espacio sin licencia recibe una nueva con sus créditos mensuales")
    void licenciaNueva() {
        when(licencias.findByWorkspaceId(espacio.getId())).thenReturn(Optional.empty());
        LocalDateTime hasta = LocalDateTime.now().plusMonths(1);
        LicenciaRequest cambio = new LicenciaRequest();
        cambio.setStatus("active");
        cambio.setVigenteHasta(hasta);

        servicio.fijarLicencia(espacio.getId(), cambio, raiz);

        ArgumentCaptor<License> guardada = ArgumentCaptor.forClass(License.class);
        verify(licencias).save(guardada.capture());
        assertThat(guardada.getValue().getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(guardada.getValue().getCurrentPeriodEnd()).isEqualTo(hasta);
        assertThat(guardada.getValue().getOrganizationId()).isEqualTo(org.getId());
        verify(creditos).otorgarMensuales(eq(espacio.getId()), eq(5), eq(hasta), anyString());
    }

    @Test
    @DisplayName("alargar una licencia que ya existía no vuelve a regalar créditos")
    void alargarNoRegala() {
        conLicencia(LicenseStatus.TRIALING, LocalDateTime.now().plusDays(2));
        LicenciaRequest cambio = new LicenciaRequest();
        cambio.setStatus("TRIALING");
        cambio.setVigenteHasta(LocalDateTime.now().plusDays(30));

        servicio.fijarLicencia(espacio.getId(), cambio, raiz);

        verify(creditos, never()).otorgarMensuales(any(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("dejarla vigente restaura el espacio que había archivado el barrido")
    void restaura() {
        License l = conLicencia(LicenseStatus.ENDED, null);
        l.setArchivedBySweep(true);
        espacio.setArchivedAt(LocalDateTime.now().minusDays(1));
        LicenciaRequest cambio = new LicenciaRequest();
        cambio.setStatus("ACTIVE");
        cambio.setVigenteHasta(LocalDateTime.now().plusMonths(1));

        servicio.fijarLicencia(espacio.getId(), cambio, raiz);

        assertThat(espacio.archivado()).isFalse();
        assertThat(l.isArchivedBySweep()).isFalse();
        verify(workspaces).save(espacio);
    }

    @Test
    @DisplayName("terminarla archiva el espacio ya, sin esperar al barrido y sin borrar nada")
    void terminar() {
        conLicencia(LicenseStatus.ACTIVE, LocalDateTime.now().plusMonths(1));
        LicenciaRequest cambio = new LicenciaRequest();
        cambio.setStatus("ENDED");

        servicio.fijarLicencia(espacio.getId(), cambio, raiz);

        assertThat(espacio.archivado()).isTrue();
        verify(workspaces).save(espacio);
    }

    @Test
    @DisplayName("un estado desconocido o una vigencia que falta se rechazan antes de tocar nada")
    void validaciones() {
        conLicencia(LicenseStatus.ACTIVE, LocalDateTime.now().plusMonths(1));

        LicenciaRequest raro = new LicenciaRequest();
        raro.setStatus("VIP");
        raro.setVigenteHasta(LocalDateTime.now().plusDays(1));
        assertThatThrownBy(() -> servicio.fijarLicencia(espacio.getId(), raro, raiz))
                .isInstanceOf(IllegalArgumentException.class);

        LicenciaRequest sinFecha = new LicenciaRequest();
        sinFecha.setStatus("ACTIVE");
        assertThatThrownBy(() -> servicio.fijarLicencia(espacio.getId(), sinFecha, raiz))
                .isInstanceOf(IllegalArgumentException.class);

        verify(licencias, never()).save(any());
    }

    // ------------------------------------------------------------ créditos

    @Test
    @DisplayName("el ajuste de créditos pasa al servicio de créditos con una referencia propia del root y su motivo")
    void ajustaCreditos() {
        when(creditos.ajustar(eq(espacio.getId()), eq(10), anyString(), eq("cortesía"))).thenReturn(10);
        AjusteDeCreditosRequest ajuste = new AjusteDeCreditosRequest();
        ajuste.setDelta(10);
        ajuste.setMotivo("cortesía");

        servicio.ajustarCreditos(espacio.getId(), ajuste, raiz);

        ArgumentCaptor<String> referencia = ArgumentCaptor.forClass(String.class);
        // El motivo llega al movimiento, que es donde el historial lo lee.
        verify(creditos).ajustar(eq(espacio.getId()), eq(10), referencia.capture(), eq("cortesía"));
        assertThat(referencia.getValue()).startsWith("root:" + raiz.getId());
    }

    @Test
    @DisplayName("un ajuste de cero no es un ajuste")
    void ajusteCero() {
        AjusteDeCreditosRequest ajuste = new AjusteDeCreditosRequest();
        ajuste.setDelta(0);

        assertThatThrownBy(() -> servicio.ajustarCreditos(espacio.getId(), ajuste, raiz))
                .isInstanceOf(IllegalArgumentException.class);
        verify(creditos, never()).ajustar(any(), anyInt(), anyString(), any());
    }

    // --------------------------------------------------------- sin límites

    @Test
    @DisplayName("exentar de pago enciende la bandera de la organización y la devuelve al día")
    void exentar() {
        var detalle = servicio.cambiarSinLimites(org.getId(), true, raiz);

        assertThat(org.isSinLimites()).isTrue();
        assertThat(detalle.sinLimites()).isTrue();
        verify(organizaciones).save(org);
    }

    @Test
    @DisplayName("exentar restaura los espacios que el barrido había archivado; los archivados a mano no")
    void exentarRestaura() {
        License l = conLicencia(LicenseStatus.ENDED, null);
        l.setArchivedBySweep(true);
        espacio.setArchivedAt(LocalDateTime.now().minusDays(2));

        Workspace aMano = Workspace.builder().name("Cerrado").organization(org).build();
        aMano.setId(UUID.randomUUID());
        aMano.setArchivedAt(LocalDateTime.now().minusDays(9));
        when(workspaces.findById(aMano.getId())).thenReturn(Optional.of(aMano));

        servicio.cambiarSinLimites(org.getId(), true, raiz);

        assertThat(espacio.archivado()).isFalse();
        assertThat(l.isArchivedBySweep()).isFalse();
        assertThat(aMano.archivado()).isTrue();
        verify(workspaces, never()).save(aMano);
    }

    @Test
    @DisplayName("la referencia que manda el cliente viaja al servicio de créditos: un reintento no suma dos veces")
    void referenciaDelCliente() {
        when(creditos.ajustar(eq(espacio.getId()), eq(5), anyString(), any())).thenReturn(5);
        AjusteDeCreditosRequest ajuste = new AjusteDeCreditosRequest();
        ajuste.setDelta(5);
        ajuste.setReferencia("abc-123");

        servicio.ajustarCreditos(espacio.getId(), ajuste, raiz);
        servicio.ajustarCreditos(espacio.getId(), ajuste, raiz);

        // Sin motivo escrito viaja nulo, no una cadena vacía.
        verify(creditos, times(2)).ajustar(espacio.getId(), 5, "root:" + raiz.getId() + ":abc-123", null);
    }
}
