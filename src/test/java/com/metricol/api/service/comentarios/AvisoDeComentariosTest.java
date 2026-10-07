package com.metricol.api.service.comentarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.enums.Permission;
import com.metricol.api.repository.ComentarioRepository;
import com.metricol.api.service.avisos.AvisosPush;

/**
 * Los avisos al teléfono. Todo lo que se prueba aquí es que NO avise: uno
 * agrupado, con hueco entre uno y otro, nada de noche y tope al día (N-01 a
 * N-06, N-12).
 */
class AvisoDeComentariosTest {

    private ComentarioRepository repo;
    private AvisosPush push;
    private AvisoDeComentarios aviso;
    private final UUID espacio = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        repo = mock(ComentarioRepository.class);
        push = mock(AvisosPush.class);
        when(push.activo()).thenReturn(true);
        when(push.avisarAQuienPuede(any(), any(), anyString(), anyString(), any())).thenReturn(true);
        aviso = new AvisoDeComentarios(repo, push);
        aviso.configurar(2, 4, 8, 22);
    }

    private void hay(long cuantos, long cuentas) {
        when(repo.nuevosSinAtenderPorEspacio(any()))
                .thenReturn(List.<Object[]>of(new Object[] {espacio.toString(), cuantos, cuentas}));
    }

    @Test
    @DisplayName("N-02: siete comentarios son UN aviso, no siete")
    void unoSolo() {
        hay(7, 1);

        aviso.avisarDeLoNuevo();

        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(push, times(1)).avisarAQuienPuede(eq(espacio), eq(Permission.COMMENT_REPLY), anyString(),
                texto.capture(), any());
        assertThat(texto.getValue()).contains("7 comentarios nuevos");
    }

    @Test
    @DisplayName("N-04: no se avisa otra vez hasta que pasen las horas de respiro")
    void respiroEntreAvisos() {
        hay(3, 1);

        aviso.avisarDeLoNuevo();
        aviso.avisarDeLoNuevo();

        verify(push, times(1)).avisarAQuienPuede(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("N-12: sin credenciales de avisos no se hace nada, y nada se rompe")
    void sinCredenciales() {
        when(push.activo()).thenReturn(false);
        hay(3, 1);

        aviso.avisarDeLoNuevo();

        verify(push, never()).avisarAQuienPuede(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Sin pendientes no se avisa de nada")
    void sinPendientes() {
        hay(0, 0);

        aviso.avisarDeLoNuevo();

        verify(push, never()).avisarAQuienPuede(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("N-05: de noche no se avisa; a las 8 de la mañana sí")
    void deNoche() {
        LocalDateTime noche = LocalDateTime.of(2026, 10, 7, 23, 30);
        LocalDateTime madrugada = LocalDateTime.of(2026, 10, 8, 3, 0);
        LocalDateTime manana = LocalDateTime.of(2026, 10, 8, 8, 0);

        assertThat(aviso.toca(espacio, noche)).isFalse();
        assertThat(aviso.toca(espacio, madrugada)).isFalse();
        assertThat(aviso.toca(espacio, manana)).isTrue();
    }

    @Test
    @DisplayName("N-06: pasados los cuatro del día, se calla hasta mañana")
    void topeDiario() {
        LocalDateTime dia = LocalDateTime.of(2026, 10, 7, 9, 0);

        // Cuatro avisos repartidos a lo largo del día, respetando el respiro.
        for (int i = 0; i < 4; i++) {
            LocalDateTime cuando = dia.plusHours(i * 3L);
            assertThat(aviso.toca(espacio, cuando)).isTrue();
            aviso.apuntar(espacio, cuando);
        }

        assertThat(aviso.toca(espacio, dia.plusHours(12))).isFalse();
        // Al día siguiente vuelve a empezar la cuenta.
        assertThat(aviso.toca(espacio, dia.plusDays(1))).isTrue();
    }

    @Test
    @DisplayName("N-07: con la bandeja abierta no se avisa al teléfono")
    void bandejaAbierta() {
        hay(4, 1);
        aviso.estanMirando(espacio);

        aviso.avisarDeLoNuevo();

        verify(push, never()).avisarAQuienPuede(any(), any(), anyString(), anyString(), any());
        // Pasado el rato, vuelve a avisar: la persona ya no está mirando.
        assertThat(aviso.toca(espacio, LocalDateTime.now().plusMinutes(30))).isTrue();
    }

    @Test
    @DisplayName("N-15: avisa a quien puede CONTESTAR, no a quien puede programar")
    void aQuienPuedeContestar() {
        hay(2, 1);

        aviso.avisarDeLoNuevo();

        verify(push).avisarAQuienPuede(eq(espacio), eq(Permission.COMMENT_REPLY), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("N-01 y N-03: el texto se lee sin pensar, y nunca dice '1 comentarios'")
    void texto() {
        assertThat(AvisoDeComentarios.texto(1, 1)).isEqualTo("1 comentario nuevo esperando respuesta.");
        assertThat(AvisoDeComentarios.texto(3, 1)).isEqualTo("3 comentarios nuevos esperando respuesta.");
        assertThat(AvisoDeComentarios.texto(5, 2)).isEqualTo("5 comentarios nuevos en 2 cuentas, esperando respuesta.");
    }
}
