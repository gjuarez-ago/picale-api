package com.metricol.api.models.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * Lo que la app dice antes de subir un archivo directo a R2.
 *
 * <p>El tamaño viene aquí porque hay que decidir ANTES si cabe en la cuota:
 * el servidor ya no ve los bytes, así que si no se preguntara, el aviso de
 * "no te queda espacio" llegaría cuando el archivo ya estuviera subido y
 * pagado.
 *
 * <p>Es una promesa del cliente, no un hecho. Al confirmar se comprueba el
 * tamaño real contra R2 y, si no cuadra, el archivo se borra. Eso es lo que
 * hace que la cuota no la decida el teléfono.
 */
@Getter
@Setter
public class MediaPresignRequest {

    @NotBlank
    private String fileName;

    /**
     * El content-type con el que se va a subir. Se firma junto con la URL, así
     * que el PUT tiene que mandar exactamente este: es lo que impide declarar
     * una foto y subir otra cosa.
     */
    @NotBlank
    private String contentType;

    @Positive
    private long sizeBytes;
}
