package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.models.request.MediaPresignRequest;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.storage.R2StorageService;

/** El freno de la carga masiva: cuántos archivos al día por espacio. R2 va doblado. */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000",
        "app.agente.enabled=false",
        "app.media.max-subidas-por-dia=3"
})
class MediaTopeDiarioTest {

    @Autowired private MediaService media;
    @Autowired private MediaAssetRepository assets;
    @Autowired private NamedParameterJdbcTemplate sql;

    @MockitoBean private R2StorageService storage;

    private final UUID ws = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        when(storage.claveNueva(any(UUID.class), anyString(), anyString())).thenAnswer(i -> "media/" + ws + "/" + UUID.randomUUID() + ".jpg");
        when(storage.urlDe(anyString())).thenAnswer(i -> "https://cdn.test/" + i.getArgument(0));
        when(storage.firmarSubida(anyString(), anyString())).thenReturn("https://r2.test/firmada");
        when(storage.tipoDe(anyString(), anyString())).thenReturn(MediaType.IMAGE);
    }

    @AfterEach
    void limpiar() {
        sql.update("delete from media_assets where tenant_id = :t", Map.of("t", ws.toString()));
    }

    private void pedir(String nombre) {
        MediaPresignRequest r = new MediaPresignRequest();
        r.setFileName(nombre);
        r.setContentType("image/jpeg");
        r.setSizeBytes(1024);
        media.presign(r, ws);
    }

    @Test
    @DisplayName("pasado el tope del día ya no se aparta espacio, con un motivo claro; las copias de la IA no cuentan")
    void topeDiario() {
        TenantIdentifierResolver.comoTenant(ws.toString(), () -> {
            // Una copia hecha por la IA (retoque, logo, diseño): no cuenta para el tope.
            assets.save(MediaAsset.builder().fileName("retoque.jpg").url("https://cdn.test/r.jpg")
                    .type(MediaType.IMAGE).status(MediaAssetStatus.READY).generadaPorIa(true).build());

            pedir("1.jpg");
            pedir("2.jpg");
            pedir("3.jpg");
            assertThat(media.usage().getSubidasHoy()).isEqualTo(3);
            assertThat(media.usage().getMaxSubidasPorDia()).isEqualTo(3);
            assertThat(media.usage().getMaxPorTanda()).isEqualTo(20);
            assertThat(media.usage().getMaxVideosPorTanda()).isEqualTo(5);

            assertThatThrownBy(() -> pedir("4.jpg"))
                    .isInstanceOf(QuotaExceededException.class)
                    .hasMessageContaining("Mañana puedes seguir")
                    .satisfies(ex -> assertThat(((QuotaExceededException) ex).getCode()).isEqualTo("DAILY_UPLOADS_EXCEEDED"));
        });
    }
}
