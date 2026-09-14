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
}
