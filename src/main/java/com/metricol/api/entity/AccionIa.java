package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una acción que hizo una IA conectada (Claude, ChatGPT…) a través del
 * servidor MCP: qué hizo, en qué cuenta, en nombre de quién y con qué cliente.
 *
 * <p>Existe para la confianza: una agencia que conecta su IA a las cuentas
 * de sus clientes tiene que poder contestar "¿quién programó esto?" y "¿qué
 * hizo Claude esta semana en la cuenta de Tacos El Güero?". La app y el panel
 * no se anotan aquí: lo que hace una persona a mano ya tiene su rastro en las
 * propias publicaciones; lo que hace una IA en su nombre, no lo tenía.
 *
 * <p>Sin {@code @TenantId}, como {@code AiUsage}: el workspace va en una
 * columna normal porque la acción puede caer en OTRA cuenta de la persona
 * (aprobar desde la bandeja de todas), y porque quien administra la agencia
 * querrá verlas todas juntas algún día.
 */
@Entity
@Table(name = "acciones_ia", indexes = {
        @Index(name = "ix_acciones_ia_ws_fecha", columnList = "workspace_id, created_at"),
        @Index(name = "ix_acciones_ia_entidad", columnList = "entidad_id") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccionIa {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** La cuenta sobre la que se actuó. */
    @Column(nullable = false)
    private UUID workspaceId;

    /** La persona en cuyo nombre actuó la IA (su sesión de Pícale). */
    private UUID userId;

    @Column(length = 160)
    private String userEmail;

    /** De dónde vino: {@code mcp} hoy; la cabecera {@code X-Picale-Origen}. */
    @Column(nullable = false, length = 20)
    private String origen;

    /** El cliente de IA, como se presentó al conectarse: Claude, ChatGPT, Claude Code… */
    @Column(length = 80)
    private String cliente;

    /** Qué hizo, en código. Ver {@code AccionesIa}. */
    @Column(nullable = false, length = 40)
    private String accion;

    @Column(nullable = false, length = 8)
    private String metodo;

    @Column(nullable = false, length = 300)
    private String ruta;

    /** La publicación, archivo o propuesta sobre la que actuó, si la hay. */
    @Column(length = 40)
    private String entidadId;

    /** Un resumen legible: el texto de la publicación, el nombre del archivo, la fecha programada… */
    @Column(length = 500)
    private String detalle;

    /** El estado HTTP con el que la API contestó. Solo se anotan los 2xx. */
    @Column(nullable = false)
    private int estado;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
