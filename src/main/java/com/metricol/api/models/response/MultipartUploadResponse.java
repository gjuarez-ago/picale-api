package com.metricol.api.models.response;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Una subida por partes recién abierta.
 *
 * <p>El cliente parte el archivo en {@code partCount} trozos de {@code partSizeBytes} (el último puede
 * ser menor), pide permiso para subirlos a {@code POST /media/{assetId}/multipart/parts}, los sube con
 * un PUT cada uno y cierra con {@code POST /media/{assetId}/multipart/complete}. Si se corta, pregunta
 * a {@code GET /media/{assetId}/multipart/parts} cuáles llegaron y sigue con las demás.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultipartUploadResponse {

    private UUID assetId;

    /** Lo que se declaró, en bytes. Retomar exige el mismo archivo. */
    private long sizeBytes;

    private long partSizeBytes;

    private int partCount;

    private String contentType;

    /** Dónde quedará el archivo. No vale nada hasta que se complete. */
    private String url;
}
