package com.metricol.api.service.agente.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.metricol.api.service.agente.PresupuestoDelAsistente;
import com.metricol.api.service.agente.TrabajoDeFondo;
import com.metricol.api.service.avisos.AvisosPush;

/**
 * El asistente planea solo y gasta solo cuando se lo piden: pregunta por las
 * fechas, pide fotos cada semana y avisa si la cuenta se va a callar (CMRG, oct 2026).
 */
class PlanDelAgenteTest {

    private AgenteService agente;
    private ListaDeTomas tomas;
    private AvisosPush avisos;
    private WorkspaceRepository workspaces;
    private PresupuestoDelAsistente presupuesto;
    private PlanDelAgente plan;
    private Workspace cmrg;

    @BeforeEach
    void preparar() {
        agente = mock(AgenteService.class);
        tomas = mock(ListaDeTomas.class);
        avisos = mock(AvisosPush.class);
        workspaces = mock(WorkspaceRepository.class);
        presupuesto = mock(PresupuestoDelAsistente.class);
        TrabajoDeFondo fondo = mock(TrabajoDeFondo.class);
        doAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return null;
        }).when(fondo).enEspacio(any(), any());
        plan = new PlanDelAgente(agente, tomas, avisos, workspaces, presupuesto, fondo);
        cmrg = Workspace.builder().name("CMRG").giro("Construcción").descripcion("Obras y remodelaciones")
                .agenteActivo(true).agenteDesde(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        cmrg.setId(UUID.randomUUID());
        cmrg.setPerfilRasgos(RasgoDelNegocio.guardar(EnumSet.of(RasgoDelNegocio.POR_PROYECTO, RasgoDelNegocio.COTIZA)));
        when(workspaces.findById(cmrg.getId())).thenReturn(Optional.of(cmrg));
        when(workspaces.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        when(agente.conRedes()).thenReturn(true);
        when(presupuesto.alcanza(any())).thenReturn(true);
        when(tomas.semana(any(), any())).thenReturn(List.of(new ListaDeTomas.Toma("La obra terminada", "De frente"),
                new ListaDeTomas.Toma("Antes y después", "Mismo ángulo"), new ListaDeTomas.Toma("El equipo", "Natural")));
    }

    @Test
    @DisplayName("antes del Día del Albañil pregunta si prepara la pieza, una sola vez, y no gasta nada")
    void ofreceLaFecha() {
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 4, 28, 10, 0));
        verify(avisos).avisarAlEquipo(eq(cmrg.getId()), contains("Santa Cruz"), contains("¿Te preparo"), any());
        assertThat(cmrg.getAgenteFechasHechas()).contains("ALBANIL-2026");
        verify(agente, never()).proponerSinFoto(any(), anyString(), anyString(), any(), any());

        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 4, 28, 12, 0));
        verify(avisos).avisarAlEquipo(eq(cmrg.getId()), contains("Santa Cruz"), anyString(), any());
        assertThat(plan.plan(cmrg, LocalDate.of(2026, 4, 28)).fechas())
                .filteredOn(f -> f.clave().equals("ALBANIL-2026")).singleElement()
                .extracting(PlanDelAgente.FechaVista::estado).isEqualTo("OFRECIDA");
    }

    @Test
    @DisplayName("si dice que sí, la diseña para ese día y avisa cuando está")
    void preparaLaFecha() {
        when(agente.proponerSinFoto(any(), anyString(), anyString(), any(), any()))
                .thenReturn(AgenteService.SinFoto.PROPUESTA);
        // La pieza se pide unos días antes; aquí "hoy" es el de la máquina, así que se busca una fecha que venga.
        PlanDelAgente.FechaVista proxima = plan.plan(cmrg, LocalDate.now()).fechas().stream()
                .filter(f -> f.dia().isAfter(LocalDate.now())).findFirst().orElseThrow();
        plan.prepararFecha(cmrg.getId(), proxima.clave());

        verify(agente).proponerSinFoto(eq(cmrg.getId()), contains(proxima.nombre()), anyString(), any(), any());
        verify(avisos).avisarAlEquipo(eq(cmrg.getId()), contains("Lista"), anyString(), any());
        assertThat(cmrg.getAgenteFechasPreparadas()).contains(proxima.clave());
        assertThatThrownBy(() -> plan.prepararFecha(cmrg.getId(), proxima.clave()))
                .hasMessageContaining("ya la preparé");
    }

    @Test
    @DisplayName("sin presupuesto del mes no prepara nada y lo dice")
    void sinPresupuesto() {
        when(presupuesto.alcanza(any())).thenReturn(false);
        assertThatThrownBy(() -> plan.prepararRelleno(cmrg.getId())).hasMessageContaining("presupuesto");
        verify(agente, never()).proponerSinFoto(any(), anyString(), anyString(), any(), any());
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
    @DisplayName("si la cuenta se va a quedar callada, avisa y ofrece una pieza de su oficio, sin gastar")
    void avisaElSilencio() {
        when(agente.callada(PlanDelAgente.DIAS_DE_SILENCIO)).thenReturn(true);
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 3, 23, 10, 0));
        verify(avisos).avisarAlEquipo(eq(cmrg.getId()), contains("callada"), contains("¿Te preparo"), any());
        verify(agente, never()).proponerSinFoto(any(), anyString(), anyString(), any(), any());
        assertThat(cmrg.getAgenteUltimoRelleno()).isNotNull();
    }

    @Test
    @DisplayName("con fotos por revisar o de noche, no molesta")
    void prudente() {
        when(agente.callada(PlanDelAgente.DIAS_DE_SILENCIO)).thenReturn(true);
        when(agente.conMaterialPendiente(any())).thenReturn(true);
        plan.trabajar(cmrg.getId(), LocalDateTime.of(2026, 4, 28, 23, 0));
        verify(avisos, never()).avisarAlEquipo(any(), anyString(), anyString(), any());
    }
}
