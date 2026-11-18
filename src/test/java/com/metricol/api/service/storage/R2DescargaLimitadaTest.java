package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metricol.api.config.R2Properties;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class R2DescargaLimitadaTest {
    @TempDir Path folder;
    private final S3Client s3 = mock(S3Client.class);

    private R2StorageService storage() {
        var props = new R2Properties();
        props.setEndpoint("https://r2.example.invalid");
        props.setAccessKey("test");
        props.setBucket("test");
        props.setPublicUrl("https://media.example.invalid");
        return new R2StorageService(s3, mock(S3Presigner.class), props);
    }

    private void response(Long declaredBytes, byte[] bytes) {
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(declaredBytes).build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes))));
    }

    @Test
    void descargaExactaDentroDelLimite() throws Exception {
        response(4L, new byte[]{1, 2, 3, 4});
        Path out = folder.resolve("original.bin");
        assertThat(storage().descargarLimitado("key", out, 4)).isTrue();
        assertThat(Files.readAllBytes(out)).containsExactly(1, 2, 3, 4);
    }

    @Test
    void rechazaCabeceraQueExcedeElLimiteAntesDeEscribir() {
        response(100L, new byte[100]);
        Path out = folder.resolve("original.bin");
        assertThatIllegalArgumentException().isThrownBy(() -> storage().descargarLimitado("key", out, 4));
        assertThat(out).doesNotExist();
    }

    @Test
    void noConfiaEnCabeceraAusenteOMenorAlCuerpo() throws Exception {
        for (Long declared : new Long[]{null, 1L}) {
            response(declared, new byte[100]);
            Path out = folder.resolve("original.bin");
            assertThatIllegalArgumentException().isThrownBy(() -> storage().descargarLimitado("key", out, 4));
            assertThat(Files.size(out)).isLessThanOrEqualTo(4);
        }
    }

    @Test
    void objetoAusenteNoSeConfundeConAnalisisCompletado() {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertThat(storage().descargarLimitado("key", folder.resolve("original.bin"), 4)).isFalse();
    }
}
