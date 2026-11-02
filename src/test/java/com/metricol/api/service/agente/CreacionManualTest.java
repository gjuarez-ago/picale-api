package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.EtapaAgente;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.media.subtitulos.SubtitulosAutomaticos;

/** «Crear publicación» sin preguntas: qué se puede pedir y en qué va. */
class CreacionManualTest {

    private static final UUID WS = UUID.randomUUID();
    private MediaAssetRepository assets;
    private AgenteService agente;
    private CreacionManual creacion;

    @BeforeEach
    void preparar() {
        assets = mock(MediaAssetRepository.class);
        agente = mock(AgenteService.class);
        creacion = new CreacionManual(assets, agente);
        when(agente.propuestasDeArchivos(any())).thenReturn(List.of());
        when(assets.copiasEditadas(any())).thenReturn(List.of());
    }

    private MediaAsset archivo(MediaType tipo) {
        MediaAsset a = MediaAsset.builder().id(UUID.randomUUID()).type(tipo).status(MediaAssetStatus.READY)
                .fileName(tipo == MediaType.VIDEO ? "v.mp4" : "f.jpg").url("https://cdn.test/" + UUID.randomUUID())
                .build();
        a.setTenantId(WS.toString());
        when(assets.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    @DisplayName("Un video va solo, hasta 10 fotos, de este negocio, ya subido y sin propuesta")
    void reglas() {
        MediaAsset video = archivo(MediaType.VIDEO);
        MediaAsset foto = archivo(MediaType.IMAGE);
        assertThatThrownBy(() -> creacion.pedir(List.of(video.getId(), foto.getId()), WS))
                .hasMessageContaining("Un video va solo");
        assertThatThrownBy(() -> creacion.pedir(List.of(), WS)).hasMessageContaining("Elige");
        assertThatThrownBy(() -> creacion.pedir(List.of(foto.getId()), UUID.randomUUID()))
                .hasMessageContaining("no es de este negocio");
        foto.setStatus(MediaAssetStatus.PENDING);
        assertThatThrownBy(() -> creacion.pedir(List.of(foto.getId()), WS)).hasMessageContaining("subiendo");
        video.setAgenteEtapa(EtapaAgente.PROPUESTA);
        assertThatThrownBy(() -> creacion.pedir(List.of(video.getId()), WS)).hasMessageContaining("ya tiene una propuesta");
        List<UUID> once = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) once.add(archivo(MediaType.IMAGE).getId());
        assertThatThrownBy(() -> creacion.pedir(once, WS)).hasMessageContaining("hasta 10");
    }

    @Test
    @DisplayName("En qué va: editando, preparando, lista, o algo que la persona decide")
    void fases() {
        MediaAsset video = archivo(MediaType.VIDEO);
        video.setSubtitulosEstado(SubtitulosAutomaticos.HACIENDO);
        assertThat(creacion.estado(List.of(video.getId()), WS).fase()).isEqualTo("EDITANDO");

        video.setSubtitulosEstado(SubtitulosAutomaticos.LISTO);
        video.setAgenteEtapa(EtapaAgente.REVISANDO);
        assertThat(creacion.estado(List.of(video.getId()), WS).fase()).isEqualTo("PREPARANDO");

        video.setAgenteEtapa(EtapaAgente.OBSERVACION);
        video.setAgenteMotivo("Es horizontal.");
        CreacionManual.Estado decidir = creacion.estado(List.of(video.getId()), WS);
        assertThat(decidir.fase()).isEqualTo("DECIDIR");
        assertThat(decidir.mensaje()).isEqualTo("Es horizontal.");

        PostResponse propuesta = PostResponse.builder().id(UUID.randomUUID()).build();
        when(agente.propuestasDeArchivos(any())).thenReturn(List.of(propuesta));
        CreacionManual.Estado lista = creacion.estado(List.of(video.getId()), WS);
        assertThat(lista.fase()).isEqualTo("LISTA");
        assertThat(lista.propuestas()).containsExactly(propuesta);
    }

    @Test
    @DisplayName("Si algo falla, se dice en palabras y la pantalla lo enseña")
    void fallo() {
        MediaAsset foto = archivo(MediaType.IMAGE);
        doThrow(new IllegalStateException("Conecta al menos una red que acepte fotos."))
                .when(agente).trabajarPedido(anyList(), any());
        creacion.trabajar(List.of(foto.getId()), WS);
        assertThat(foto.getAgenteEtapa()).isEqualTo(EtapaAgente.PENDIENTE);
        CreacionManual.Estado e = creacion.estado(List.of(foto.getId()), WS);
        assertThat(e.fase()).isEqualTo("ERROR");
        assertThat(e.mensaje()).contains("Conecta al menos una red");
    }

    @Test
    @DisplayName("Una foto no espera nada: se trabaja de inmediato")
    void fotoDeInmediato() {
        MediaAsset foto = archivo(MediaType.IMAGE);
        creacion.trabajar(List.of(foto.getId()), WS);
        verify(agente).trabajarPedido(List.of(foto.getId()), WS);
    }
}
