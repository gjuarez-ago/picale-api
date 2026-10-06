package com.metricol.api.service.agente.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.RasgoDelNegocio;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.agente.AgenteService;
import com.metricol.api.service.avisos.AvisosPush;

/** El asistente planea solo: fechas, fotos de la semana y que la cuenta no se calle (CMRG, oct 2026). */
class PlanDelAgenteTest {

    private AgenteService agente;
    private ListaDeTomas tomas;
    private AvisosPush avisos;
    private WorkspaceRepository workspaces;
    private PlanDelAgente plan;
    private Workspace cmrg;

    @BeforeEach
    void preparar() {
        agente = mock(AgenteService.class);
        tomas = mock(ListaDeTomas.class);
        avisos = mock(AvisosPush.class);
        workspaces = mock(WorkspaceRepository.class);
        plan = new PlanDelAgente(agente, tomas, avisos, workspaces);
        cmrg = Workspace.builder().name("CMRG").giro("Construcción").descripcion("Obras y remodelaciones")
                .agenteActivo(true).agenteDesde(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        cmrg.setId(UUID.randomUUID());
        cmrg.setPerfilRasgos(RasgoDelNegocio.guardar(EnumSet.of(RasgoDelNegocio.POR_PROYECTO, RasgoDelNegocio.COTIZA)));
        when(workspaces.findById(cmrg.getId())).thenReturn(Optional.of(cmrg));
        when(workspaces.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        when(agente.conRedes()).thenReturn(true);
        when(tomas.semana(any(), any())).thenReturn(List.of(new ListaDeTomas.Toma("La obra terminada", "De frente"),
                new ListaDeTomas.Toma("Antes y después", "Mismo ángulo"), new ListaDeTomas.Toma("El equipo", "Natural")));
    }

    @Test
    @DisplayName("cinco días antes del Día del Albañil, una constructora recibe su pieza para ese día, sola")
    void fechaDeSuOficio() {
        when(agente.proponerSinFoto(eq(cmrg.getId()), anyString(), anyString(), any(), any()))
                .thenReturn(AgenteService.SinFoto.PROPUESTA);
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 4, 28, 10, 0));

        verify(agente).proponerSinFoto(eq(cmrg.getId()), contains("Santa Cruz"), anyString(), any(),
                eq(LocalDateTime.of(2026, 5, 3, 10, 0)));
        assertThat(cmrg.getAgenteFechasHechas()).contains("ALBANIL-2026");

        // La siguiente vuelta no la vuelve a hacer.
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 4, 28, 12, 0));
        verify(agente).proponerSinFoto(eq(cmrg.getId()), contains("Santa Cruz"), anyString(), any(), any());
    }

    @Test
    @DisplayName("sin créditos para diseñarla, le pide la foto para esa fecha y no insiste")
    void fechaSinCreditos() {
        when(agente.proponerSinFoto(any(), anyString(), anyString(), any(), any()))
                .thenReturn(AgenteService.SinFoto.SIN_CREDITOS);
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 4, 28, 10, 0));
        verify(avisos).avisarAlEquipo(eq(cmrg.getId()), contains("Santa Cruz"), contains("Mándame una foto"), any());
        assertThat(cmrg.getAgenteFechasHechas()).contains("ALBANIL-2026");
    }

    @Test
    @DisplayName("cada siete días pide tres fotos al teléfono, y no antes")
    void fotosDeLaSemana() {
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 3, 23, 10, 0));
        verify(avisos).avisarAlEquipo(eq(cmrg.getId()), eq("Fotos para esta semana"), contains("1) La obra terminada"),
                any());
        assertThat(plan.plan(cmrg, LocalDate.of(2026, 3, 23)).tomas()).hasSize(3);

        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 3, 26, 10, 0));
        verify(tomas).semana(any(), any());
    }

    @Test
    @DisplayName("si la cuenta se iba a quedar callada y no hay fotos por revisar, prepara una pieza de su oficio")
    void sinSilencio() {
        when(agente.callada(PlanDelAgente.DIAS_DE_SILENCIO)).thenReturn(true);
        when(agente.proponerSinFoto(any(), anyString(), anyString(), any(), isNull()))
                .thenReturn(AgenteService.SinFoto.PROPUESTA);
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 3, 23, 10, 0));
        verify(agente).proponerSinFoto(eq(cmrg.getId()), contains("proyecto"), anyString(), any(), isNull());
        assertThat(cmrg.getAgenteUltimoRelleno()).isNotNull();
    }

    @Test
    @DisplayName("con fotos por revisar o de noche, no inventa nada")
    void prudente() {
        when(agente.callada(PlanDelAgente.DIAS_DE_SILENCIO)).thenReturn(true);
        when(agente.conMaterialPendiente(any())).thenReturn(true);
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 3, 23, 10, 0));
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 4, 28, 23, 0));
        verify(agente, never()).proponerSinFoto(any(), anyString(), anyString(), any(), any());
    }
}
