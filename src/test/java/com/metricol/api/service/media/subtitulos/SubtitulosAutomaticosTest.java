package com.metricol.api.service.media.subtitulos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.WorkspaceRepository;

/** El recorrido de un video con subtítulos automáticos: nunca se queda atorado. */
class SubtitulosAutomaticosTest {

    private static final UUID WS = UUID.randomUUID();
    private MediaAssetRepository assets;
    private SubtitulosService subtitulos;
    private SubtitulosAutomaticos automaticos;
    private Workspace espacio;
    private MediaAsset video;

    @BeforeEach
    void preparar() {
        assets = mock(MediaAssetRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        subtitulos = mock(SubtitulosService.class);
        automaticos = new SubtitulosAutomaticos(assets, workspaces, subtitulos);
        espacio = new Workspace();
        espacio.setSubtitulosAuto(true);
        espacio.setSubtitulosEstilo("REBOTE");
        when(workspaces.findById(WS)).thenReturn(Optional.of(espacio));
        when(workspaces.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        video = MediaAsset.builder().id(UUID.randomUUID()).type(MediaType.VIDEO).status(MediaAssetStatus.READY)
                .subtitulosEstado(SubtitulosAutomaticos.ESPERANDO).build();
        video.setTenantId(WS.toString());
        when(assets.findById(video.getId())).thenReturn(Optional.of(video));
    }

    @Test
    @DisplayName("Saca el texto y encarga la copia con el estilo de la cuenta; el agente sigue esperando")
    void encargaConElEstiloDeLaCuenta() {
        automaticos.procesar(video.getId());
        verify(subtitulos).texto(video.getId(), WS);
        verify(subtitulos).pedir(eq(video.getId()), eq(WS), eq(EstiloSubtitulos.REBOTE), eq(null), eq(null));
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.HACIENDO);
        assertThat(video.getSubtitulosIntentos()).isEqualTo(1);
    }

    @Test
    @DisplayName("Si nadie habla, se suelta sin subtítulos a la primera")
    void sinVozSeSuelta() {
        doThrow(new IllegalArgumentException("No escuché a nadie hablando en este video."))
                .when(subtitulos).texto(video.getId(), WS);
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.SIN_SUBTITULOS);
        verify(subtitulos, never()).pedir(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Un fallo se reintenta, y a la tercera se suelta sin subtítulos")
    void tresFallosYSeSuelta() {
        doThrow(new IllegalStateException("OpenAI no contesta")).when(subtitulos).texto(video.getId(), WS);
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);
        automaticos.procesar(video.getId());
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.SIN_SUBTITULOS);
        assertThat(video.getSubtitulosIntentos()).isEqualTo(3);
    }

    @Test
    @DisplayName("Si los apagaron mientras esperaba, el video se suelta tal cual")
    void apagados() {
        espacio.setSubtitulosAuto(false);
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isNull();
        verify(subtitulos, never()).texto(any(), any());
    }

    @Test
    @DisplayName("Copia lista: se suelta con copia. Copia fallida: se reintenta, y a la tercera se suelta")
    void copiaTerminada() {
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        video.setSubtitulosIntentos(1);
        SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), false);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        video.setSubtitulosIntentos(3);
        SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), false);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.SIN_SUBTITULOS);
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), true);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.LISTO);

        // Una copia pedida a mano no toca un video que no la esperaba: ni lo
        // devuelve a la fila de los automáticos si falla.
        video.setSubtitulosEstado(SubtitulosAutomaticos.LISTO);
        SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), false);
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.LISTO);
    }

    @Test
    @DisplayName("Si la copia termina mientras se encarga, gana el listo: el video no se queda haciendo")
    void carreraConLaCola() {
        // La cola termina la copia dentro de pedir(), antes de que vuelva.
        when(subtitulos.pedir(any(), any(), any(), any(), any())).thenAnswer(i -> {
            SubtitulosAutomaticos.copiaTerminada(assets, video.getId(), true);
            return null;
        });
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.LISTO);
    }

    @Test
    @DisplayName("Con la cola llena espera su turno sin gastar un intento")
    void colaLlena() {
        doThrow(new SubtitulosService.ColaLlena()).when(subtitulos).pedir(any(), any(), any(), any(), any());
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);
        assertThat(video.getSubtitulosIntentos()).isZero();
    }

    @Test
    @DisplayName("Un video haciendo sin copia en la cola se suelta: con su copia si ya está, o de vuelta a esperar")
    void atorado() {
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        video.setSubtitulosIntentos(1);
        when(assets.copiasConSubtitulos(video.getId())).thenReturn(java.util.List.of());
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);

        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        when(assets.copiasConSubtitulos(video.getId())).thenReturn(java.util.List.of(new MediaAsset()));
        automaticos.procesar(video.getId());
        assertThat(video.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.LISTO);
    }

    @Test
    @DisplayName("Los ajustes se guardan; sin estilo ni color es al azar")
    void ajustes() {
        SubtitulosAutomaticos.Ajustes guardados = automaticos.guardar(WS,
                new SubtitulosAutomaticos.Ajustes(true, null, SubtitulosService.Color.AMARILLO));
        assertThat(guardados.activo()).isTrue();
        assertThat(guardados.estilo()).isNull();
        assertThat(guardados.color()).isEqualTo(SubtitulosService.Color.AMARILLO);
        assertThat(espacio.getSubtitulosEstilo()).isNull();
    }
}
