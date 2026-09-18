package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;
import com.metricol.api.service.publishing.ConfirmacionDelProveedor;
import com.metricol.api.service.publishing.PostPublishStore;
import com.metricol.api.service.publishing.ResultadoDeRed;

/**
 * En qué orden se le pregunta al proveedor, y cuándo entra el historial.
 *
 * <p>El historial es de respaldo y solo en dos casos: cuando el proveedor no
 * reconoce el envío, y cuando lo da por cerrado. Mientras el envío sigue
 * abierto NO se mira: sus filas son de lo que ya cerró, y lo que falta
 * simplemente no ha pasado todavía. Mirarlo ahí solo gastaría una llamada por
 * consulta contra una llave compartida.
 */
class ConsultaDeEnvioTest {

    private final ConfirmacionUploadPost confirmacion = mock(ConfirmacionUploadPost.class);
    private final ConsultaDeEnvio consulta = new ConsultaDeEnvio(confirmacion);

    private static final LocalDateTime HORA = LocalDateTime.of(2026, 9, 17, 19, 21);
    private static final PostPublishStore.EnvioEnCurso ENVIO =
            new PostPublishStore.EnvioEnCurso("req-1", HORA, "perfil");

    private ConfirmacionDelProveedor con(boolean terminado, Platform red, String id) {
        return new ConfirmacionDelProveedor(terminado, false,
                Map.of(red, ResultadoDeRed.publicada(id, "https://" + id)));
    }

    @Test
    void conElEnvioAbiertoSoloSePreguntaElEstado() {
        when(confirmacion.porEnvio("req-1")).thenReturn(con(false, Platform.FACEBOOK, "fb"));

        ConfirmacionDelProveedor resultado = consulta.preguntar(ENVIO);

        assertThat(resultado.porRed()).containsOnlyKeys(Platform.FACEBOOK);
        verify(confirmacion, never()).porHistorial(anyString());
        verify(confirmacion, never()).porHistorial(anyString(), any());
    }

    @Test
    void conElEnvioCerradoElHistorialRellenaLoQueFalte() {
        // El caso que motivó todo: el estado dice "completed" pero a una red le
        // falta su fila. El historial la tiene.
        when(confirmacion.porEnvio("req-1")).thenReturn(con(true, Platform.FACEBOOK, "fb"));
        when(confirmacion.porHistorial("req-1")).thenReturn(con(false, Platform.TIKTOK, "tt"));

        ConfirmacionDelProveedor resultado = consulta.preguntar(ENVIO);

        assertThat(resultado.terminado()).isTrue();
        assertThat(resultado.porRed()).containsOnlyKeys(Platform.FACEBOOK, Platform.TIKTOK);
    }

    @Test
    void siElProveedorNoReconoceElEnvioSeBuscaPorPerfilYHora() {
        when(confirmacion.porEnvio("req-1")).thenReturn(ConfirmacionDelProveedor.desconocida());
        when(confirmacion.porHistorial("perfil", HORA)).thenReturn(con(false, Platform.TIKTOK, "tt"));

        ConfirmacionDelProveedor resultado = consulta.preguntar(ENVIO);

        assertThat(resultado.porRed()).containsOnlyKeys(Platform.TIKTOK);
        verify(confirmacion, never()).porHistorial("req-1");
    }

    @Test
    void sinIdentificadorSeVaDirectoAlHistorialPorPerfilYHora() {
        PostPublishStore.EnvioEnCurso sinId = new PostPublishStore.EnvioEnCurso(null, HORA, "perfil");
        when(confirmacion.porHistorial("perfil", HORA)).thenReturn(con(false, Platform.INSTAGRAM, "ig"));

        assertThat(consulta.preguntar(sinId).porRed()).containsOnlyKeys(Platform.INSTAGRAM);
        verify(confirmacion, never()).porEnvio(anyString());
    }
}
