package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.metricol.api.enums.PostStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Solo el estado de una publicación, para que la app pueda vigilar sin costo.
 *
 * <p>Existe por una razón de carga concreta. La app necesita enterarse de
 * cuándo una publicación pasa de "en cola" a "publicada", y eso pasa en el
 * servidor unos segundos después, sin que nadie se lo diga. La forma obvia de
 * enterarse —volver a pedir {@code GET /posts} cada pocos segundos— es
 * justamente la que tumba el servicio: esa respuesta arrastra el texto, los
 * medios y los destinos de cada publicación, y cada destino carga su cuenta
 * de red por separado.
 *
 * <p>Esto es una consulta plana, sin uniones y sin colecciones: tres campos
 * por fila. La app la pide seguido y solo cuando algo cambia pide la lista
 * completa, una vez.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PostStatusResponse {
    private UUID id;
    private PostStatus status;
    private LocalDateTime publishedAt;
}
