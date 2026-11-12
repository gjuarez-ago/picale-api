package com.metricol.api.entity;

import java.util.UUID;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Cerrojo durable de admisión por negocio; no contiene credenciales ni archivos. */
@Entity
@Table(name = "upload_scopes")
@Getter
@NoArgsConstructor
public class UploadScope {
    @Id private UUID id;
    public UploadScope(UUID id) { this.id = id; }
}
