package com.metricol.api.models.response;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Las partes de una subida: las que ya están en R2 (para retomar) o las que se acaban de firmar
 * (para subirlas). Cada lista va llena solo cuando corresponde.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultipartPartsResponse {

    private UUID assetId;

    private long sizeBytes;

    private long partSizeBytes;

    private int partCount;

    /** Las que ya llegaron, en orden, y cuánto suman. */
    private List<UploadedPart> uploaded;

    private long uploadedBytes;

    /** Permisos para subir: un PUT con el trozo como cuerpo, sin Content-Type. */
    private List<SignedPart> signed;

    /** Segundos que valen los permisos de {@code signed}. */
    private long expiresInSeconds;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UploadedPart {
        private int partNumber;
        private long sizeBytes;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SignedPart {
        private int partNumber;
        private String url;
    }
}
