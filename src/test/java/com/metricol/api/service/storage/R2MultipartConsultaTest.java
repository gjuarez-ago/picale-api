package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.metricol.api.config.R2Properties;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Consulta real del adaptador con SDK simulado: sin Spring, red ni credenciales reales. */
class R2MultipartConsultaTest {

    private S3Client s3;
    private R2StorageService storage;

    @BeforeEach
    void preparar() {
        s3 = mock(S3Client.class);
        R2Properties props = new R2Properties();
        props.setEndpoint("https://r2.example.test");
        props.setAccessKey("test-access");
        props.setSecretKey("test-secret");
        props.setBucket("test-bucket");
        props.setPublicUrl("https://cdn.example.test");
        storage = new R2StorageService(s3, mock(S3Presigner.class), props);
    }

    @Test
    void paginaConLaMismaIdentidadYOrdenaTodasLasPartes() {
        when(s3.listParts(any(ListPartsRequest.class)))
                .thenReturn(ListPartsResponse.builder().parts(parte(2), parte(1))
                        .isTruncated(true).nextPartNumberMarker(2).build())
                .thenReturn(ListPartsResponse.builder().parts(parte(3)).isTruncated(false).build());

        assertThat(storage.partesSubidas("media/test.mp4", "upload-test"))
                .extracting(R2StorageService.ParteSubida::numero).containsExactly(1, 2, 3);
        ArgumentCaptor<ListPartsRequest> pedidos = ArgumentCaptor.forClass(ListPartsRequest.class);
        verify(s3, times(2)).listParts(pedidos.capture());
        assertThat(pedidos.getAllValues()).extracting(ListPartsRequest::partNumberMarker)
                .containsExactly(null, 2);
        assertThat(pedidos.getAllValues()).allSatisfy(p -> {
            assertThat(p.bucket()).isEqualTo("test-bucket");
            assertThat(p.key()).isEqualTo("media/test.mp4");
            assertThat(p.uploadId()).isEqualTo("upload-test");
        });
    }

    @Test
    void sesionInexistenteDevuelveVacio() {
        when(s3.listParts(any(ListPartsRequest.class)))
                .thenThrow(NoSuchUploadException.builder().statusCode(404).build());
        assertThat(storage.partesSubidas("key", "upload")).isEmpty();
    }

    @Test
    void sesionQueDesapareceEntrePaginasNoDevuelveUnaListaParcial() {
        when(s3.listParts(any(ListPartsRequest.class)))
                .thenReturn(ListPartsResponse.builder().parts(parte(1))
                        .isTruncated(true).nextPartNumberMarker(1).build())
                .thenThrow(NoSuchUploadException.builder().statusCode(404).build());
        assertThat(storage.partesSubidas("key", "upload")).isEmpty();
    }

    @Test
    void errorS3EnOtraPaginaNoDevuelveExitoParcialNiAusencia() {
        var fallo = S3Exception.builder().statusCode(503).build();
        when(s3.listParts(any(ListPartsRequest.class)))
                .thenReturn(ListPartsResponse.builder().parts(parte(1))
                        .isTruncated(true).nextPartNumberMarker(1).build())
                .thenThrow(fallo);
        assertThatThrownBy(() -> storage.partesSubidas("key", "upload")).isSameAs(fallo);
    }

    @Test
    void errorDeTransporteNoSeConvierteEnListaVacia() {
        SdkClientException fallo = SdkClientException.create("test transport failure");
        when(s3.listParts(any(ListPartsRequest.class))).thenThrow(fallo);
        assertThatThrownBy(() -> storage.partesSubidas("key", "upload")).isSameAs(fallo);
    }

    @Test
    void paginaTruncadaSinMarcadorFallaSinRepetirse() {
        AtomicInteger llamadas = new AtomicInteger();
        when(s3.listParts(any(ListPartsRequest.class))).thenAnswer(invocacion -> {
            if (llamadas.incrementAndGet() > 1) {
                throw new AssertionError("La consulta repitió una página sin marcador");
            }
            return ListPartsResponse.builder().parts(parte(1)).isTruncated(true).build();
        });
        assertThatThrownBy(() -> storage.partesSubidas("key", "upload"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("paginación");
        verify(s3).listParts(any(ListPartsRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 2, 10001})
    void marcadorDebeAvanzarDentroDelRangoDePartes(int siguiente) {
        AtomicInteger llamadas = new AtomicInteger();
        when(s3.listParts(any(ListPartsRequest.class))).thenAnswer(invocacion -> {
            int numero = llamadas.incrementAndGet();
            if (numero > 2) {
                throw new AssertionError("La consulta continuó con un marcador inválido");
            }
            return ListPartsResponse.builder().parts(List.of(parte(numero)))
                    .isTruncated(true).nextPartNumberMarker(numero == 1 ? 2 : siguiente).build();
        });
        assertThatThrownBy(() -> storage.partesSubidas("key", "upload"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("paginación");
        verify(s3, times(2)).listParts(any(ListPartsRequest.class));
    }

    private static Part parte(int numero) {
        return Part.builder().partNumber(numero).eTag("etag-" + numero).size(5L * 1024 * 1024).build();
    }
}
