package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAssetResponse {
    private UUID id;
    private String fileName;
    private String url;

    /** Fotograma del video. Nulo en imagenes y mientras se genera. */
    private String thumbnailUrl;
    private MediaType type;

    /**
     * READY es lo normal. PENDING solo lo ve quien acaba de pedir una firma y
     * todavía no ha confirmado la subida: la galería no devuelve pendientes.
     */
    private MediaAssetStatus status;

    private Long sizeBytes;
    private String sizeLabel;
    private LocalDateTime createdAt;

    /** Cuándo se archivó, o nulo si sigue a la vista en Contenido. */
    private LocalDateTime archivedAt;
}
