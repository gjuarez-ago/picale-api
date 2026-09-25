package com.metricol.api.models.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Lo que pasó al eliminar (o liberar) un archivo de Contenido: cuánto espacio
 * volvió y cuántas publicaciones se fueron con él. Es lo que la pantalla dice
 * después, en vez de un "listo" a secas.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaEliminadoResponse {

    /** Publicaciones que se eliminaron —para la persona— porque usaban el archivo. */
    private int publicacionesEliminadas;

    private long bytesLiberados;

    /** Lo mismo, legible: "12 MB". */
    private String liberadoLabel;
}
