package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.UploadDecision;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.MediaPresignRequest;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.MediaService;
import com.metricol.api.service.media.MiniaturasEnSegundoPlano;

import software.amazon.awssdk.core.exception.SdkClientException;

/** Concurrencia real de transacciones H2, sesiones y coordinadores independientes; R2 simulado. */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000", "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000", "app.media.orphan-delay-ms=3600000",
        "app.media.rescate-delay-ms=3600000", "app.media.unused-delay-ms=3600000",
        "app.media.multipart.modo=TODAS"
})
class CoordinadorDeSubidasTest {
    private static final String WS = "99999999-9999-9999-9999-999999999999";
    private static final UUID WORKSPACE = UUID.fromString(WS);
    private static final long BYTES = 10L * 1024 * 1024;
    @Autowired MediaService media;
    @Autowired CoordinadorDeSubidas coordinador;
    @Autowired MediaAssetReconciler limpieza;
    @Autowired MediaAssetRepository assets;
    @Autowired MediaLimitsProperties limites;
    @Autowired PlatformTransactionManager manager;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @MockitoBean R2StorageService storage;
    @MockitoBean MiniaturasEnSegundoPlano miniaturas;
    private final List<UUID> creados = new ArrayList<>();
    private final List<CountDownLatch> puertas = new ArrayList<>();
    private ExecutorService executor;

    @BeforeEach
    void preparar() {
        executor = Executors.newFixedThreadPool(2);
        when(storage.claveNueva(any(UUID.class), anyString(), anyString()))
                .thenAnswer(i -> "media/" + WS + "/" + UUID.randomUUID() + ".mp4");
        when(storage.urlDe(anyString())).thenAnswer(i -> "https://cdn.test/" + i.getArgument(0));
        when(storage.tipoDe(anyString(), anyString())).thenReturn(MediaType.VIDEO);
        when(storage.iniciarPorPartes(anyString(), anyString())).thenReturn("multipart-test");
        when(storage.firmarParte(anyString(), anyString(), anyInt(), anyLong())).thenReturn("https://r2.test/part");
    }

    @AfterEach
    void cerrar() throws Exception {
        puertas.forEach(CountDownLatch::countDown);
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        enWorkspace(() -> { creados.forEach(assets::deleteById); return null; });
    }

    @Test
    void cancelarGanaYLaDecisionSeConservaSinLiberarAntesDelAborto() throws Exception {
        UUID id = abrir();
        CountDownLatch dentro = puerta(), continuar = puerta();
        doAnswer(i -> { sinTransaccion(); dentro.countDown(); esperar(continuar); return null; })
                .when(storage).abortarPorPartes(anyString(), anyString());
        Future<Void> cancelacion = async(() -> { coordinador.cancelar(id, WORKSPACE); return null; });
        esperar(dentro);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLING);
        assertThat(enWorkspace(assets::espacioUsado)).isEqualTo(BYTES);
        assertThatThrownBy(() -> enWorkspace(() -> media.completarPorPartes(id, WORKSPACE)))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("cancelando");
        continuar.countDown();
        cancelacion.get(20, TimeUnit.SECONDS);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.RELEASED);
        assertThat(enWorkspace(assets::espacioUsado)).isZero();
        enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; });
        verify(storage, times(1)).abortarPorPartes(anyString(), anyString());
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
        verify(storage, never()).deleteByKey(anyString());
        assertThat(enWorkspace(() -> media.estadoDeSubida(fila(id).getClientUploadId(), WORKSPACE)).getEstado())
                .isEqualTo("CANCELLED");
    }

    @Test
    void completarGanaYCancelarNoBorraElResultadoNiDuplicaEfectos() throws Exception {
        UUID id = abrir();
        completas();
        CountDownLatch dentro = puerta(), continuar = puerta();
        doAnswer(i -> { sinTransaccion(); dentro.countDown(); esperar(continuar); return null; })
                .when(storage).completarPorPartes(anyString(), anyString(), any());
        Future<MediaAssetResponse> carga = async(() -> media.completarPorPartes(id, WORKSPACE));
        esperar(dentro);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.COMPLETING);
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; }))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("terminando");
        continuar.countDown();
        assertThat(carga.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(MediaAssetStatus.READY);
        assertThat(enWorkspace(() -> media.completarPorPartes(id, WORKSPACE)).getStatus()).isEqualTo(MediaAssetStatus.READY);
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; }))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("disponible");
        verify(storage, times(1)).completarPorPartes(anyString(), anyString(), any());
        verify(miniaturas, times(1)).sacar(id, WS);
        verify(storage, never()).abortarPorPartes(anyString(), anyString());
        verify(storage, never()).deleteByKey(anyString());
    }

    @Test
    void consultaViejaNoPuedeUnirDespuesDeQueOtroLeaseCancelo() throws Exception {
        UUID id = abrir();
        CountDownLatch dentro = puerta(), continuar = puerta();
        AtomicInteger lecturas = new AtomicInteger();
        when(storage.partesSubidas(anyString(), anyString())).thenAnswer(i -> {
            sinTransaccion();
            if (lecturas.incrementAndGet() == 1) { dentro.countDown(); esperar(continuar); return partes(); }
            return List.of();
        });
        Future<MediaAssetResponse> vieja = async(() -> media.completarPorPartes(id, WORKSPACE));
        esperar(dentro);
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; }))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("revisada");
        editar(id, a -> a.setUploadLeaseUntil(Instant.now().minusSeconds(1)));
        enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; });
        continuar.countDown();
        assertThatThrownBy(() -> vieja.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ConflictoException.class);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
    }

    @Test
    void otroCoordinadorRecuperaLaUnionQueNoSeGuardo() {
        UUID id = abrir();
        simularProcesoCaido(id, UploadDecision.COMPLETING);
        when(storage.consultar(anyString())).thenAnswer(i -> { sinTransaccion(); return objeto(); });
        CoordinadorDeSubidas nuevo = nuevoCoordinador();
        assertThat(enWorkspace(() -> nuevo.completar(id, WORKSPACE)).getStatus()).isEqualTo(MediaAssetStatus.READY);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.DONE);
        assertThat(fila(id).getUploadLeaseOwner()).isNull();
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
        verify(miniaturas, times(1)).sacar(id, WS);
    }

    @Test
    void procesoViejoNoConfirmaNiSobrescribeAlQueRecuperoElLease() throws Exception {
        UUID id = abrir();
        completas();
        CountDownLatch dentro = puerta(), continuar = puerta();
        AtomicInteger uniones = new AtomicInteger();
        doAnswer(i -> {
            sinTransaccion();
            if (uniones.incrementAndGet() == 1) { dentro.countDown(); esperar(continuar); }
            return null;
        }).when(storage).completarPorPartes(anyString(), anyString(), any());
        Future<MediaAssetResponse> vieja = async(() -> media.completarPorPartes(id, WORKSPACE));
        esperar(dentro);
        editar(id, a -> a.setUploadLeaseUntil(Instant.now().minusSeconds(1)));
        CoordinadorDeSubidas nuevo = nuevoCoordinador();
        assertThat(enWorkspace(() -> nuevo.completar(id, WORKSPACE)).getStatus()).isEqualTo(MediaAssetStatus.READY);
        continuar.countDown();
        assertThatThrownBy(() -> vieja.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(ConflictoException.class);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.DONE);
        verify(miniaturas, times(1)).sacar(id, WS);
        verify(storage, never()).abortarPorPartes(anyString(), anyString());
        verify(storage, never()).deleteByKey(anyString());
    }

    @Test
    void abortoFallidoSeRecuperaTrasReinicioYConservaReservaHastaEntonces() {
        UUID id = abrir();
        doThrow(new IllegalStateException("test abort failure")).doNothing()
                .when(storage).abortarPorPartes(anyString(), anyString());
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; }))
                .hasMessage("test abort failure");
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLING);
        assertThat(fila(id).getMultipartUploadId()).isEqualTo("multipart-test");
        assertThat(fila(id).getUploadNextAttemptAt()).isAfter(Instant.now());
        assertThat(enWorkspace(assets::espacioUsado)).isEqualTo(BYTES);
        editar(id, a -> a.setUploadNextAttemptAt(Instant.now().minusSeconds(1)));
        new MultipartRecoveryWorker(assets, nuevoCoordinador()).recuperar();
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        assertThat(enWorkspace(assets::espacioUsado)).isZero();
        verify(storage, times(2)).abortarPorPartes(anyString(), anyString());
    }

    @Test
    void limpiezaRespetaLaSesionYLasFirmasAntesDeVencer() {
        UUID id = abrir();
        assertThat(enWorkspace(() -> limpieza.resolver(id))).isFalse();
        editar(id, a -> {
            a.setUploadExpiresAt(Instant.now().minusSeconds(1));
            a.setUploadProtectedUntil(Instant.now().plusSeconds(600));
        });
        assertThat(enWorkspace(() -> limpieza.resolver(id))).isFalse();
        verify(storage, never()).abortarPorPartes(anyString(), anyString());
        editar(id, a -> a.setUploadProtectedUntil(Instant.now().minusSeconds(1)));
        assertThat(enWorkspace(() -> limpieza.resolver(id))).isFalse();
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.EXPIRED);
        assertThat(enWorkspace(() -> media.estadoDeSubida(fila(id).getClientUploadId(), WORKSPACE)).getEstado()).isEqualTo("EXPIRED");
    }

    @Test
    void cancelarUnObjetoFinalHeredadoLoConfirmaSinBorrarlo() {
        UUID id = abrir();
        when(storage.consultar(anyString())).thenReturn(objeto());
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; }))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("disponible");
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.READY);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.DONE);
        verify(storage, never()).deleteByKey(anyString());
    }

    @Test
    void objetoInesperadoAlCancelarConservaReservaParaRevision() {
        UUID id = abrir();
        when(storage.consultar(anyString())).thenReturn(new R2StorageService.Consulta(BYTES + 1, "video/mp4"));
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; }))
                .hasMessageContaining("revisión");
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLING);
        assertThat(enWorkspace(assets::espacioUsado)).isEqualTo(BYTES);
        verify(storage, never()).deleteByKey(anyString());
    }

    @Test
    void timeoutDeUnionConservaDecisionYRecuperaObjetoSinNuevaReserva() {
        UUID id = abrir();
        completas();
        doThrow(SdkClientException.create("test timeout")).when(storage).completarPorPartes(anyString(), anyString(), any());
        assertThatThrownBy(() -> enWorkspace(() -> media.completarPorPartes(id, WORKSPACE))).isInstanceOf(SdkClientException.class);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.COMPLETING);
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; }))
                .isInstanceOf(ConflictoException.class);
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(List.of());
        assertThat(enWorkspace(() -> nuevoCoordinador().completar(id, WORKSPACE)).getStatus()).isEqualTo(MediaAssetStatus.READY);
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
        verify(storage, times(1)).completarPorPartes(anyString(), anyString(), any());
    }

    @Test
    void firmasNoSobrepasanElPlazoNiSeEntreganTrasUnaDecision() {
        UUID id = abrir();
        Instant limite = Instant.now().plusSeconds(900);
        editar(id, a -> a.setUploadExpiresAt(limite));
        long ttl = enWorkspace(() -> media.firmarPartes(id, WORKSPACE, List.of(1))).getExpiresInSeconds();
        assertThat(ttl).isBetween(1L, 300L);
        assertThat(fila(id).getUploadProtectedUntil()).isBeforeOrEqualTo(limite);
        simularProcesoCaido(id, UploadDecision.COMPLETING);
        assertThatThrownBy(() -> enWorkspace(() -> media.firmarPartes(id, WORKSPACE, List.of(1))))
                .isInstanceOf(ConflictoException.class);
        verify(storage, times(1)).firmarParte(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void colaGlobalExcluyeBackoffYLeaseActivos() {
        UUID lista = abrir(), espera = abrir(), ocupada = abrir();
        editar(lista, a -> a.setUploadNextAttemptAt(Instant.now().minusSeconds(1)));
        editar(espera, a -> a.setUploadNextAttemptAt(Instant.now().plusSeconds(600)));
        editar(ocupada, a -> { a.setUploadNextAttemptAt(Instant.now().minusSeconds(1)); a.setUploadLeaseUntil(Instant.now().plusSeconds(300)); });
        assertThat(assets.multipartRecuperables(Instant.now(), 100)).extracting(f -> String.valueOf(f[0]))
                .contains(lista.toString()).doesNotContain(espera.toString(), ocupada.toString());
    }

    @Test
    void noCompletaCancelaNiLimpiaOtroWorkspace() {
        UUID id = abrir(), otro = UUID.randomUUID();
        assertThatThrownBy(() -> enWorkspace(() -> coordinador.completar(id, otro))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, otro); return null; })).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> enWorkspace(() -> coordinador.limpiar(id, otro))).isInstanceOf(ResourceNotFoundException.class);
        verify(storage, never()).abortarPorPartes(anyString(), anyString());
        verify(storage, never()).partesSubidas(anyString(), anyString());
    }

    @Test
    void confirmarYReabrirNoResucitanUnaCancelacion() {
        UUID id = abrir();
        enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; });
        assertThatThrownBy(() -> enWorkspace(() -> media.confirm(id))).isInstanceOf(ConflictoException.class);
        assertThatThrownBy(() -> enWorkspace(() -> media.iniciarPorPartes(pedido(fila(id).getClientUploadId()), WORKSPACE)))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("cancelada");
        verify(storage, times(1)).iniciarPorPartes(anyString(), anyString());
        verify(miniaturas, never()).sacar(any(), anyString());
    }

    @Test
    void partesQueSiguenPresentesTrasAbortoImpidenLiberarReserva() {
        UUID id = abrir();
        when(storage.partesSubidas(anyString(), anyString())).thenReturn(partes());
        assertThatThrownBy(() -> enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; }))
                .hasMessageContaining("conserva partes");
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLING);
        assertThat(enWorkspace(assets::espacioUsado)).isEqualTo(BYTES);
    }

    @Test
    void dosCoordinadoresQueArrancanJuntosSoloReclamanUnaVez() throws Exception {
        UUID id = abrir();
        completas();
        CountDownLatch listas = new CountDownLatch(2), iniciar = puerta();
        CountDownLatch dentro = puerta(), continuar = puerta(), rechazada = puerta();
        when(storage.partesSubidas(anyString(), anyString())).thenAnswer(i -> {
            sinTransaccion(); dentro.countDown(); esperar(continuar); return partes();
        });
        CoordinadorDeSubidas otroProceso = nuevoCoordinador();
        java.util.function.Function<CoordinadorDeSubidas, String> operacion = c -> {
            listas.countDown();
            try {
                esperar(iniciar);
                return c.completar(id, WORKSPACE).getStatus().name();
            } catch (ConflictoException ex) {
                rechazada.countDown(); return ex.getCode();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(ex);
            }
        };
        Future<String> una = async(() -> operacion.apply(coordinador));
        Future<String> otra = async(() -> operacion.apply(otroProceso));
        esperar(listas); iniciar.countDown(); esperar(dentro); esperar(rechazada);
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CHECKING);
        continuar.countDown();
        assertThat(List.of(una.get(20, TimeUnit.SECONDS), otra.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("READY", "UPLOAD_BUSY");
        verify(storage, times(1)).partesSubidas(anyString(), anyString());
        verify(storage, times(1)).completarPorPartes(anyString(), anyString(), any());
        verify(miniaturas, times(1)).sacar(id, WS);
    }

    @Test
    void transaccionDelInvocadorSeSuspendeDuranteElTrabajoRemoto() {
        UUID id = abrir();
        completas();
        MediaAssetResponse respuesta = enWorkspace(() -> new TransactionTemplate(manager).execute(s -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return coordinador.completar(id, WORKSPACE);
        }));
        assertThat(respuesta.getStatus()).isEqualTo(MediaAssetStatus.READY);
        verify(storage).completarPorPartes(anyString(), anyString(), any());
    }

    @Test
    void borradoGenericoNoEliminaUnaSesionNiSuRegistroTerminal() {
        UUID id = abrir();
        assertThatThrownBy(() -> enWorkspace(() -> { media.liberar(id); return null; }))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("cancelación");
        assertThatThrownBy(() -> enWorkspace(() -> { media.delete(id); return null; }))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("cancelación");
        enWorkspace(() -> { coordinador.cancelar(id, WORKSPACE); return null; });
        assertThatThrownBy(() -> enWorkspace(() -> { media.delete(id); return null; }))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("registro");
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        verify(storage, never()).deleteByKey(anyString());
    }

    @Test
    void limpiadorYRescateAntiguosExcluyenMultipartAunqueSuUltimaPeticionSeaVieja() {
        UUID id = abrir();
        editar(id, a -> a.setUploadRenewedAt(java.time.LocalDateTime.now().minusDays(2)));
        java.time.LocalDateTime limite = java.time.LocalDateTime.now().minusMinutes(60);
        assertThat(assets.findPrefirmadosViejos(limite, limite, 100)).extracting(f -> String.valueOf(f[0]))
                .doesNotContain(id.toString());
        assertThat(assets.findPrefirmadosViejos(limite, 100)).extracting(f -> String.valueOf(f[0]))
                .doesNotContain(id.toString());
        editar(id, a -> a.setUploadNextAttemptAt(Instant.now().minusSeconds(1)));
        assertThat(assets.multipartRecuperables(Instant.now(), 100)).extracting(f -> String.valueOf(f[0]))
                .contains(id.toString());
    }

    @Test
    void decisionNoUsaLaCopiaObsoletaDelContextoDeUnaPeticion() {
        UUID id = abrir(), tokenViejo = UUID.randomUUID();
        editar(id, a -> {
            a.setUploadDecision(UploadDecision.CHECKING);
            a.setUploadLeaseOwner(tokenViejo);
            a.setUploadLeaseUntil(Instant.now().plusSeconds(300));
        });
        enWorkspace(() -> {
            assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
            jakarta.persistence.EntityManager em = entityManagerFactory.createEntityManager();
            try {
                MediaAsset copiaVieja = em.find(MediaAsset.class, id);
                // Emula un contexto de petición abierto, sin transacción activa (OSIV).
                TransactionSynchronizationManager.bindResource(entityManagerFactory,
                        new org.springframework.orm.jpa.EntityManagerHolder(em));
                Future<Void> otroProceso = async(() -> {
                    editar(id, a -> a.setUploadLeaseUntil(Instant.now().minusSeconds(1)));
                    coordinador.cancelar(id, WORKSPACE);
                    return null;
                });
                try { otroProceso.get(20, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new AssertionError(ex); }
                assertThat(copiaVieja.getUploadLeaseOwner()).isEqualTo(tokenViejo);
                assertThatThrownBy(() -> coordinador.decidirCompletar(id, tokenViejo))
                        .isInstanceOf(ConflictoException.class).hasMessageContaining("recuperando");
            } finally {
                TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
                em.close();
            }
            return null;
        });
        assertThat(fila(id).getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        assertThat(fila(id).getStatus()).isEqualTo(MediaAssetStatus.RELEASED);
        verify(storage, never()).completarPorPartes(anyString(), anyString(), any());
    }

    private UUID abrir() {
        UUID id = enWorkspace(() -> media.iniciarPorPartes(pedido("c_" + UUID.randomUUID()), WORKSPACE)).getAssetId();
        creados.add(id);
        return id;
    }
    private MediaPresignRequest pedido(String clave) {
        MediaPresignRequest p = new MediaPresignRequest();
        p.setClientUploadId(clave); p.setFileName("test.mp4"); p.setSizeBytes(BYTES);
        p.setContentType("video/mp4"); p.setAunqueRepetido(true);
        return p;
    }
    private void completas() {
        when(storage.partesSubidas(anyString(), anyString())).thenAnswer(i -> { sinTransaccion(); return partes(); });
        when(storage.consultar(anyString())).thenAnswer(i -> { sinTransaccion(); return objeto(); });
    }
    private List<R2StorageService.ParteSubida> partes() { return List.of(new R2StorageService.ParteSubida(1, "etag", BYTES)); }
    private R2StorageService.Consulta objeto() { return new R2StorageService.Consulta(BYTES, "video/mp4"); }
    private MediaAsset fila(UUID id) { return enWorkspace(() -> assets.findById(id).orElseThrow()); }
    private void editar(UUID id, Consumer<MediaAsset> cambio) {
        enWorkspace(() -> new TransactionTemplate(manager).execute(s -> {
            MediaAsset a = assets.findForUploadUpdate(id).orElseThrow(); cambio.accept(a); return assets.save(a);
        }));
    }
    private void simularProcesoCaido(UUID id, UploadDecision decision) {
        editar(id, a -> { a.setUploadDecision(decision); a.setUploadLeaseOwner(UUID.randomUUID()); a.setUploadLeaseUntil(Instant.now().minusSeconds(1)); });
    }
    private CoordinadorDeSubidas nuevoCoordinador() {
        CoordinadorDeSubidas c = new CoordinadorDeSubidas(assets, storage, limites, manager); c.setMedia(media); return c;
    }
    private CountDownLatch puerta() { CountDownLatch p = new CountDownLatch(1); puertas.add(p); return p; }
    private void esperar(CountDownLatch p) throws InterruptedException { assertThat(p.await(20, TimeUnit.SECONDS)).isTrue(); }
    private <T> Future<T> async(Supplier<T> tarea) { return executor.submit(() -> enWorkspace(tarea)); }
    private void sinTransaccion() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private <T> T enWorkspace(Supplier<T> tarea) {
        Object[] valor = new Object[1]; TenantIdentifierResolver.comoTenant(WS, () -> valor[0] = tarea.get());
        @SuppressWarnings("unchecked") T resultado = (T) valor[0]; return resultado;
    }
}
