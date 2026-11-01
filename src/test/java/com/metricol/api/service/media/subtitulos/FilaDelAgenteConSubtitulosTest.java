package com.metricol.api.service.media.subtitulos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;

/**
 * Lo que el agente toma por su cuenta cuando hay subtítulos de por medio: no
 * toma un video que todavía espera los suyos (publicaría el original), ni la
 * copia con subtítulos como si fuera un video nuevo (saldría repetido).
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000"
})
class FilaDelAgenteConSubtitulosTest {

    private static final String WS = "77777777-7777-7777-7777-777777777777";

    @Autowired
    private MediaAssetRepository assets;

    private final List<UUID> creados = new ArrayList<>();

    @AfterEach
    void limpiar() {
        TenantIdentifierResolver.comoTenant(WS, () -> creados.forEach(id -> assets.findById(id).ifPresent(assets::delete)));
    }

    private MediaAsset video(String nombre, String estado, String subtitulos, UUID original) {
        MediaAsset v = assets.save(MediaAsset.builder()
                .fileName(nombre).url("https://cdn.test/" + nombre).storageKey("media/" + WS + "/" + nombre)
                .type(MediaType.VIDEO).contentType("video/mp4").sizeBytes(1024L).thumbnailUrl("https://cdn.test/t.jpg")
                .status(MediaAssetStatus.READY).subtitulosEstado(estado).subtitulos(subtitulos)
                .sourceAssetId(original).build());
        creados.add(v.getId());
        return v;
    }

    @Test
    @DisplayName("El agente espera los subtítulos del video y nunca toma la copia por su cuenta")
    void esperaYNoRepite() {
        TenantIdentifierResolver.comoTenant(WS, () -> {
            LocalDateTime desde = LocalDateTime.now().minusHours(1);
            MediaAsset normal = video("normal.mp4", null, null, null);
            MediaAsset esperando = video("esperando.mp4", SubtitulosAutomaticos.ESPERANDO, null, null);
            MediaAsset haciendo = video("haciendo.mp4", SubtitulosAutomaticos.HACIENDO, null, null);
            MediaAsset copia = video("haciendo-subtitulado.mp4", null, "{\"estilo\":\"PALABRA\"}", haciendo.getId());

            List<UUID> fila = assets.paraElAgente(desde, LocalDateTime.now(), PageRequest.of(0, 50))
                    .stream().map(MediaAsset::getId).toList();
            assertThat(fila).contains(normal.getId()).doesNotContain(esperando.getId(), haciendo.getId(), copia.getId());
            assertThat(assets.porRevisarVideosDelAgente(desde)).isEqualTo(1);

            // Ya con su copia, el video entra, y la copia es la que se publica.
            haciendo.setSubtitulosEstado(SubtitulosAutomaticos.LISTO);
            assets.save(haciendo);
            assertThat(assets.paraElAgente(desde, LocalDateTime.now(), PageRequest.of(0, 50)))
                    .extracting(MediaAsset::getId).contains(haciendo.getId());
            assertThat(assets.copiasConSubtitulos(haciendo.getId())).extracting(MediaAsset::getId)
                    .containsExactly(copia.getId());
            assertThat(assets.esperandoSubtitulos(10)).extracting(f -> String.valueOf(f[0]))
                    .contains(esperando.getId().toString());
        });
    }

    @Test
    @DisplayName("Un video haciendo sin copia en curso aparece para soltarlo; con su copia en curso, no (la consulta corre de verdad)")
    void haciendoSinCopia() {
        TenantIdentifierResolver.comoTenant(WS, () -> {
            MediaAsset solo = video("solo.mp4", SubtitulosAutomaticos.HACIENDO, null, null);
            MediaAsset conCopia = video("con-copia.mp4", SubtitulosAutomaticos.HACIENDO, null, null);
            MediaAsset copia = video("con-copia-subtitulado.mp4", null, "{}", conCopia.getId());
            copia.setStatus(MediaAssetStatus.PROCESSING);
            assets.save(copia);
            List<String> ids = assets.haciendoSinCopiaEnCurso(50).stream().map(f -> String.valueOf(f[0])).toList();
            assertThat(ids).contains(solo.getId().toString()).doesNotContain(conCopia.getId().toString());
        });
    }
}
