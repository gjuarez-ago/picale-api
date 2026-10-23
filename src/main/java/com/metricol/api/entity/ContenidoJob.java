package com.metricol.api.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.*;

/** Cola interna global. Toda lectura pública exige workspace; nunca contiene credenciales. */
@Entity
@Table(name = "contenido_jobs", indexes = @Index(name = "ix_contenido_jobs_dispatch", columnList = "status,lease_until"))
@Getter @Setter @NoArgsConstructor
public class ContenidoJob {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID workspaceId;
    /** Un único trabajo activo por negocio, también entre instancias. NULL al terminar. */
    @Column(unique = true) private UUID activeWorkspace;
    @Column(nullable = false, length = 24) private String status;
    @Column(nullable = false, columnDefinition = "text") private String responseJson;
    /** Instrucciones y contexto confirmado, sin binarios; permanecen para auditoría del resultado. */
    @Column(nullable = false, columnDefinition = "text") private String requestJson;
    /** Snapshot comprimido, con referencias congeladas. Se elimina al terminar o quedar incierto. */
    @Column(length = 100663296) private byte[] prepared;
    @Column(nullable = false) private String creditReference;
    @Column(nullable = false) private boolean externalStarted;
    private UUID owner;
    private Instant leaseUntil;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
}
