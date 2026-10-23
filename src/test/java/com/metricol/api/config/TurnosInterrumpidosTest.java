package com.metricol.api.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.repository.AssistantRunRepository;
import com.metricol.api.service.assistant.AssistantOrchestrator;

/** Un reinicio no deja el chat en "Preparando el turno…" para siempre. */
class TurnosInterrumpidosTest {

    @Test
    @DisplayName("al arrancar se cierran los turnos que el reinicio dejó a medias")
    void cierraLosInterrumpidos() {
        UUID uno = UUID.randomUUID();
        UUID sinTenant = UUID.randomUUID();
        AssistantRunRepository runs = mock(AssistantRunRepository.class);
        AssistantOrchestrator orquestador = mock(AssistantOrchestrator.class);
        when(runs.findInterrumpidos(any())).thenReturn(List.of(
                new Object[] {uno.toString(), "ws-1"},
                new Object[] {sinTenant.toString(), null}));
        when(orquestador.cerrarInterrumpido(uno)).thenReturn(true);

        new TurnosInterrumpidos(runs, orquestador).cerrar();

        verify(orquestador).cerrarInterrumpido(uno);
        verify(orquestador, never()).cerrarInterrumpido(sinTenant);
    }

    @Test
    @DisplayName("si la base no contesta, el arranque sigue")
    void sinBaseNoSeCae() {
        AssistantRunRepository runs = mock(AssistantRunRepository.class);
        AssistantOrchestrator orquestador = mock(AssistantOrchestrator.class);
        when(runs.findInterrumpidos(any())).thenThrow(new RuntimeException("sin base"));

        new TurnosInterrumpidos(runs, orquestador).cerrar();

        verify(orquestador, never()).cerrarInterrumpido(any());
    }
}
