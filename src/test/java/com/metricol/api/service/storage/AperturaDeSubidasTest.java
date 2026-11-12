package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.*;
import com.metricol.api.enums.*;
import com.metricol.api.exception.*;
import com.metricol.api.models.request.MediaPresignRequest;
import com.metricol.api.models.response.MultipartUploadResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.MediaService;
import com.metricol.api.service.media.MiniaturasEnSegundoPlano;
import software.amazon.awssdk.core.exception.SdkClientException;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000", "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000", "app.media.orphan-delay-ms=3600000",
        "app.media.rescate-delay-ms=3600000", "app.media.unused-delay-ms=3600000", "app.media.multipart.modo=TODAS"
})
class AperturaDeSubidasTest {
    private static final UUID WS = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final long BYTES = 10L * 1024 * 1024;
    @Autowired MediaService media;
    @Autowired MediaAssetRepository assets;
    @Autowired AperturaDeSubidas aperturas;
    @Autowired CoordinadorDeSubidas coordinador;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @MockitoBean R2StorageService storage;
    @MockitoBean MiniaturasEnSegundoPlano miniaturas;
    private ExecutorService executor;
    private final List<CountDownLatch> puertas = new ArrayList<>();

    @BeforeEach void preparar() {
        executor = Executors.newFixedThreadPool(2);
        when(storage.claveNueva(any(UUID.class), anyString(), anyString()))
                .thenAnswer(i -> "media/" + i.getArgument(0) + "/" + UUID.randomUUID() + ".mp4");
        when(storage.urlDe(anyString())).thenAnswer(i -> "https://cdn.test/" + i.getArgument(0));
        when(storage.tipoDe(anyString(), anyString())).thenReturn(MediaType.VIDEO);
        when(storage.iniciarPorPartes(anyString(), anyString())).thenReturn("r2-one");
        when(storage.firmarParte(anyString(), anyString(), anyInt(), anyLong())).thenReturn("https://r2.test/part");
    }
    @AfterEach void cerrar() throws Exception {
        puertas.forEach(CountDownLatch::countDown);
        executor.shutdown(); assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        sinUsuario(() -> { assets.findAll().forEach(a -> assets.deleteById(a.getId())); return null; });
        SecurityContextHolder.clearContext();
    }

    @Test void dosSolicitudesSimultaneasCreanUnaReservaYUnaApertura() throws Exception {
        MediaPresignRequest p = pedido();
        CountDownLatch listas = new CountDownLatch(2), iniciar = puerta();
        Supplier<MultipartUploadResponse> tarea = () -> {
            listas.countDown(); esperar(iniciar); return abrir(p);
        };
        Future<MultipartUploadResponse> a = executor.submit(tarea::get), b = executor.submit(tarea::get);
        esperar(listas); iniciar.countDown();
        assertThat(a.get(20, TimeUnit.SECONDS).getAssetId()).isEqualTo(b.get(20, TimeUnit.SECONDS).getAssetId());
        assertThat(reservas()).isEqualTo(1);
        assertThat(cuota()).isEqualTo(BYTES);
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
    }

    @Test void respuestaDeCreatePendientePermiteRecuperarElMismoIdSinRetenerTransaccion() throws Exception {
        MediaPresignRequest p = pedido(); CountDownLatch dentro = puerta(), continuar = puerta();
        when(storage.iniciarPorPartes(anyString(), anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(assets.findByClientUploadId(p.getClientUploadId())).isPresent();
            dentro.countDown(); esperar(continuar); return "r2-one";
        });
        Future<MultipartUploadResponse> primera = executor.submit(() -> abrir(p)); esperar(dentro);
        MultipartUploadResponse segunda = abrir(p);
        assertThat(segunda.getEstado()).isEqualTo("OPENING");
        continuar.countDown();
        assertThat(primera.get(20, TimeUnit.SECONDS).getAssetId()).isEqualTo(segunda.getAssetId());
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
    }

    enum Cambio { NOMBRE, BYTES, MIME, HUELLA, LOTE, INDICE, VERSION, MODALIDAD }
    @ParameterizedTest @EnumSource(Cambio.class)
    void mismaClaveConOtraIdentidadNoReutilizaNiReserva(Cambio cambio) {
        MediaPresignRequest p = pedido(); abrir(p);
        switch (cambio) {
            case NOMBRE -> p.setFileName("otro.mp4");
            case BYTES -> p.setSizeBytes(BYTES + 1);
            case MIME -> p.setContentType("video/quicktime");
            case HUELLA -> p.setHuellaArchivo("b".repeat(64));
            case LOTE -> p.setUploadBatchId(UUID.randomUUID());
            case INDICE -> p.setUploadBatchIndex(1);
            case VERSION, MODALIDAD -> p.setUploadProtocolVersion(1);
        }
        assertThatThrownBy(() -> como(USER, () -> cambio == Cambio.MODALIDAD
                ? media.presign(p, WS) : media.iniciarPorPartes(p, WS)))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("identidad");
        assertThat(reservas()).isEqualTo(1);
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
    }

    @Test void otroUsuarioDelMismoNegocioNoRecuperaNiModificaLaSesion() {
        MediaPresignRequest p = pedido(); UUID id = abrir(p).getAssetId(); UUID otro = UUID.randomUUID();
        assertThatThrownBy(() -> como(otro, () -> media.iniciarPorPartes(p, WS))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> como(otro, () -> media.estadoDeSubida(p.getClientUploadId(), WS))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> como(otro, () -> media.firmarPartes(id, WS, List.of(1)))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> como(otro, () -> media.partesSubidas(id, WS))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> como(otro, () -> media.confirm(id))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> como(otro, () -> { coordinador.cancelar(id, WS); return null; })).isInstanceOf(ResourceNotFoundException.class);
        verify(storage, never()).abortarPorPartes(anyString(), anyString());
        verify(storage, never()).partesSubidas(anyString(), anyString());
    }

    @Test void respuestaPerdidaSeReconcilaPorClaveSinOtroCreate() {
        MediaPresignRequest p = pedido();
        when(storage.iniciarPorPartes(anyString(), anyString())).thenThrow(SdkClientException.create("test response lost"));
        assertThatThrownBy(() -> abrir(p)).isInstanceOf(SdkClientException.class);
        MediaAsset pendiente = fila(p);
        assertThat(pendiente.getUploadOpeningState()).isEqualTo("SENT");
        assertThat(pendiente.getMultipartUploadId()).isNull();
        when(storage.aperturasPorClave(pendiente.getStorageKey())).thenReturn(List.of("r2-recovered"));
        MultipartUploadResponse recuperada = abrir(p);
        assertThat(recuperada.getAssetId()).isEqualTo(pendiente.getId());
        assertThat(recuperada.getEstado()).isEqualTo("PENDING");
        assertThat(fila(p).getMultipartUploadId()).isEqualTo("r2-recovered");
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
        assertThat(cuota()).isEqualTo(BYTES);
    }

    @Test void aperturaAmbiguaNoSeRecreaNiLiberaPorSuponerAusencia() {
        MediaPresignRequest p = pedido();
        when(storage.iniciarPorPartes(anyString(), anyString())).thenThrow(SdkClientException.create("test timeout"));
        assertThatThrownBy(() -> abrir(p)).isInstanceOf(SdkClientException.class);
        assertThat(abrir(p).getEstado()).isEqualTo("OPENING");
        UUID id = fila(p).getId();
        assertThatThrownBy(() -> como(USER, () -> { coordinador.cancelar(id, WS); return null; }))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("reconciliando");
        assertThat(fila(p).getUploadDecision()).isEqualTo(UploadDecision.CANCELLING);
        assertThat(como(USER, () -> media.estadoDeSubida(p.getClientUploadId(), WS)).getEstado()).isEqualTo("CANCELLING");
        assertThat(cuota()).isEqualTo(BYTES);
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
        verify(storage, never()).deleteByKey(anyString());
    }

    @Test void cancelarDuranteCreateSeRecuperaSinResucitarLaSesion() throws Exception {
        MediaPresignRequest p = pedido(); CountDownLatch dentro = puerta(), continuar = puerta();
        when(storage.iniciarPorPartes(anyString(), anyString())).thenAnswer(i -> {
            dentro.countDown(); esperar(continuar); return "r2-one";
        });
        Future<MultipartUploadResponse> creacion = executor.submit(() -> abrir(p)); esperar(dentro);
        UUID id = fila(p).getId();
        assertThatThrownBy(() -> como(USER, () -> { coordinador.cancelar(id, WS); return null; }))
                .isInstanceOf(ConflictoException.class);
        continuar.countDown(); assertThat(creacion.get(20, TimeUnit.SECONDS).getEstado()).isEqualTo("CANCELLING");
        sinUsuario(() -> { coordinador.recuperar(id, WS); return null; });
        assertThat(fila(p).getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        assertThat(cuota()).isZero();
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
    }

    @Test void plazosYVersionSeSerializanEnUtcSinPublicarCapacidadMovil() throws Exception {
        MediaPresignRequest p = pedido(); MultipartUploadResponse abierta = abrir(p);
        assertThat(abierta.getProtocolVersion()).isEqualTo(2);
        assertThat(abierta.getExpiresAt()).isAfter(Instant.now().plusSeconds(23 * 3600));
        var partes = como(USER, () -> media.firmarPartes(abierta.getAssetId(), WS, List.of(1)));
        assertThat(partes.getExpiresAt()).isEqualTo(abierta.getExpiresAt());
        assertThat(partes.getSignedExpiresAt()).isBefore(partes.getExpiresAt());
        assertThat(partes.getSigned().get(0).getExpiresAt()).isEqualTo(partes.getSignedExpiresAt());
        var json = mapper.readTree(mapper.writeValueAsString(abierta));
        assertThat(json.get("expiresAt").asText()).endsWith("Z");
        assertThat(json.has("uploadOwnerId")).isFalse(); assertThat(json.has("uploadIdentityHash")).isFalse();
        var uso = como(USER, media::usage);
        assertThat(uso.getMultipartSessionVersion()).isEqualTo(2);
        assertThat(uso.isMultipartMobileReady()).isFalse();
        assertThat(uso.getSubidaEnFondo()).isEqualTo(1);
    }

    @Test void identidadOriginalSeConservaCuandoElMedioListoCambioDeTamano() {
        MediaPresignRequest p = pedido(); UUID id = abrir(p).getAssetId();
        editar(id, a -> { a.setStatus(MediaAssetStatus.READY); a.setSizeBytes(BYTES + 1); });
        assertThat(abrir(p).getEstado()).isEqualTo("READY");
        assertThat(abrir(p).getSizeBytes()).isEqualTo(BYTES);
        var estado = como(USER, () -> media.estadoDeSubida(p.getClientUploadId(), WS));
        assertThat(estado.getSizeBytes()).isEqualTo(BYTES);
        assertThat(estado.getNextCheckAt()).isNull();
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
    }

    @Test void v2RechazaCamposIncompletosAntesDeReservar() {
        MediaPresignRequest p = pedido(); p.setHuellaArchivo(null);
        assertThatThrownBy(() -> abrir(p)).isInstanceOf(IllegalArgumentException.class);
        p.setHuellaArchivo("a".repeat(64)); p.setUploadBatchId(null);
        assertThatThrownBy(() -> abrir(p)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sinUsuario(() -> media.iniciarPorPartes(pedido(), WS)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reservas()).isZero(); verify(storage, never()).iniciarPorPartes(anyString(), anyString());
    }

    @Test void sobrantesDeAperturaSeAbortanAntesDeEntregarFirmasYSeReintentan() {
        MediaPresignRequest p = pedido();
        when(storage.iniciarPorPartes(anyString(), anyString())).thenThrow(SdkClientException.create("test timeout"));
        assertThatThrownBy(() -> abrir(p)).isInstanceOf(SdkClientException.class);
        when(storage.aperturasPorClave(anyString())).thenReturn(List.of("a-selected", "z-extra"));
        doThrow(new IllegalStateException("test abort failure")).doNothing()
                .when(storage).abortarPorPartes(anyString(), eq("z-extra"));
        assertThatThrownBy(() -> abrir(p)).hasMessage("test abort failure");
        assertThat(fila(p).getUploadOpeningState()).isEqualTo("SENT");
        assertThatThrownBy(() -> como(USER, () -> media.firmarPartes(fila(p).getId(), WS, List.of(1))))
                .isInstanceOf(ConflictoException.class);
        assertThat(abrir(p).getEstado()).isEqualTo("PENDING");
        assertThat(fila(p).getMultipartUploadId()).isEqualTo("a-selected");
        verify(storage, times(2)).abortarPorPartes(anyString(), eq("z-extra"));
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
    }

    @Test void sesionHeredadaSeRecuperaEnV1SinApropiarlaComoV2() {
        MediaPresignRequest p = pedido(); UUID id = abrir(p).getAssetId();
        editar(id, a -> { a.setUploadIdentityHash(null); a.setUploadOwnerId(null); a.setUploadProtocolVersion(null); });
        p.setUploadProtocolVersion(1);
        assertThat(abrir(p).getAssetId()).isEqualTo(id);
        p.setUploadProtocolVersion(2);
        assertThatThrownBy(() -> abrir(p)).isInstanceOf(ConflictoException.class).hasMessageContaining("anterior");
        assertThat(fila(p).getUploadOwnerId()).isNull();
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
    }

    @Test void usuarioDeOtroNegocioNoPuedeUsarElParametroWorkspace() {
        var previo = SecurityContextHolder.getContext(); var contexto = SecurityContextHolder.createEmptyContext();
        User u = new User(); u.setId(USER); Workspace otro = new Workspace(); otro.setId(UUID.randomUUID()); u.setWorkspace(otro);
        contexto.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(contexto);
        try {
            assertThatThrownBy(() -> tenant(() -> media.iniciarPorPartes(pedido(), WS))).isInstanceOf(ResourceNotFoundException.class);
        } finally { SecurityContextHolder.setContext(previo); }
        assertThat(reservas()).isZero(); verify(storage, never()).iniciarPorPartes(anyString(), anyString());
    }

    @Test void caidaAntesDeAdmitirCreateSeRecuperaDesdeLaReserva() {
        MediaPresignRequest p = pedido(); UUID id = reservarSinEnviar(p);
        assertThatThrownBy(() -> sinUsuario(() -> { coordinador.recuperar(id, WS); return null; }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Faltan");
        assertThat(fila(p).getUploadOpeningState()).isEqualTo("ATTACHED");
        assertThat(fila(p).getUploadOwnerId()).isEqualTo(USER);
        assertThat(abrir(p).getAssetId()).isEqualTo(id);
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
    }

    @Test void cancelarReservaSinEnvioNoAbreR2Despues() {
        MediaPresignRequest p = pedido(); UUID id = reservarSinEnviar(p);
        como(USER, () -> { coordinador.cancelar(id, WS); return null; });
        assertThat(fila(p).getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        assertThatThrownBy(() -> abrir(p)).isInstanceOf(ConflictoException.class);
        assertThat(cuota()).isZero(); verify(storage, never()).iniciarPorPartes(anyString(), anyString());
    }

    private UUID reservarSinEnviar(MediaPresignRequest p) {
        return como(USER, () -> {
            MediaAsset a = MediaAsset.builder().fileName(p.getFileName()).clientUploadId(p.getClientUploadId())
                    .uploadBatchId(p.getUploadBatchId()).uploadBatchIndex(p.getUploadBatchIndex())
                    .contentType(p.getContentType()).huellaArchivo(p.getHuellaArchivo()).sizeBytes(BYTES)
                    .storageKey("media/" + WS + "/" + UUID.randomUUID()).url("https://cdn.test/reserved")
                    .type(MediaType.VIDEO).status(MediaAssetStatus.PENDING).multipartPartSize(16L * 1024 * 1024)
                    .uploadExpiresAt(Instant.now().plusSeconds(86400)).uploadOpeningState("RESERVED").build();
            IdentidadDeSubida.guardar(a, p, WS, true, p.getContentType());
            return assets.saveAndFlush(a).getId();
        });
    }

    private long reservas() { return sinUsuario(assets::count); }
    private long cuota() { return sinUsuario(assets::espacioUsado); }
    private MediaPresignRequest pedido() {
        MediaPresignRequest p = new MediaPresignRequest(); p.setUploadProtocolVersion(2);
        p.setClientUploadId("v2_" + UUID.randomUUID()); p.setUploadBatchId(UUID.randomUUID()); p.setUploadBatchIndex(0);
        p.setFileName("source.mp4"); p.setContentType("video/mp4"); p.setSizeBytes(BYTES);
        p.setHuellaArchivo("a".repeat(64)); p.setAunqueRepetido(true); return p;
    }
    private MultipartUploadResponse abrir(MediaPresignRequest p) { return como(USER, () -> media.iniciarPorPartes(p, WS)); }
    private MediaAsset fila(MediaPresignRequest p) { return sinUsuario(() -> assets.findByClientUploadId(p.getClientUploadId()).orElseThrow()); }
    private void editar(UUID id, Consumer<MediaAsset> cambio) {
        sinUsuario(() -> new TransactionTemplate(manager).execute(s -> {
            MediaAsset a = assets.findForUploadUpdate(id).orElseThrow(); cambio.accept(a); return assets.save(a);
        }));
    }
    private CountDownLatch puerta() { CountDownLatch p = new CountDownLatch(1); puertas.add(p); return p; }
    private void esperar(CountDownLatch p) { try { assertThat(p.await(20, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); } }
    private <T> T como(UUID usuario, Supplier<T> operacion) {
        var previo = SecurityContextHolder.getContext(); var contexto = SecurityContextHolder.createEmptyContext();
        Workspace ws = new Workspace(); ws.setId(WS); User u = new User(); u.setId(usuario); u.setWorkspace(ws);
        contexto.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(contexto);
        try { return tenant(operacion); } finally { SecurityContextHolder.setContext(previo); }
    }
    private <T> T sinUsuario(Supplier<T> operacion) {
        var previo = SecurityContextHolder.getContext(); SecurityContextHolder.clearContext();
        try { return tenant(operacion); } finally { SecurityContextHolder.setContext(previo); }
    }
    private <T> T tenant(Supplier<T> operacion) {
        Object[] valor = new Object[1]; TenantIdentifierResolver.comoTenant(WS.toString(), () -> valor[0] = operacion.get());
        @SuppressWarnings("unchecked") T resultado = (T) valor[0]; return resultado;
    }
}
