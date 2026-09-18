package com.metricol.api.service.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.service.publishing.ConfirmacionDelProveedor;
import com.metricol.api.service.publishing.PostPublishStore;

/**
 * Cómo va un envío, juntando las fuentes que haga falta para no quedarse con
 * una respuesta a medias.
 *
 * <p>Es UNA cadena de preguntas, y está aquí y no repartida, porque la usan
 * dos caminos que tienen que llegar a la misma conclusión con los mismos
 * datos: el worker que publica y vuelve a preguntar, y la reconciliación
 * manual de una publicación que quedó mal guardada. Si cada uno preguntara a
 * su manera, la misma publicación podría salir "publicada" por un lado y
 * "fallida" por el otro.
 *
 * <p>El orden: primero el estado del envío por su identificador, que es
 * exacto. El historial entra de respaldo en dos casos, y solo en esos:
 * cuando el proveedor no reconoce el identificador, y cuando da el envío por
 * cerrado (ahí ya nada va a cambiar, y si a alguna red le faltó su fila en el
 * estado, el historial la tiene). Mientras el envío sigue abierto no se mira
 * el historial: sus filas son de lo que ya cerró, y lo que falta simplemente
 * no ha pasado todavía.
 */
@Service
public class ConsultaDeEnvio {

    private static final Logger log = LoggerFactory.getLogger(ConsultaDeEnvio.class);

    private final ConfirmacionUploadPost confirmacion;

    public ConsultaDeEnvio(ConfirmacionUploadPost confirmacion) {
        this.confirmacion = confirmacion;
    }

    public ConfirmacionDelProveedor preguntar(PostPublishStore.EnvioEnCurso envio) {
        if (envio.requestId() == null) {
            log.warn("El envio de upload-post no trajo identificador; se confirma por historial.");
            return confirmacion.porHistorial(envio.profile(), envio.iniciadoEn());
        }

        ConfirmacionDelProveedor porEstado = confirmacion.porEnvio(envio.requestId());

        if (porEstado.sinRastro()) {
            // Preguntar otra vez por ese identificador no va a servir. Lo que
            // queda es buscar por perfil y hora, que es menos preciso pero
            // mejor que inventarse el resultado.
            return porEstado.completadaCon(
                    confirmacion.porHistorial(envio.profile(), envio.iniciadoEn()));
        }

        if (porEstado.terminado()) {
            return porEstado.completadaCon(confirmacion.porHistorial(envio.requestId()));
        }

        return porEstado;
    }
}
