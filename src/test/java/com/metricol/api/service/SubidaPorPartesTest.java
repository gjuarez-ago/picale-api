package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.models.request.MediaPresignRequest;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.MultipartPartsResponse;
import com.metricol.api.models.response.MultipartUploadResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.media.MiniaturasEnSegundoPlano;
import com.metricol.api.service.storage.MediaAssetReconciler;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.R2StorageService.ParteSubida;

/**
 * Subir un archivo grande por partes: abrir, firmar, retomar, unir y cancelar.
 *
 * <p>R2 va doblado: aquí no hay bucket (el {@code .env} local apunta al de producción), y lo que se
 * prueba es la regla. Que R2 acepte de verdad partes firmadas se comprobó aparte contra el bucket,
 * ver docs/subida-por-partes.md.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.rescate-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000",
        "app.media.multipart.modo=TODAS"
})
class SubidaPorPartesTest {

    private static final String WS = "66666666-6666-6666-6666-666666666666";
    private static final UUID WS_ID = UUID.fromString(WS);
    private static final long MIB = 1024L * 1024;
    /** 300 MB: 19 partes de 16 MiB, la última de 12 MiB. */
    private static final long PESO = 300L * MIB;

    @Autowired
    private MediaService media;

    @Autowired
    private MediaAssetRepository assets;

    @Autowired
    private MediaAssetReconciler reconciliador;

    @Autowired
    private MediaLimitsProperties limites;

    @MockitoBean
    private R2StorageService storage;

    @MockitoBean
    private MiniaturasEnSegundoPlano miniaturas;

    private final List<UUID> creados = new ArrayList<>();

    @BeforeEach
    void r2DeMentira() {
        when(storage.claveNueva(any(UUID.class), anyString(), anyString()))
                .thenAnswer(i -> "media/" + WS + "/" + UUID.randomUUID() + ".mp4");
        when(storage.urlDe(anyString())).thenAnswer(i -> "https://cdn.test/" + i.getArgument(0));
        when(storage.tipoDe(anyString(), anyString())).thenReturn(MediaType.VIDEO);
        when(storage.iniciarPorPartes(anyString(), anyString())).thenReturn("subida-1");
        when(storage.firmarParte(anyString(), anyString(), anyInt(), anyLong()))
                .thenAnswer(i -> "https://r2.test/parte/" + i.getArgument(2));
    }

    @AfterEach
    void limpiar() {
        limites.getMultipart().setModo(MediaLimitsProperties.Multipart.Modo.TODAS);
        enElWorkspace(() -> {
            creados.forEach(id -> assets.findById(id).ifPresent(assets::delete));
            return null;
        });
    }

    // ------------------------------------------------------------------ abrir

    @Test
    @DisplayName("abrir crea la fila pendiente con su subida de R2 y dice cuántas partes subir")
    void abre() {
        MultipartUploadResponse abierta = abrir();

        assertThat(abierta.getPartCount()).isEqualTo(19);
        assertThat(abierta.getPartSizeBytes()).isEqualTo(16 * MIB);
        assertThat(abierta.getSizeBytes()).isEqualTo(PESO);
        MediaAsset fila = fila(abierta.getAssetId());
        assertThat(fila.getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(fila.getMultipartUploadId()).isEqualTo("subida-1");
        assertThat(fila.getMultipartPartSize()).isEqualTo(16 * MIB);
        verify(storage).iniciarPorPartes(anyString(), eq("video/mp4"));
    }

    @Test
    @DisplayName("apagada, no se abre nada y el uso dice que no toca")
    void apagada() {
        limites.getMultipart().setModo(MediaLimitsProperties.Multipart.Modo.APAGADA);

        assertThat(enElWorkspace(() -> media.multipartDesde())).isNull();
        assertThatThrownBy(this::abrir).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no está activa");
        verify(storage, never()).iniciarPorPartes(anyString(), anyString());
    }

    @Test
    @DisplayName("solo para las cuentas sin límites: un espacio normal no la tiene")
    void soloSinLimites() {
        limites.getMultipart().setModo(MediaLimitsProperties.Multipart.Modo.SIN_LIMITES);
        assertThat(enElWorkspace(() -> media.multipartDesde())).isNull();
    }

    @Test
    @DisplayName("un archivo que no cabe por tamaño no abre nada en R2")
    void respetaElTope() {
        MediaPresignRequest enorme = pedido(600L * MIB);
        assertThatThrownBy(() -> enElWorkspace(() -> media.iniciarPorPartes(enorme, WS_ID)))
                .hasMessageContaining("máximo por archivo");
        verify(storage, never()).iniciarPorPartes(anyString(), anyString());
    }

    // ------------------------------------------------------------------ firmar

    @Test
    @DisplayName("firma el lote pedido, una URL por parte, y lo cuenta como señal de vida")
    void firma() {
        UUID id = abrir().getAssetId();
        MultipartPartsResponse lote = enElWorkspace(() -> media.firmarPartes(id, WS_ID, List.of(3, 1, 2, 2)));

        assertThat(lote.getSigned()).extracting(MultipartPartsResponse.SignedPart::getPartNumber)
                .containsExactly(1, 2, 3);
        assertThat(lote.getSigned().get(0).getUrl()).isEqualTo("https://r2.test/parte/1");
        assertThat(lote.getExpiresInSeconds()).isEqualTo(3600);
        assertThat(fila(id).getUploadRenewedAt()).isNotNull();
    }

    @Test
    @DisplayName("no firma partes que no existen en esta subida")
    void noFirmaDeMas() {
        UUID id = abrir().getAssetId();
        assertThatThrownBy(() -> enElWorkspace(() -> media.firmarPartes(id, WS_ID, List.of(20))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tiene 19");
        assertThatThrownBy(() -> enElWorkspace(() -> media.firmarPartes(id, WS_ID, List.of(0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("una subida de otro negocio no existe para este")
    void ajena() {
        UUID id = abrir().getAssetId();
        UUID otro = UUID.randomUUID();
        assertThatThrownBy(() -> enElWorkspace(() -> media.firmarPartes(id, otro, List.of(1))))
                .hasMessageContaining("ya no existe");
    }

    @Test
    @DisplayName("una subida por partes no se puede pisar con un PUT entero")
    void sinPutEncima() {
        UUID id = abrir().getAssetId();
        assertThatThrownBy(() -> enElWorkspace(() -> media.renewUpload(id, WS_ID)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("por partes");
    }

    // ------------------------------------------------------------------ retomar

    @Test
    @DisplayName("retomar: dice qué partes ya llegaron y cuánto suman, según R2")
    void retoma() {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), eq("subida-1"))).thenReturn(List.of(
                new ParteSubida(1, "\"a\"", 16 * MIB), new ParteSubida(2, "\"b\"", 16 * MIB)));

        MultipartPartsResponse estado = enElWorkspace(() -> media.partesSubidas(id, WS_ID));
        assertThat(estado.getUploaded()).extracting(MultipartPartsResponse.UploadedPart::getPartNumber)
                .containsExactly(1, 2);
        assertThat(estado.getUploadedBytes()).isEqualTo(32 * MIB);
        assertThat(estado.getPartCount()).isEqualTo(19);
    }

    // ------------------------------------------------------------------ unir

    @Test
    @DisplayName("unir: con todas las partes, R2 las une y el archivo queda listo con su tamaño real")
    void une() {
        UUID id = abrir().getAssetId();
        List<ParteSubida> todas = todasLasPartes();
        when(storage.partesSubidas(anyString(), eq("subida-1"))).thenReturn(todas);
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO, "video/mp4"));

        MediaAssetResponse listo = enElWorkspace(() -> media.completarPorPartes(id, WS_ID));

        verify(storage).completarPorPartes(anyString(), eq("subida-1"), eq(todas));
        assertThat(listo.getStatus()).isEqualTo(MediaAssetStatus.READY);
        MediaAsset fila = fila(id);
        assertThat(fila.getStatus()).isEqualTo(MediaAssetStatus.READY);
        assertThat(fila.getMultipartUploadId()).isNull();
        assertThat(fila.getSizeBytes()).isEqualTo(PESO);
    }

    @Test
    @DisplayName("unir dos veces (se perdió la respuesta) no vuelve a unir: contesta el archivo listo")
    void unirDosVeces() {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), eq("subida-1"))).thenReturn(todasLasPartes());
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO, "video/mp4"));

        enElWorkspace(() -> media.completarPorPartes(id, WS_ID));
        MediaAssetResponse otraVez = enElWorkspace(() -> media.completarPorPartes(id, WS_ID));

        assertThat(otraVez.getStatus()).isEqualTo(MediaAssetStatus.READY);
        verify(storage, org.mockito.Mockito.times(1)).completarPorPartes(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("si faltan partes no une nada y dice cuántas faltan")
    void faltanPartes() {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), eq("subida-1"))).thenReturn(todasLasPartes().subList(0, 17));

        assertThatThrownBy(() -> enElWorkspace(() -> media.completarPorPartes(id, WS_ID)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Faltan 2 de 19");
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.PENDING);
    }

    @Test
    @DisplayName("si las partes no suman lo declarado no une: no se cuela un archivo distinto")
    void sumaDistinta() {
        UUID id = abrir().getAssetId();
        List<ParteSubida> partes = new ArrayList<>(todasLasPartes());
        partes.set(18, new ParteSubida(19, "\"z\"", 1 * MIB));
        when(storage.partesSubidas(anyString(), eq("subida-1"))).thenReturn(partes);

        assertThatThrownBy(() -> enElWorkspace(() -> media.completarPorPartes(id, WS_ID)))
                .hasMessageContaining("no suman");
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------ cancelar y abandonar

    @Test
    void recuperaLaMismaSesionAunqueSeApaguenLasNuevas() {
        MediaPresignRequest pedido = pedido(PESO);
        pedido.setClientUploadId("multipart_" + UUID.randomUUID());
        MultipartUploadResponse original = enElWorkspace(() -> media.iniciarPorPartes(pedido, WS_ID));
        creados.add(original.getAssetId());
        limites.getMultipart().setModo(MediaLimitsProperties.Multipart.Modo.APAGADA);

        MultipartUploadResponse recuperada = enElWorkspace(() -> media.iniciarPorPartes(pedido, WS_ID));

        assertThat(recuperada.getAssetId()).isEqualTo(original.getAssetId());
        assertThat(recuperada.getPartCount()).isEqualTo(19);
        verify(storage, org.mockito.Mockito.times(1)).iniciarPorPartes(anyString(), anyString());
    }

    @Test
    void rechazaPartesExtraAunqueLasEsperadasEstenCompletas() {
        List<ParteSubida> partes = new ArrayList<>(todasLasPartes());
        partes.add(new ParteSubida(20, "extra", MIB));
        rechazaPartes(partes);
    }

    @Test
    void rechazaNumeroCero() {
        List<ParteSubida> partes = new ArrayList<>(todasLasPartes());
        partes.add(new ParteSubida(0, "extra", 0));
        rechazaPartes(partes);
    }

    @Test
    void rechazaNumerosDuplicadosAunqueLaSumaSeaCorrecta() {
        List<ParteSubida> partes = new ArrayList<>(todasLasPartes());
        partes.set(0, new ParteSubida(1, "a", 8 * MIB));
        partes.add(new ParteSubida(1, "b", 8 * MIB));
        rechazaPartes(partes);
    }

    @Test
    void rechazaTamanosIntercambiadosAunqueLaSumaSeaCorrecta() {
        List<ParteSubida> partes = new ArrayList<>(todasLasPartes());
        partes.set(0, new ParteSubida(1, "a", 15 * MIB));
        partes.set(1, new ParteSubida(2, "b", 17 * MIB));
        rechazaPartes(partes);
    }

    @Test
    void rechazaEtagVacio() {
        List<ParteSubida> partes = new ArrayList<>(todasLasPartes());
        partes.set(0, new ParteSubida(1, " ", 16 * MIB));
        rechazaPartes(partes);
    }

    private void rechazaPartes(List<ParteSubida> partes) {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(partes);
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO, "video/mp4"));
        assertThatThrownBy(() -> enElWorkspace(() -> media.completarPorPartes(id, WS_ID)))
                .isInstanceOf(IllegalStateException.class);
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(fila(id).getMultipartUploadId()).isEqualTo("subida-1");
    }

    @Test
    void recuperaUnionTerminadaAntesDeGuardarEnLaBase() {
        UUID id = abrir().getAssetId();
        // R2 ya consumió uploadId al unir; ListParts devuelve vacío por NoSuchUpload.
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(List.of());
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO, "video/mp4"));

        MediaAssetResponse lista = enElWorkspace(() -> media.completarPorPartes(id, WS_ID));

        assertThat(lista.getStatus()).isEqualTo(MediaAssetStatus.READY);
        assertThat(fila(id).getMultipartUploadId()).isNull();
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
    }

    @Test
    void noRecuperaObjetoDeTamanoDistinto() {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(List.of());
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO + 1, "video/mp4"));
        assertThatThrownBy(() -> enElWorkspace(() -> media.completarPorPartes(id, WS_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(fila(id).getMultipartUploadId()).isEqualTo("subida-1");
    }

    @Test
    void errorAlUnirNoAceptaCualquierObjeto() {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(todasLasPartes());
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO + 1, "video/mp4"));
        doThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build())
                .when(storage).completarPorPartes(anyString(), anyString(), any());

        assertThatThrownBy(() -> enElWorkspace(() -> media.completarPorPartes(id, WS_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(fila(id).getMultipartUploadId()).isEqualTo("subida-1");
    }

    @Test
    void ordenaLasPartesAntesDeUnir() {
        UUID id = abrir().getAssetId();
        List<ParteSubida> desordenadas = new ArrayList<>(todasLasPartes());
        java.util.Collections.reverse(desordenadas);
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(desordenadas);
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO, "video/mp4"));
        enElWorkspace(() -> media.completarPorPartes(id, WS_ID));
        verify(storage).completarPorPartes(anyString(), anyString(), eq(todasLasPartes()));
    }

    @Test
    @DisplayName("cancelar aborta en R2 y libera cuota conservando el registro terminal")
    void cancela() {
        UUID id = abrir().getAssetId();
        enElWorkspace(() -> {
            media.cancelarPorPartes(id, WS_ID);
            return null;
        });

        verify(storage).abortarPorPartes(anyString(), eq("subida-1"));
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.RELEASED);
        assertThat(fila(id).getUploadDecision()).isEqualTo(com.metricol.api.enums.UploadDecision.CANCELLED);
    }

    @Test
    @DisplayName("una subida vencida se aborta antes de liberar cuota y queda registrada")
    void abandonada() {
        UUID id = abrir().getAssetId();
        vencer(id);
        when(storage.consultar(anyString())).thenReturn(null);

        boolean confirmada = enElWorkspace(() -> reconciliador.resolver(id));

        assertThat(confirmada).isFalse();
        verify(storage).abortarPorPartes(anyString(), eq("subida-1"));
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.RELEASED);
        assertThat(fila(id).getUploadDecision()).isEqualTo(com.metricol.api.enums.UploadDecision.EXPIRED);
    }

    @Test
    @DisplayName("si R2 no deja abortar, la fila se queda para reintentar: sin ella se perdería el id")
    void abandonadaSinR2() {
        UUID id = abrir().getAssetId();
        vencer(id);
        when(storage.consultar(anyString())).thenReturn(null);
        doThrow(new RuntimeException("R2 caído")).when(storage).abortarPorPartes(anyString(), anyString());

        enElWorkspace(() -> reconciliador.resolver(id));

        assertThat(enElWorkspace(() -> assets.findById(id))).isPresent();
    }

    // ------------------------------------------------------------------ cálculo de partes

    @Test
    void archivoConMasDe50PartesSeFirmaEnVariosLotes() {
        long configurado = limites.getMultipart().getTamanoParteBytes();
        try {
            limites.getMultipart().setTamanoParteBytes(5 * MIB);
            MultipartUploadResponse abierta = abrir();
            assertThat(abierta.getPartCount()).isEqualTo(60);
            UUID id = abierta.getAssetId();
            assertThat(enElWorkspace(() -> media.firmarPartes(id, WS_ID,
                    IntStream.rangeClosed(1, 50).boxed().toList())).getSigned()).hasSize(50);
            assertThat(enElWorkspace(() -> media.firmarPartes(id, WS_ID,
                    IntStream.rangeClosed(51, 60).boxed().toList())).getSigned()).hasSize(10);
            assertThatThrownBy(() -> enElWorkspace(() -> media.firmarPartes(id, WS_ID,
                    IntStream.rangeClosed(1, 51).boxed().toList())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hasta 50");
            verify(storage, org.mockito.Mockito.times(60))
                    .firmarParte(anyString(), anyString(), anyInt(), anyLong());
        } finally {
            limites.getMultipart().setTamanoParteBytes(configurado);
        }
    }

    @Test
    void listaVaciaSinObjetoNoConfirmaNiLiberaReserva() {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(List.of());
        when(storage.consultar(anyString())).thenReturn(null);
        assertThatThrownBy(() -> enElWorkspace(() -> media.completarPorPartes(id, WS_ID)))
                .hasMessageContaining("Faltan 19 de 19");
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(fila(id).getMultipartUploadId()).isEqualTo("subida-1");
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
    }

    @Test
    void recuperaErrorS3DeUnionSiElObjetoTieneTamanoExacto() {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(todasLasPartes());
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO, "video/mp4"));
        doThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build())
                .when(storage).completarPorPartes(anyString(), anyString(), any());
        assertThat(enElWorkspace(() -> media.completarPorPartes(id, WS_ID)).getStatus())
                .isEqualTo(MediaAssetStatus.READY);
        assertThat(fila(id).getMultipartUploadId()).isNull();
    }

    @Test
    void apagarNoImpideFirmarConsultarCompletarNiCancelarSesionesExistentes() {
        UUID completar = abrir().getAssetId();
        UUID cancelar = abrir().getAssetId();
        limites.getMultipart().setModo(MediaLimitsProperties.Multipart.Modo.APAGADA);
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(todasLasPartes());
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO, "video/mp4"));
        String claveCancelada = fila(cancelar).getStorageKey();
        when(storage.partesSubidas(eq(claveCancelada), anyString())).thenReturn(List.of());
        when(storage.consultar(claveCancelada)).thenReturn(null);

        assertThat(enElWorkspace(() -> media.firmarPartes(completar, WS_ID, List.of(1))).getSigned()).hasSize(1);
        assertThat(enElWorkspace(() -> media.partesSubidas(completar, WS_ID)).getUploaded()).hasSize(19);
        assertThat(enElWorkspace(() -> media.completarPorPartes(completar, WS_ID)).getStatus())
                .isEqualTo(MediaAssetStatus.READY);
        enElWorkspace(() -> { media.cancelarPorPartes(cancelar, WS_ID); return null; });
        assertThat(fila(cancelar).getUploadDecision()).isEqualTo(com.metricol.api.enums.UploadDecision.CANCELLED);
    }

    @Test
    void noConsultaCompletaNiCancelaOtroWorkspace() {
        UUID id = abrir().getAssetId();
        UUID otro = UUID.randomUUID();
        assertThatThrownBy(() -> enElWorkspace(() -> media.partesSubidas(id, otro)))
                .isInstanceOf(com.metricol.api.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> enElWorkspace(() -> media.completarPorPartes(id, otro)))
                .isInstanceOf(com.metricol.api.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> enElWorkspace(() -> { media.cancelarPorPartes(id, otro); return null; }))
                .isInstanceOf(com.metricol.api.exception.ResourceNotFoundException.class);
        verify(storage, never()).partesSubidas(anyString(), anyString());
        verify(storage, never()).abortarPorPartes(anyString(), anyString());
        verify(storage, never()).deleteByKey(anyString());
    }

    @Test
    void unErrorDeLecturaNoSeInterpretaComoObjetoAusente() {
        UUID id = abrir().getAssetId();
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(List.of());
        when(storage.consultar(anyString())).thenThrow(new IllegalStateException("R2 no disponible"));
        assertThatThrownBy(() -> enElWorkspace(() -> media.completarPorPartes(id, WS_ID)))
                .hasMessage("R2 no disponible");
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(fila(id).getMultipartUploadId()).isEqualTo("subida-1");
        verify(storage, never()).deleteByKey(anyString());
    }

    @Test
    @DisplayName("las partes: el tamaño configurado, nunca menos de 5 MiB ni más de 10,000 partes")
    void calculo() {
        assertThat(MediaService.tamanoDeParte(PESO, 16 * MIB)).isEqualTo(16 * MIB);
        assertThat(MediaService.tamanoDeParte(PESO, 1 * MIB)).isEqualTo(5 * MIB);
        long enorme = 200L * 1024 * MIB;
        long parte = MediaService.tamanoDeParte(enorme, 16 * MIB);
        assertThat(MediaService.totalPartes(enorme, parte)).isLessThanOrEqualTo(10_000);
        assertThat(parte % MIB).isZero();

        assertThat(MediaService.totalPartes(PESO, 16 * MIB)).isEqualTo(19);
        assertThat(MediaService.totalPartes(16 * MIB, 16 * MIB)).isEqualTo(1);
        assertThat(MediaService.totalPartes(1L, 16 * MIB)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ayudas

    private void vencer(UUID id) {
        enElWorkspace(() -> {
            MediaAsset a = assets.findById(id).orElseThrow();
            a.setUploadExpiresAt(java.time.Instant.now().minusSeconds(1));
            assets.save(a);
            return null;
        });
    }

    private MultipartUploadResponse abrir() {
        MultipartUploadResponse abierta = enElWorkspace(() -> media.iniciarPorPartes(pedido(PESO), WS_ID));
        creados.add(abierta.getAssetId());
        return abierta;
    }

    private static MediaPresignRequest pedido(long bytes) {
        MediaPresignRequest pedido = new MediaPresignRequest();
        pedido.setFileName("108907.mp4");
        pedido.setContentType("video/mp4");
        pedido.setSizeBytes(bytes);
        return pedido;
    }

    /** Las 19 partes de 300 MB: 18 de 16 MiB y la última de 12 MiB. */
    private static List<ParteSubida> todasLasPartes() {
        return IntStream.rangeClosed(1, 19)
                .mapToObj(n -> new ParteSubida(n, "\"e" + n + "\"", n < 19 ? 16 * MIB : PESO - 18 * 16 * MIB))
                .toList();
    }

    private MediaAsset fila(UUID id) {
        return enElWorkspace(() -> assets.findById(id).orElseThrow());
    }

    private <T> T enElWorkspace(Supplier<T> accion) {
        Object[] resultado = new Object[1];
        TenantIdentifierResolver.comoTenant(WS, () -> resultado[0] = accion.get());
        @SuppressWarnings("unchecked")
        T valor = (T) resultado[0];
        return valor;
    }
}
