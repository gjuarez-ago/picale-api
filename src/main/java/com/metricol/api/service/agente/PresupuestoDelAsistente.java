package com.metricol.api.service.agente;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.repository.AiUsageRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.billing.CreditService;

/**
 * Cuántos créditos puede usar el asistente al mes en lo que cuesta: mejorar
 * fotos con IA y diseñar. Lo pone el dueño; mientras no lo toque, vale
 * {@code app.agente.presupuesto-default}.
 *
 * <p>Se mide en créditos aunque los cobros estén apagados: así el tope también
 * protege el gasto de la plataforma. Lo usado sale de lo que de verdad se hizo
 * en el mes (mejoras pedidas y diseños del asistente), no de un contador
 * aparte que se pueda desincronizar.
 */
@Component
public class PresupuestoDelAsistente {

    private final AiUsageRepository usos;
    private final PostRepository posts;
    private final WorkspaceRepository workspaces;
    private final CreditService creditos;

    @Value("${app.agente.presupuesto-default:50}")
    private int porDefecto;

    public PresupuestoDelAsistente(AiUsageRepository usos, PostRepository posts, WorkspaceRepository workspaces,
            CreditService creditos) {
        this.usos = usos;
        this.posts = posts;
        this.workspaces = workspaces;
        this.creditos = creditos;
    }

    /**
     * @param mensual   créditos al mes que puede usar
     * @param usados    créditos usados este mes
     * @param porAccion lo que cuesta una mejora o un diseño
     */
    public record Estado(int mensual, int usados, int porAccion) {

        // Sin la anotación Jackson solo manda los componentes del record: la
        // pantalla decía "me alcanza para  mejoras" sin el número.
        @com.fasterxml.jackson.annotation.JsonProperty
        public int restantes() {
            return Math.max(0, mensual - usados);
        }

        /** Cuántas mejoras o diseños caben todavía este mes. */
        @com.fasterxml.jackson.annotation.JsonProperty
        public int acciones() {
            return porAccion <= 0 ? 0 : restantes() / porAccion;
        }
    }

    public Estado estado(UUID workspaceId) {
        Workspace w = workspaces.findById(workspaceId).orElse(null);
        int mensual = w == null || w.getAgentePresupuesto() == null ? porDefecto : w.getAgentePresupuesto();
        int porAccion = Math.max(1, creditos.porGeneracion());
        LocalDateTime inicio = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long mejoras = usos.countByWorkspaceIdAndOperacionAndCreatedAtBetween(workspaceId, AiOperacion.AGENTE_MEJORAR,
                inicio, inicio.plusMonths(1));
        long disenos = posts.disenosDelAgenteDesde(inicio);
        return new Estado(mensual, (int) ((mejoras + disenos) * porAccion), porAccion);
    }

    /** Si cabe una mejora o un diseño más este mes. */
    public boolean alcanza(UUID workspaceId) {
        return estado(workspaceId).acciones() >= 1;
    }

    /** El dueño cambia su presupuesto (créditos al mes, de 0 a 1000). */
    public Estado guardar(UUID workspaceId, int mensual) {
        Workspace w = workspaces.findById(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Espacio no encontrado."));
        w.setAgentePresupuesto(Math.max(0, Math.min(1000, mensual)));
        workspaces.save(w);
        return estado(workspaceId);
    }
}
