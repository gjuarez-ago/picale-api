package com.metricol.api.models.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * El espacio de un workspace, listo para pintar una barra.
 *
 * <p>Trae los bytes Y su etiqueta ya formateada. Duplicar el dato parece de
 * mal gusto, pero el formato de "780 MB" tiene que decidirlo un solo sitio:
 * con cada cliente formateando por su cuenta, la app y la web acabarían
 * enseñando cifras distintas para el mismo número.
 *
 * <p>{@code limitBytes} en {@code null} es un entorno sin cuota: entonces no
 * hay barra que pintar ni porcentaje que enseñar.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageUsageResponse {
    private long usedBytes;
    private Long limitBytes;
    private Long availableBytes;
    private Integer percentUsed;
    private String usedLabel;
    private String limitLabel;

    /** Topes que la app necesita conocer antes de dejar elegir archivos. */
    private long maxFileBytes;
    private int maxImagesPerPost;

    /** Carga masiva: por tanda, videos por tanda, y lo que lleva y le queda hoy (0 = sin tope). */
    private int maxPorTanda;
    private int maxVideosPorTanda;
    private int maxSubidasPorDia;
    private long subidasHoy;

    /**
     * Desde qué tamaño este espacio sube por partes ({@code POST /media/multipart}); nulo si no le
     * toca, y entonces todo va por el PUT de siempre. Lo decide el servidor para poder apagarlo sin
     * desplegar la web ni la app.
     */
    private Long multipartFromBytes;

    /**
     * Versión de la subida en segundo plano que entiende este servidor: 1 = acepta
     * {@code clientUploadId}, firma por horas, guarda la subida un día y contesta
     * {@code GET /media/subidas/{clientUploadId}}. Ausente en un servidor viejo: la app nueva
     * entonces sube como antes, con la app abierta.
     */
    private int subidaEnFondo;
    /** Contrato de sesión API; no habilita el motor móvil multipart. */
    private int multipartSessionVersion;
    private boolean multipartMobileReady;
}
