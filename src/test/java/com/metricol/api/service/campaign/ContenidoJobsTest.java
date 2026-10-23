package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.*;
import com.metricol.api.repository.ContenidoJobRepository;
import com.metricol.api.service.billing.CreditService;
import com.metricol.api.service.campaign.CampaignImageService.*;
import com.metricol.api.service.campaign.Lienzo.Variante;
import com.metricol.api.enums.Platform;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.service.ai.OpenAiImageClient.Referencia;
import com.metricol.api.models.response.ContenidoEstadoResponse;

/** Base real H2 y proveedor simulado: nunca consume servicios externos. */
@DataJpaTest(showSql=false, properties={"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "logging.level.org.hibernate.SQL=WARN"})
@Import({ContenidoJobStore.class, ContenidoJobsTest.Config.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ContenidoJobsTest {
    @TestConfiguration static class Config { @Bean ObjectMapper jobMapper() { return new ObjectMapper().findAndRegisterModules(); } }
    @Autowired ContenidoJobStore store;
    @Autowired ContenidoJobRepository rows;
    @Autowired com.metricol.api.repository.MediaAssetRepository assets;
    @Autowired com.metricol.api.repository.CreditMovementRepository movements;
    @MockitoBean CreditService credits;
    CampaignImageService generator;
    ContenidoJobs jobs;
    UUID workspace;
    User user;
    Preparado p;
    CampaignImageRequest request;
    @BeforeEach void init() {
        workspace=UUID.randomUUID(); user=User.builder().workspace(Workspace.builder().id(workspace).build()).build();
        request=new CampaignImageRequest(1,new CampaignImageRequest.Format("post",null,null),List.of(),null,"Texto literal",null,null,null,null,false,List.of("INSTAGRAM"));
        p=new Preparado(new Negocio(workspace,"Banquetes",null,null,null,null), Formato.POST,
                List.of(new Variante("v1",Lienzo.CUATRO_QUINTOS,List.of(Platform.INSTAGRAM))), List.of(),
                Map.of("foto",new Referencia(new byte[]{1,2,3},"original.jpg","image/jpeg")), null,null,request,1,1,9,UUID.randomUUID().toString());
        when(credits.consumirGeneracion(any(),anyString())).thenReturn(8);
        generator=mock(CampaignImageService.class); when(generator.prepararSinCobro(any(),any())).thenReturn(p);
        jobs=new ContenidoJobs(generator,store);
    }
    @AfterEach void stop() { jobs.cerrar(); }
    VarianteGenerada piece() { return new VarianteGenerada("v1",Lienzo.CUATRO_QUINTOS,List.of(Platform.INSTAGRAM),List.of("https://test/real.jpg"),List.of(UUID.randomUUID().toString()),null,null); }
    Generado result() { return new Generado(List.of(piece()),"Título","Apoyo","Caption",Map.of(Platform.INSTAGRAM,"Caption"),"prompt",8); }
    ContenidoEstadoResponse await(String id,String status) throws Exception {
        long until=System.currentTimeMillis()+5000;
        ContenidoEstadoResponse r=jobs.estado(user,id);
        while(!status.equals(r.status()) && System.currentTimeMillis()<until) { Thread.sleep(20); r=jobs.estado(user,id); }
        assertThat(r.status()).isEqualTo(status); return r;
    }
    @Test void completesAndSurvivesANewServiceInstance() throws Exception {
        when(generator.ejecutar(any(),any())).thenAnswer(i->{ Progreso progress=i.getArgument(1); progress.antesDeProveedor(); progress.varianteLista(piece()); return result(); });
        String id=jobs.iniciar(user,request); var ready=await(id,"READY");
        assertThat(ready.versions().get(0).imageUrls()).containsExactly("https://test/real.jpg");
        var restarted=new ContenidoJobs(generator,store);
        try { assertThat(restarted.estado(user,id)).isEqualTo(ready); } finally { restarted.cerrar(); }
        assertThat(rows.findById(UUID.fromString(id)).orElseThrow().getPrepared()).isNull();
        verify(credits,times(1)).consumirGeneracion(workspace,p.referenciaCredito());
    }
    @Test void queuedWorkRecoversWithoutPreparingOrChargingAgain() throws Exception {
        UUID id=store.create(p);
        when(generator.ejecutar(any(),any())).thenAnswer(i->{ Progreso progress=i.getArgument(1); progress.antesDeProveedor(); progress.varianteLista(piece()); return result(); });
        jobs.recover(); await(id.toString(),"READY");
        jobs.recover(); verify(generator,times(1)).ejecutar(any(),any());
        verify(generator,never()).prepararSinCobro(any(),any());
        verify(credits,times(1)).consumirGeneracion(any(),any());
    }
    @Test void snapshotKeepsLiteralBriefAndFrozenReferenceBytes() {
        UUID id=store.create(p), owner=UUID.randomUUID();
        var recovered=store.claim(id,owner).orElseThrow().prepared();
        assertThat(recovered.peticion().brief()).isEqualTo("Texto literal");
        assertThat(recovered.fotos().get("foto").bytes()).containsExactly(1,2,3);
        assertThat(recovered.referenciaCredito()).isEqualTo(p.referenciaCredito());
        assertThat(recovered.restantes()).isEqualTo(8);
    }
    @Test void uniqueWorkspaceRevertsDuplicateAdmissionAndAllowsOtherBusinesses() {
        when(credits.consumirGeneracion(any(),anyString())).thenAnswer(i -> {
            movements.saveAndFlush(CreditMovement.builder().workspaceId(workspace).delta(-5).bolsa("PACK")
                    .motivo("GENERATION").referencia(UUID.randomUUID().toString()).build());
            return 8;
        });
        store.create(p);
        assertThatThrownBy(()->store.create(p)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(rows.findAll().stream().filter(j->workspace.equals(j.getWorkspaceId()))).hasSize(1);
        // El movimiento del intento rechazado se revierte con el alta; no basta contar mocks.
        assertThat(movements.findAll().stream().filter(m->workspace.equals(m.getWorkspaceId()))).hasSize(1);
    }
    @Test void twoWorkersCannotClaimTheSameJob() throws Exception {
        UUID id=store.create(p); var pool=Executors.newFixedThreadPool(2); var gate=new CountDownLatch(1);
        try {
            Callable<Boolean> claim=()->{gate.await(); return store.claim(id,UUID.randomUUID()).isPresent();};
            var first=pool.submit(claim); var second=pool.submit(claim); gate.countDown();
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
    void expire(UUID id) { var j=rows.findById(id).orElseThrow(); j.setLeaseUntil(Instant.now().minusSeconds(10)); rows.saveAndFlush(j); }
    @Test void expiredClaimBeforeProviderCanResumeWithTheSameCreditReference() {
        UUID id=store.create(p), first=UUID.randomUUID(); store.claim(id,first); expire(id);
        var next=store.claim(id,UUID.randomUUID()); assertThat(next).isPresent();
        assertThat(next.orElseThrow().prepared().referenciaCredito()).isEqualTo(p.referenciaCredito());
        assertThatThrownBy(()->store.beforeProvider(id,first)).isInstanceOf(IllegalStateException.class);
        verify(credits,times(1)).consumirGeneracion(any(),any());
    }
    @Test void interruptedProviderPreservesPartialImagesAndDoesNotRetryOrRefund() {
        UUID id=store.create(p), owner=UUID.randomUUID(); store.claim(id,owner); store.beforeProvider(id,owner);
        store.version(id,owner,piece(),false); expire(id);
        assertThat(store.claim(id,UUID.randomUUID())).isEmpty();
        var r=store.get(workspace,id); assertThat(r.status()).isEqualTo("UNAVAILABLE");
        assertThat(r.versions().get(0).imageUrls()).containsExactly("https://test/real.jpg");
        assertThat(store.claim(id,UUID.randomUUID())).isEmpty();
        verify(credits,never()).devolverGeneracion(any(),any());
        verifyNoInteractions(generator);
    }
    @Test void liveLeaseIsNotRecoveredByAnotherInstance() {
        UUID id=store.create(p), owner=UUID.randomUUID(); store.claim(id,owner); store.beforeProvider(id,owner);
        store.heartbeat(owner); assertThat(store.claim(id,UUID.randomUUID())).isEmpty();
        assertThat(store.get(workspace,id).status()).isEqualTo("THINKING");
    }
    @Test void failureBeforeProviderRefundsOnceAndReleasesWorkspace() {
        UUID id=store.create(p), owner=UUID.randomUUID(); store.claim(id,owner); store.failed(id,owner);
        assertThat(store.get(workspace,id).status()).isEqualTo("FAILED");
        assertThatThrownBy(()->store.failed(id,owner)).isInstanceOf(IllegalStateException.class);
        verify(credits,times(1)).devolverGeneracion(workspace,p.referenciaCredito());
        assertThat(store.create(p)).isNotEqualTo(id);
    }
    @Test void errorAfterProviderIsNotReportedAsFreeOrAutomaticallyRetried() throws Exception {
        when(generator.ejecutar(any(),any())).thenAnswer(i->{ ((Progreso)i.getArgument(1)).antesDeProveedor(); throw new IllegalStateException("timeout interno"); });
        String id=jobs.iniciar(user,request); var r=await(id,"UNAVAILABLE");
        assertThat(r.error()).doesNotContain("timeout interno");
        jobs.recover(); verify(generator,times(1)).ejecutar(any(),any());
        verify(credits,never()).devolverGeneracion(any(),any());
    }
    @Test void foreignWorkspaceAndInvalidIdAreNotVisible() {
        UUID id=store.create(p);
        assertThatThrownBy(()->store.get(UUID.randomUUID(),id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(()->jobs.estado(user,"no-id")).isInstanceOf(ResourceNotFoundException.class);
    }
    @Test void validationDoesNotCreateOrChargeAJob() {
        when(generator.prepararSinCobro(any(),any())).thenThrow(new IllegalArgumentException("Formato inválido"));
        assertThatThrownBy(()->jobs.iniciar(user,request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(credits);
        assertThat(rows.findAll().stream().filter(j->workspace.equals(j.getWorkspaceId()))).isEmpty();
    }
    @Test void recoversAssetEvenWhenCheckpointWasLostOrTheResponseArrivedLate() {
        UUID id=store.create(p), owner=UUID.randomUUID(); store.claim(id,owner); store.beforeProvider(id,owner); expire(id);
        store.claim(id,UUID.randomUUID()); // Resultado incierto y job ya sin worker.
        com.metricol.api.config.TenantIdentifierResolver.comoTenant(workspace.toString(), () ->
            assets.saveAndFlush(MediaAsset.builder().tenantId(workspace.toString()).fileName("piece.jpg")
                .status(com.metricol.api.enums.MediaAssetStatus.READY).type(com.metricol.api.enums.MediaType.IMAGE)
                .url("https://test/late.jpg").generationJobId(id).generationVersion("v1").generationIndex(0).build()));
        var recovered=store.get(workspace,id);
        assertThat(recovered.status()).isEqualTo("UNAVAILABLE");
        assertThat(recovered.versions().get(0).imageUrls()).containsExactly("https://test/late.jpg");
        verify(credits,never()).devolverGeneracion(any(),any());
    }
    @Test void corruptedUnsentSnapshotTerminatesInsteadOfLoopingOrChargingAgain() {
        UUID id=store.create(p); var row=rows.findById(id).orElseThrow(); row.setPrepared(new byte[]{1,2}); rows.saveAndFlush(row);
        assertThat(store.claim(id,UUID.randomUUID())).isEmpty();
        assertThat(store.get(workspace,id).status()).isEqualTo("FAILED");
        verify(credits,times(1)).devolverGeneracion(workspace,p.referenciaCredito());
    }
}
