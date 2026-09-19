package com.metricol.api.service.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.License;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.repository.LicenseRepository;
import com.metricol.api.repository.WorkspaceRepository;

/** Las licencias: cuándo se puede usar un workspace y qué hace el barrido con los que no. */
class LicenseServiceTest {

    private LicenseRepository licencias;
    private WorkspaceRepository workspaces;
    private CreditService creditos;
    private BillingConfig config;
    private LicenseService servicio;

    private final List<License> guardadas = new ArrayList<>();
    private Organization org;

    @BeforeEach
    void preparar() {
        licencias = mock(LicenseRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        creditos = mock(CreditService.class);
        config = mock(BillingConfig.class);
        servicio = new LicenseService(licencias, workspaces, creditos, config);

        when(config.habilitado()).thenReturn(true);
        when(config.diasDePruebaAlRegistrarse()).thenReturn(14);
        when(config.diasDePruebaDeLosExistentes()).thenReturn(30);
        when(config.creditosMensuales()).thenReturn(5);

        org = new Organization();
        org.setId(UUID.randomUUID());

        // Un repositorio de mentira que recuerda lo que se guarda.
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
        when(licencias.findAll()).thenAnswer(i -> new ArrayList<>(guardadas));
        when(licencias.findByWorkspaceId(any())).thenAnswer(i -> guardadas.stream()
                .filter(l -> l.getWorkspaceId().equals(i.getArgument(0))).findFirst());
        when(licencias.workspacesSinLicencia()).thenReturn(List.of());
    }

    private Workspace workspace() {
        Workspace w = Workspace.builder().name("Cliente").organization(org).build();
        w.setId(UUID.randomUUID());
        when(workspaces.findById(w.getId())).thenReturn(Optional.of(w));
        return w;
    }

    private License licencia(Workspace w, LicenseStatus estado, LocalDateTime trial, LocalDateTime periodo,
            LocalDateTime gracia) {
        License l = License.builder().organizationId(org.getId()).workspaceId(w.getId()).status(estado)
                .trialEndsAt(trial).currentPeriodEnd(periodo).graceUntil(gracia).build();
        licencias.save(l);
        return l;
    }

    // ------------------------------------------------------------ usable()

    @Test
    @DisplayName("prueba: se usa hasta que termina")
    void usablePrueba() {
        LocalDateTime ahora = LocalDateTime.now();
        assertThat(License.builder().status(LicenseStatus.TRIALING).trialEndsAt(ahora.plusDays(1)).build()
                .usable(ahora)).isTrue();
        assertThat(License.builder().status(LicenseStatus.TRIALING).trialEndsAt(ahora.minusMinutes(1)).build()
                .usable(ahora)).isFalse();
        assertThat(License.builder().status(LicenseStatus.TRIALING).build().usable(ahora)).isFalse();
    }

    @Test
    @DisplayName("pagada: se usa hasta el fin del periodo, con un día de holgura por si el aviso de Stripe se retrasa")
    void usablePagada() {
        LocalDateTime ahora = LocalDateTime.now();
        assertThat(License.builder().status(LicenseStatus.ACTIVE).currentPeriodEnd(ahora.plusDays(3)).build()
                .usable(ahora)).isTrue();
        // Terminó hace unas horas y la renovación aún no llega: no se cierra.
        assertThat(License.builder().status(LicenseStatus.ACTIVE).currentPeriodEnd(ahora.minusHours(6)).build()
                .usable(ahora)).isTrue();
        assertThat(License.builder().status(LicenseStatus.ACTIVE).currentPeriodEnd(ahora.minusDays(2)).build()
                .usable(ahora)).isFalse();
    }

    @Test
    @DisplayName("cobro fallido: se usa durante la gracia, y terminada la gracia no")
    void usableConGracia() {
        LocalDateTime ahora = LocalDateTime.now();
        assertThat(License.builder().status(LicenseStatus.PAST_DUE).graceUntil(ahora.plusDays(2)).build()
                .usable(ahora)).isTrue();
        assertThat(License.builder().status(LicenseStatus.PAST_DUE).graceUntil(ahora.minusMinutes(1)).build()
                .usable(ahora)).isFalse();
        assertThat(License.builder().status(LicenseStatus.ENDED).build().usable(ahora)).isFalse();
    }

    // ------------------------------------------------------------ alta

    @Test
    @DisplayName("al registrarse, con los cobros encendidos, empieza la prueba con sus créditos")
    void pruebaAlRegistrarse() {
        Workspace w = workspace();

        servicio.iniciarPruebaAlRegistrarse(w);

        assertThat(guardadas).hasSize(1);
        License l = guardadas.get(0);
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.TRIALING);
        assertThat(l.getTrialEndsAt()).isBetween(LocalDateTime.now().plusDays(13), LocalDateTime.now().plusDays(15));
        verify(creditos).otorgarMensuales(eq(w.getId()), eq(5), eq(l.getTrialEndsAt()), anyString());
    }

    @Test
    @DisplayName("con los cobros apagados registrarse no crea nada")
    void apagadoNoCreaNada() {
        when(config.habilitado()).thenReturn(false);

        servicio.iniciarPruebaAlRegistrarse(workspace());

        assertThat(guardadas).isEmpty();
        verify(creditos, never()).otorgarMensuales(any(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("crear la prueba dos veces devuelve la misma licencia")
    void pruebaIdempotente() {
        Workspace w = workspace();

        License primera = servicio.crearPrueba(w, 14);
        License segunda = servicio.crearPrueba(w, 14);

        assertThat(segunda).isSameAs(primera);
        assertThat(guardadas).hasSize(1);
    }

    // ------------------------------------------------------------ barrido

    @Test
    @DisplayName("apagados los cobros, el barrido no toca ni un workspace")
    void barridoApagado() {
        when(config.habilitado()).thenReturn(false);
        Workspace w = workspace();
        licencia(w, LicenseStatus.TRIALING, LocalDateTime.now().minusDays(5), null, null);

        assertThat(servicio.procesarVencimientos()).isZero();
        assertThat(w.archivado()).isFalse();
    }

    @Test
    @DisplayName("los workspaces de antes de encender los cobros reciben su prueba gratis, no un corte")
    void existentesRecibenPrueba() {
        Workspace w = workspace();
        when(licencias.workspacesSinLicencia()).thenReturn(List.of(w));

        servicio.procesarVencimientos();

        assertThat(guardadas).hasSize(1);
        License l = guardadas.get(0);
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.TRIALING);
        assertThat(l.getTrialEndsAt()).isAfter(LocalDateTime.now().plusDays(29));
        assertThat(w.archivado()).isFalse();
    }

    @Test
    @DisplayName("una prueba vencida termina la licencia y ARCHIVA el workspace: no se borra nada")
    void vencidaArchiva() {
        Workspace w = workspace();
        License l = licencia(w, LicenseStatus.TRIALING, LocalDateTime.now().minusMinutes(1), null, null);

        assertThat(servicio.procesarVencimientos()).isEqualTo(1);

        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ENDED);
        assertThat(l.isArchivedBySweep()).isTrue();
        assertThat(w.archivado()).isTrue();
    }

    @Test
    @DisplayName("una licencia vigente no se toca")
    void vigenteNoSeToca() {
        Workspace w = workspace();
        License l = licencia(w, LicenseStatus.ACTIVE, null, LocalDateTime.now().plusDays(10), null);

        assertThat(servicio.procesarVencimientos()).isZero();

        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(w.archivado()).isFalse();
    }

    @Test
    @DisplayName("al pagar, el workspace que archivó el barrido se restaura solo")
    void pagarRestaura() {
        Workspace w = workspace();
        License l = licencia(w, LicenseStatus.TRIALING, LocalDateTime.now().minusMinutes(1), null, null);
        servicio.procesarVencimientos();
        assertThat(w.archivado()).isTrue();

        // Stripe avisa que pagó: vuelve a ACTIVE con periodo por delante.
        l.setStatus(LicenseStatus.ACTIVE);
        l.setCurrentPeriodEnd(LocalDateTime.now().plusDays(30));
        servicio.procesarVencimientos();

        assertThat(w.archivado()).isFalse();
        assertThat(l.isArchivedBySweep()).isFalse();
    }

    @Test
    @DisplayName("un workspace que la persona archivó a propósito NO se restaura al pagar")
    void archivadoAPropositoSeQueda() {
        Workspace w = workspace();
        w.setArchivedAt(LocalDateTime.now().minusDays(3)); // lo archivó alguien
        License l = licencia(w, LicenseStatus.ACTIVE, null, LocalDateTime.now().plusDays(10), null);

        servicio.procesarVencimientos();

        assertThat(w.archivado()).isTrue();
        assertThat(l.isArchivedBySweep()).isFalse();
    }

    @Test
    @DisplayName("un workspace ya archivado a mano no se marca como archivado por el barrido al vencer")
    void yaArchivadoNoSeMarca() {
        Workspace w = workspace();
        w.setArchivedAt(LocalDateTime.now().minusDays(3));
        License l = licencia(w, LicenseStatus.TRIALING, LocalDateTime.now().minusDays(1), null, null);

        servicio.procesarVencimientos();

        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ENDED);
        // No lo archivó el barrido: pagar después no lo va a "restaurar".
        assertThat(l.isArchivedBySweep()).isFalse();
    }

    @Test
    @DisplayName("con cobro fallido se sigue usando en la gracia y después se archiva")
    void graciaYDespues() {
        Workspace w = workspace();
        License l = licencia(w, LicenseStatus.PAST_DUE, null, LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(3));

        servicio.procesarVencimientos();
        assertThat(w.archivado()).as("dentro de la gracia").isFalse();

        l.setGraceUntil(LocalDateTime.now().minusMinutes(1));
        servicio.procesarVencimientos();
        assertThat(w.archivado()).as("terminada la gracia").isTrue();
        assertThat(l.getStatus()).isEqualTo(LicenseStatus.ENDED);
    }
}
