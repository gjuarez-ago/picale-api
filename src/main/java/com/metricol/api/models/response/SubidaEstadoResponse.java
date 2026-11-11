package com.metricol.api.models.response;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * En qué quedó una subida en segundo plano, buscada por la clave que le puso el teléfono.
 *
 * <ul>
 * <li>{@code READY}: llegó y está confirmada; {@code media} es el archivo.</li>
 * <li>{@code PENDING}: apartada y sin terminar de llegar; la app puede seguir subiendo.</li>
 * <li>{@code NO_EXISTE}: nunca se pidió en este negocio, o se descartó por abandonada. Para
 * subirla hay que pedir permiso otra vez.</li>
 * </ul>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubidaEstadoResponse {

    private String clientUploadId;

    private UUID assetId;

    private String estado;

    private MediaAssetResponse media;
}
