package com.metricol.api.models.response;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * El permiso para subir un archivo directo a R2.
 *
 * <p>La app hace un PUT a {@code uploadUrl} con el cuerpo del archivo y la
 * cabecera {@code Content-Type} exactamente igual a {@code contentType} —va
 * firmada, así que otra cabecera hace que R2 rechace la subida— y después
 * llama a {@code POST /media/{assetId}/confirm}.
 *
 * <p>{@code url} es dónde quedará el archivo cuando llegue. Se manda ya para
 * que la pantalla pueda ir armando la publicación sin esperar, pero no vale
 * nada hasta que la subida se confirme.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaPresignResponse {

    /** El id de la fila que hay que confirmar después. */
    private UUID assetId;

    private String uploadUrl;

    /** Siempre PUT. Va explícito para que el cliente no lo dé por supuesto. */
    private String method;

    private String contentType;

    /** Segundos que vale la URL antes de vencer. */
    private long expiresInSeconds;

    private String url;
}
