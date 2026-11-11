package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.models.request.MediaPresignRequest;
import com.metricol.api.models.response.MediaPresignResponse;
import com.metricol.api.models.response.MultipartUploadResponse;
import com.metricol.api.models.response.SubidaEstadoResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.media.MiniaturasEnSegundoPlano;
import com.metricol.api.service.storage.R2StorageService;

/**
 * La subida en segundo plano del teléfono: la misma clave es la misma subida, la firma dura horas,
 * la app pregunta en qué quedó y la limpieza no la tira a la hora.
 *
 * <p>R2 va doblado, como en {@link SubidaPorPartesTest}: el {@code .env} local apunta al bucket de
 * producción.
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
class SubidaEnFondoTest {

    private static final String WS = "66666666-6666-6666-6666-666666666666";
    private static final UUID WS_ID = UUID.fromString(WS);
    private static final long PESO = 40L * 1024 * 1024;

    @Autowired
    private MediaService media;

    @Autowired
    private MediaAssetRepository assets;

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
        when(storage.ttlSegundos()).thenReturn(1800L);
        when(storage.firmarSubida(anyString(), anyString(), anyLong()))
                .thenAnswer(i -> "https://r2.test/put?ttl=" + i.getArgument(2));
        when(storage.iniciarPorPartes(anyString(), anyString())).thenReturn("subida-1");
        when(storage.firmarParte(anyString(), anyString(), anyInt(), anyLong())).thenReturn("https://r2.test/parte");
    }

    @AfterEach
    void limpiar() {
        enElWorkspace(() -> {
            creados.forEach(id -> assets.findById(id).ifPresent(assets::delete));
            return null;
        });
    }

    @Test
    @DisplayName("pedir dos veces con la misma clave devuelve la misma subida, firmada por horas")
    void mismaClaveMismaSubida() {
        String clave = clave();
        MediaPresignResponse primera = pedir(pedido(clave, PESO));
        MediaPresignResponse segunda = pedir(pedido(clave, PESO));

        assertThat(segunda.getAssetId()).isEqualTo(primera.getAssetId());
        assertThat(primera.getExpiresInSeconds()).isEqualTo(MediaService.FIRMA_EN_FONDO_SEGUNDOS);
        assertThat(segunda.getUploadUrl()).contains("ttl=" + MediaService.FIRMA_EN_FONDO_SEGUNDOS);
        assertThat(segunda.getEstado()).isEqualTo("PENDING");
        assertThat(fila(primera.getAssetId()).getClientUploadId()).isEqualTo(clave);
        verify(storage, times(1)).claveNueva(any(UUID.class), anyString(), anyString());
    }

    @Test
    @DisplayName("sin clave todo sigue como antes: firma corta y una fila por petición")
    void sinClaveComoAntes() {
        MediaPresignResponse una = pedir(pedido(null, PESO));
        MediaPresignResponse otra = pedir(pedido(null, PESO + 1));

        assertThat(una.getAssetId()).isNotEqualTo(otra.getAssetId());
        assertThat(una.getExpiresInSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("la misma clave con otro archivo es un conflicto, no la subida de otro")
    void claveConOtroArchivo() {
        String clave = clave();
        pedir(pedido(clave, PESO));

        assertThatThrownBy(() -> pedir(pedido(clave, PESO + 10)))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("otro archivo");
    }

    @Test
    @DisplayName("la app pregunta: no existe, pendiente, y lista cuando el archivo ya está en R2")
    void preguntaEnQueQuedo() {
        assertThat(estado("nunca-pedida-123").getEstado()).isEqualTo("NO_EXISTE");

        String clave = clave();
        UUID id = pedir(pedido(clave, PESO)).getAssetId();
        when(storage.consultar(anyString())).thenReturn(null);
        SubidaEstadoResponse pendiente = estado(clave);
        assertThat(pendiente.getEstado()).isEqualTo("PENDING");
        assertThat(pendiente.getAssetId()).isEqualTo(id);

        // Android terminó con la app cerrada: al preguntar, se confirma ahí mismo.
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO, "video/mp4"));
        SubidaEstadoResponse lista = estado(clave);
        assertThat(lista.getEstado()).isEqualTo("READY");
        assertThat(lista.getMedia()).isNotNull();
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.READY);

        // Y pedir permiso otra vez con esa clave ya no manda a subir nada.
        MediaPresignResponse otraVez = pedir(pedido(clave, PESO));
        assertThat(otraVez.getEstado()).isEqualTo("READY");
        assertThat(otraVez.getUploadUrl()).isNull();
        assertThat(otraVez.getAssetId()).isEqualTo(id);
    }

    @Test
    @DisplayName("una subida incompleta no se confirma al preguntar")
    void incompletaSigue() {
        String clave = clave();
        pedir(pedido(clave, PESO));
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(PESO / 2, "video/mp4"));

        assertThat(estado(clave).getEstado()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("la clave de otro negocio no se ve desde este")
    void otroNegocio() {
        String clave = clave();
        pedir(pedido(clave, PESO));

        SubidaEstadoResponse desdeOtro = enElWorkspace(() -> media.estadoDeSubida(clave, UUID.randomUUID()));
        assertThat(desdeOtro.getEstado()).isEqualTo("NO_EXISTE");
    }

    @Test
    @DisplayName("por partes: abrir dos veces con la misma clave abre una sola subida en R2")
    void porPartesIdempotente() {
        String clave = clave();
        MultipartUploadResponse una = enElWorkspace(() -> media.iniciarPorPartes(pedido(clave, 300L * 1024 * 1024), WS_ID));
        creados.add(una.getAssetId());
        MultipartUploadResponse otra = enElWorkspace(() -> media.iniciarPorPartes(pedido(clave, 300L * 1024 * 1024), WS_ID));

        assertThat(otra.getAssetId()).isEqualTo(una.getAssetId());
        assertThat(otra.getPartCount()).isEqualTo(una.getPartCount());
        verify(storage, times(1)).iniciarPorPartes(anyString(), eq("video/mp4"));
    }

    @Test
    @DisplayName("la limpieza espera un día a la subida en segundo plano, y una hora a la de siempre")
    void limpiezaEspera() {
        UUID enFondo = pedir(pedido(clave(), PESO)).getAssetId();
        UUID deSiempre = pedir(pedido(null, PESO + 1)).getAssetId();
        LocalDateTime haceDosHoras = LocalDateTime.now().minusHours(2);
        enElWorkspace(() -> {
            for (UUID id : List.of(enFondo, deSiempre)) {
                MediaAsset a = assets.findById(id).orElseThrow();
                a.setUploadRenewedAt(haceDosHoras);
                assets.save(a);
            }
            return null;
        });

        LocalDateTime ahora = LocalDateTime.now();
        List<String> viejas = assets.findPrefirmadosViejos(ahora.minusMinutes(60), ahora.minusMinutes(1440), 1000)
                .stream().map(f -> String.valueOf(f[0])).toList();

        assertThat(viejas).contains(deSiempre.toString()).doesNotContain(enFondo.toString());
    }

    // ------------------------------------------------------------------ ayudas

    private MediaPresignResponse pedir(MediaPresignRequest pedido) {
        MediaPresignResponse r = enElWorkspace(() -> media.presign(pedido, WS_ID));
        if (!creados.contains(r.getAssetId())) creados.add(r.getAssetId());
        return r;
    }

    private SubidaEstadoResponse estado(String clave) {
        return enElWorkspace(() -> media.estadoDeSubida(clave, WS_ID));
    }

    private static String clave() {
        return "t" + UUID.randomUUID().toString().replace("-", "");
    }

    private static MediaPresignRequest pedido(String clave, long bytes) {
        MediaPresignRequest pedido = new MediaPresignRequest();
        pedido.setFileName("fondo.mp4");
        pedido.setContentType("video/mp4");
        pedido.setSizeBytes(bytes);
        pedido.setClientUploadId(clave);
        pedido.setAunqueRepetido(true);
        return pedido;
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
