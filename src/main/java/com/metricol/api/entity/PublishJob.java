package com.metricol.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.metricol.api.enums.PublishJobStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Un encargo de publicación esperando su turno.
 *
 * <p>Es la cola, y vive en la base de datos. Podría haber sido una
 * {@code BlockingQueue} en memoria —menos piezas, menos SQL— pero entonces
 * cada despliegue habría tirado a la basura todo lo pendiente, y una
 * publicación perdida sin rastro es exactamente el fallo que nadie perdona.
 * Aquí, si el proceso se cae con un trabajo en la mano, otro worker lo
 * recoge en cuanto pasa el plazo de abandono.
 *
 * <p><b>No lleva TenantId</b>, y es a propósito: quien lee esta tabla es el
 * despachador, que corre sin usuario autenticado y tiene que ver los
 * trabajos de todos los workspaces a la vez. El workspace va como columna
 * normal, y es lo que el worker usa para meterse en el tenant correcto antes
 * de tocar el post (ver TenantIdentifierResolver.comoTenant).
 */
@Entity
// Los índices van por nombre de COLUMNA, no de propiedad: "run_at" y no
// "runAt". Es el índice que sostiene la consulta del despachador, que corre
// cada dos segundos contra toda la tabla.
@Table(name = "publish_jobs", indexes = {
        @Index(name = "idx_publish_jobs_due", columnList = "status,run_at"),
        @Index(name = "idx_publish_jobs_post", columnList = "post_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublishJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** El workspace dueño de la publicación. Ver la nota de la clase. */
    @Column(nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private UUID postId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PublishJobStatus status;

    /**
     * Cuándo puede tomarse. Es lo que hace que la misma tabla sirva para
     * "sal ya" (ahora), para una programada (su fecha) y para un reintento
     * con espera creciente: solo cambia este campo.
     */
    @Column(nullable = false)
    private LocalDateTime runAt;

    /**
     * Menor va antes. Una publicación inmediata gana a una programada que
     * llegó tarde: alguien está mirando la pantalla esperando la primera.
     */
    @Builder.Default
    private int priority = 100;

    @Builder.Default
    private int attempts = 0;

    private int maxAttempts;

    /** Qué instancia lo tiene; sirve para diagnosticar, no para coordinar. */
    private String lockedBy;

    /** Desde cuándo lo tiene. Un RUNNING viejo es un worker que se murió. */
    private LocalDateTime lockedAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(length = 500)
    private String lastError;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
