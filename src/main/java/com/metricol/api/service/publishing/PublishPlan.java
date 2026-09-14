package com.metricol.api.service.publishing;

import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;

/**
 * Todo lo que hace falta para hablar con el proveedor, ya leído de la base.
 *
 * <p>Existe para que la llamada HTTP pueda hacerse SIN una transacción
 * abierta. Publicar tarda lo que tarde la red —medio minuto no es raro con un
 * video— y hacerlo dentro de la transacción que cargó el post significaría
 * tener una conexión de la base ocupada todo ese rato por cada worker. Con
 * ocho workers y un pool de cinco conexiones, el segundo lote se queda
 * esperando una conexión que nadie va a soltar, y el síntoma no es "el
 * proveedor va lento" sino "la aplicación entera se congeló".
 *
 * <p>Así el ciclo son tres tramos: una transacción corta que prepara, la
 * llamada larga sin transacción, y otra transacción corta que apunta el
 * resultado.
 */
public record PublishPlan(
        UUID postId,
        UUID workspaceId,
        String profile,
        String caption,
        List<String> mediaUrls,
        boolean video,

        /**
         * Qué clase de publicación es. Nunca nulo: viene de
         * {@code Post.formatoEfectivo()}, que deduce el de las publicaciones
         * anteriores a la columna.
         *
         * <p>Viaja hasta aquí porque es lo que decide qué se le dice al
         * proveedor. El mismo video de treinta segundos es un reel o una
         * historia según este campo, y sin él upload-post lo trata como reel
         * —que es lo que hacía toda la aplicación antes—.
         */
        PostFormat formato,

        List<Destino> destinos,

        /**
         * Cuando ya se sabe el final sin llamar a nadie —falta configuración,
         * ninguna red aguanta el video, la cuota está agotada— viene aquí el
         * resultado y no hay nada que enviar. Se devuelve así en vez de
         * lanzar porque no es un error: la transacción que preparó ya dejó
         * escrito el motivo en cada red, y eso hay que conservarlo.
         */
        PublishOutcome atajo) {

    public boolean hayQueEnviar() {
        return atajo == null && !destinos.isEmpty();
    }

    /**
     * Una red a la que sí se va a enviar, con su fila ya reservada.
     *
     * @param caption el texto de ESTA red, o {@code null} para usar el de la
     *                publicacion. Existe porque un mismo parrafo no vale para
     *                las cinco: en TikTok son 90 caracteres y en LinkedIn 3000, y
     *                publicar lo mismo en todas es lo que delata una cuenta
     *                automatizada.
     */
    public record Destino(UUID targetId, Platform platform, String caption) {
    }
}
