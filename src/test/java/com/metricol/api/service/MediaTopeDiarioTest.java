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
        "spring.datasource.url=${subidas.test.url:jdbc:h2:mem:mobile-upload;DB_CLOSE_DELAY=-1}",
        "spring.datasource.driver-class-name=${subidas.test.driver:org.h2.Driver}",
        "spring.datasource.username=${subidas.test.user:sa}",
        "spring.datasource.password=${subidas.test.password:}",
        "spring.jpa.database-platform=${subidas.test.dialect:org.hibernate.dialect.H2Dialect}",
        // El perfil dev toma el dialecto de DB_DIALECT, y esa propiedad le gana a la de arriba: con un
        // .env apuntado a Postgres, Hibernate le hablaba en Postgres a esta H2 y el candado de
        // upload_scopes ("for no key update") no se podía preparar.
        "spring.jpa.properties.hibernate.dialect=${subidas.test.dialect:org.hibernate.dialect.H2Dialect}",
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
    @Autowired private com.metricol.api.service.storage.MediaAssetReconciler reconciler;

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
    @org.junit.jupiter.api.DisplayName("El mismo archivo otra vez se detiene y pregunta; con «súbelo igual» pasa; en otro negocio no cuenta")
    void repetido() {
        long tamano = 3_456_789;
        TenantIdentifierResolver.comoTenant(ws.toString(), () -> {
            assets.save(com.metricol.api.entity.MediaAsset.builder().fileName("video-1.mp4").url("https://cdn.test/v1")
                    .storageKey("media/" + ws + "/v1.mp4").type(MediaType.VIDEO).contentType("video/mp4")
                    .sizeBytes(tamano).status(MediaAssetStatus.READY).build());
            MediaPresignRequest otra = new MediaPresignRequest();
            otra.setFileName("video-1 (copia).mp4");
            otra.setContentType("video/mp4");
            otra.setSizeBytes(tamano);
            assertThatThrownBy(() -> media.presign(otra, ws))
                    .isInstanceOf(com.metricol.api.exception.ConflictoException.class)
                    .hasMessageContaining("Este video ya está en tu Contenido: lo subiste hoy como «video-1.mp4»")
                    .hasMessageContaining("No lo subí otra vez");
            otra.setAunqueRepetido(true);
            assertThat(media.presign(otra, ws).getAssetId()).isNotNull();

            // Otro tamaño, aunque sea del mismo tipo, no es el mismo archivo.
            MediaPresignRequest distinto = new MediaPresignRequest();
            distinto.setFileName("video-2.mp4");
            distinto.setContentType("video/mp4");
            distinto.setSizeBytes(tamano + 1);
            assertThat(media.presign(distinto, ws).getAssetId()).isNotNull();
        });
        UUID otroNegocio = UUID.randomUUID();
        TenantIdentifierResolver.comoTenant(otroNegocio.toString(), () -> {
            MediaPresignRequest suyo = new MediaPresignRequest();
            suyo.setFileName("video-1.mp4");
            suyo.setContentType("video/mp4");
            suyo.setSizeBytes(tamano);
            assertThat(media.presign(suyo, otroNegocio).getAssetId()).isNotNull();
        });
        sql.update("delete from media_assets where tenant_id = :t", Map.of("t", otroNegocio.toString()));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Con huella: el mismo contenido se reconoce aunque pese poco; dos distintos del mismo tamaño no se confunden")
    void repetidoPorHuella() {
        String h1 = "a".repeat(64);
        String h2 = "b".repeat(64);
        TenantIdentifierResolver.comoTenant(ws.toString(), () -> {
            com.metricol.api.entity.MediaAsset ya = assets.save(com.metricol.api.entity.MediaAsset.builder()
                    .fileName("logo.png").url("https://cdn.test/l").storageKey("media/" + ws + "/l.png")
                    .type(MediaType.IMAGE).contentType("image/png").sizeBytes(20_000L).huellaArchivo(h1)
                    .status(MediaAssetStatus.READY).build());
            MediaPresignRequest chico = new MediaPresignRequest();
            chico.setFileName("otra-vez.png");
            chico.setContentType("image/png");
            chico.setSizeBytes(20_000);
            chico.setHuellaArchivo(h1);
            // Debajo de 100 KB el tamaño solo no avisaba; la huella sí.
            assertThatThrownBy(() -> media.presign(chico, ws))
                    .isInstanceOfSatisfying(com.metricol.api.exception.ConflictoException.class,
                            ex -> assertThat(ex.getDetalles()).containsEntry("assetId", ya.getId().toString()));

            assets.save(com.metricol.api.entity.MediaAsset.builder().fileName("a.mp4").url("https://cdn.test/a")
                    .storageKey("media/" + ws + "/a.mp4").type(MediaType.VIDEO).contentType("video/mp4")
                    .sizeBytes(5_000_000L).huellaArchivo(h1).status(MediaAssetStatus.READY).build());
            MediaPresignRequest mismoPeso = new MediaPresignRequest();
            mismoPeso.setFileName("b.mp4");
            mismoPeso.setContentType("video/mp4");
            mismoPeso.setSizeBytes(5_000_000);
            mismoPeso.setHuellaArchivo(h2);
            assertThat(media.presign(mismoPeso, ws).getAssetId()).isNotNull();
        });
    }

    @Test
    void batchMetadataAndPendingCountAreTenantScoped() {
        var batch = UUID.randomUUID();
        TenantIdentifierResolver.comoTenant(ws.toString(), () -> {
            var request = new MediaPresignRequest();
            request.setFileName("photo.jpg");
            request.setContentType("image/jpeg");
            request.setSizeBytes(1024);
            request.setUploadBatchId(batch);
            request.setUploadBatchIndex(2);
            var signed = media.presign(request, ws);
            var saved = assets.findById(signed.getAssetId()).orElseThrow();
            assertThat(saved.getUploadBatchId()).isEqualTo(batch);
            assertThat(saved.getUploadBatchIndex()).isEqualTo(2);
            assertThat(media.renewUpload(signed.getAssetId(), ws).getAssetId()).isEqualTo(signed.getAssetId());
            assertThat(assets.findById(signed.getAssetId()).orElseThrow().getUploadRenewedAt()).isNotNull();
            assertThatThrownBy(() -> media.renewUpload(signed.getAssetId(), UUID.randomUUID()))
                    .isInstanceOf(com.metricol.api.exception.ResourceNotFoundException.class);
            assertThat(assets.countByUploadBatchIdAndStatus(batch, MediaAssetStatus.PENDING)).isEqualTo(1);
            assertThat(assets.activeUploadsInBatch(batch, java.time.LocalDateTime.now().minusHours(1))).isEqualTo(1);
            assertThat(assets.activeUploadsInBatch(batch, java.time.LocalDateTime.now().plusMinutes(1))).isZero();
        });
        TenantIdentifierResolver.comoTenant(UUID.randomUUID().toString(), () -> {
            assertThat(assets.countByUploadBatchIdAndStatus(batch, MediaAssetStatus.PENDING)).isZero();
        });
    }

    @Test
    void partialVideoIsNotConfirmedOrDeleted() {
        TenantIdentifierResolver.comoTenant(ws.toString(), () -> {
            var asset = assets.save(MediaAsset.builder().fileName("clip.mp4").storageKey("clip")
                    .type(MediaType.VIDEO).sizeBytes(1024L).status(MediaAssetStatus.PENDING).build());
            when(storage.consultar("clip")).thenReturn(new R2StorageService.Consulta(512, "video/mp4"));
            assertThatThrownBy(() -> media.confirm(asset.getId())).hasMessageContaining("incompleta");
            assertThat(reconciler.resolver(asset.getId())).isFalse();
            assertThat(assets.findById(asset.getId()).orElseThrow().getStatus()).isEqualTo(MediaAssetStatus.PENDING);
            org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).deleteByKey("clip");
        });
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
