package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.metricol.api.config.R2Config;
import com.metricol.api.config.R2Properties;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class R2AperturaTest {
    @Test void createUsaElClienteSinReintentos() {
        S3Client normal = mock(S3Client.class), apertura = mock(S3Client.class);
        when(apertura.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("created").build());
        R2StorageService storage = new R2StorageService(normal, mock(S3Presigner.class), props(), apertura);
        assertThat(storage.iniciarPorPartes("key", "video/mp4")).isEqualTo("created");
        verify(normal, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        verify(apertura).createMultipartUpload(argThat((CreateMultipartUploadRequest r) ->
                r.bucket().equals("test") && r.key().equals("key") && r.contentType().equals("video/mp4")));
    }

    @Test void configuracionRealDelSdkNoReintentaCreateNiEsperaIndefinidamente() {
        R2Config config = new R2Config();
        try (S3Client cliente = config.r2OpeningClient(props()); S3Client normal = config.r2Client(props())) {
            var opciones = cliente.serviceClientConfiguration().overrideConfiguration();
            assertThat(opciones.retryPolicy().orElseThrow().numRetries()).isZero();
            assertThat(opciones.apiCallTimeout()).contains(Duration.ofMinutes(2));
            assertThat(opciones.apiCallAttemptTimeout()).contains(Duration.ofMinutes(2));
            assertThat(normal.serviceClientConfiguration().overrideConfiguration().retryPolicy()).isEmpty();
        }
    }

    @Test void listaPaginaPorClaveExactaSinAdoptarElPrefijoVecino() {
        S3Client s3 = mock(S3Client.class); R2StorageService storage = storage(s3);
        when(s3.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                .thenReturn(ListMultipartUploadsResponse.builder()
                        .uploads(upload("key-other", "foreign"), upload("key", "z"))
                        .isTruncated(true).nextKeyMarker("key").nextUploadIdMarker("z").build())
                .thenReturn(ListMultipartUploadsResponse.builder().uploads(upload("key", "a"), upload("key", "a"))
                        .isTruncated(false).build());
        assertThat(storage.aperturasPorClave("key")).containsExactly("a", "z");
        var pedidos = org.mockito.ArgumentCaptor.forClass(ListMultipartUploadsRequest.class);
        verify(s3, times(2)).listMultipartUploads(pedidos.capture());
        assertThat(pedidos.getAllValues()).allSatisfy(r -> {
            assertThat(r.bucket()).isEqualTo("test"); assertThat(r.prefix()).isEqualTo("key");
        });
        assertThat(pedidos.getAllValues().get(1).keyMarker()).isEqualTo("key");
        assertThat(pedidos.getAllValues().get(1).uploadIdMarker()).isEqualTo("z");
    }

    @Test void paginaTruncadaSinCursorSeDetiene() {
        S3Client s3 = mock(S3Client.class);
        AtomicInteger llamadas = new AtomicInteger();
        when(s3.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenAnswer(i -> {
            if (llamadas.incrementAndGet() > 1) throw new AssertionError("No debe repetir la página");
            return ListMultipartUploadsResponse.builder().isTruncated(true).build();
        });
        assertThatThrownBy(() -> storage(s3).aperturasPorClave("key")).isInstanceOf(IllegalStateException.class);
        verify(s3).listMultipartUploads(any(ListMultipartUploadsRequest.class));
    }

    @Test void cursorRepetidoSeDetiene() {
        S3Client s3 = mock(S3Client.class); AtomicInteger llamadas = new AtomicInteger();
        when(s3.listMultipartUploads(any(ListMultipartUploadsRequest.class))).thenAnswer(i -> {
            if (llamadas.incrementAndGet() > 2) throw new AssertionError("No debe repetir el cursor");
            return ListMultipartUploadsResponse.builder().isTruncated(true).nextKeyMarker("key").nextUploadIdMarker("id").build();
        });
        assertThatThrownBy(() -> storage(s3).aperturasPorClave("key")).isInstanceOf(IllegalStateException.class);
        verify(s3, times(2)).listMultipartUploads(any(ListMultipartUploadsRequest.class));
    }

    @Test void errorDeOtraPaginaNoDevuelveExitoParcialNiAusencia() {
        S3Client s3 = mock(S3Client.class); var fallo = S3Exception.builder().statusCode(503).build();
        when(s3.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                .thenReturn(ListMultipartUploadsResponse.builder().uploads(upload("key", "id"))
                        .isTruncated(true).nextKeyMarker("key").nextUploadIdMarker("id").build())
                .thenThrow(fallo);
        assertThatThrownBy(() -> storage(s3).aperturasPorClave("key")).isSameAs(fallo);
    }
    private R2StorageService storage(S3Client s3) { return new R2StorageService(s3, mock(S3Presigner.class), props()); }
    private MultipartUpload upload(String key, String id) { return MultipartUpload.builder().key(key).uploadId(id).build(); }
    private R2Properties props() {
        R2Properties p = new R2Properties(); p.setEndpoint("https://r2.example.test");
        p.setAccessKey("test-access"); p.setSecretKey("test-secret"); p.setBucket("test"); p.setPublicUrl("https://cdn.example.test"); return p;
    }
}
