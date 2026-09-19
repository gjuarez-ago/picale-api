package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Platform;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.models.response.ContenidoEstadoResponse;
import com.metricol.api.service.campaign.CampaignImageService.Formato;
import com.metricol.api.service.campaign.CampaignImageService.Generado;
import com.metricol.api.service.campaign.CampaignImageService.Negocio;
import com.metricol.api.service.campaign.CampaignImageService.Preparado;
import com.metricol.api.service.campaign.CampaignImageService.Progreso;
import com.metricol.api.service.campaign.CampaignImageService.VarianteGenerada;
import com.metricol.api.service.campaign.Lienzo.Variante;

class ContenidoJobsTest {

    private static final UUID WS = UUID.randomUUID();

    private CampaignImageService generador;
    private ContenidoJobs trabajos;
    private User usuario;
    private CampaignImageRequest peticion;

    private static User usuarioDe(UUID workspace) {
        Workspace w = mock(Workspace.class);
        when(w.getId()).thenReturn(workspace);
        User u = mock(User.class);
        when(u.getWorkspace()).thenReturn(w);
        return u;
    }

    private static final List<Variante> DOS_VERSIONES = List.of(
            new Variante("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM, Platform.FACEBOOK)),
            new Variante("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN)));

    private static Preparado preparado() {
        return new Preparado(new Negocio(WS, "CMRG", null, null, null, null), Formato.POST, DOS_VERSIONES, List.of(),
                Map.of(), null, null, null, 1, 2, 8);
    }

    private static VarianteGenerada lista(String id, Lienzo lienzo, List<Platform> redes) {
        return new VarianteGenerada(id, lienzo, redes, List.of("https://cdn.test/" + id + ".jpg"),
                List.of(UUID.randomUUID().toString()), null, null);
    }

    private static VarianteGenerada fallida(String id, Lienzo lienzo, List<Platform> redes, String motivo) {
        return new VarianteGenerada(id, lienzo, redes, List.of(), List.of(), motivo, new IllegalStateException(motivo));
    }

    private static Generado generado(VarianteGenerada... variantes) {
        return new Generado(List.of(variantes), "Obra segura", "Manzanillo",
                "Caption general", Map.of(Platform.INSTAGRAM, "Texto IG", Platform.LINKEDIN, "Texto LI"), "prompt", 8);
    }

    @BeforeEach
    void preparar() {
        generador = mock(CampaignImageService.class);
        trabajos = new ContenidoJobs(generador);
        usuario = usuarioDe(WS);
        peticion = mock(CampaignImageRequest.class);
        when(generador.preparar(any(User.class), any())).thenReturn(preparado());
    }

    private ContenidoEstadoResponse esperar(String id, String estado) throws Exception {
        long limite = System.currentTimeMillis() + 5000;
        ContenidoEstadoResponse actual = trabajos.estado(usuario, id);
        while (!estado.equals(actual.status()) && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
            actual = trabajos.estado(usuario, id);
        }
        assertThat(actual.status()).isEqualTo(estado);
        return actual;
    }

    @Test
    @DisplayName("contesta con un identificador y va contando el avance hasta que cada versión está lista")
    void iniciaYConsulta() throws Exception {
        CountDownLatch pensando = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        when(generador.ejecutar(any(), any())).thenAnswer(i -> {
            Progreso avance = i.getArgument(1);
            avance.etapa("Pensando la composición…");
            pensando.countDown();
            soltar.await(5, TimeUnit.SECONDS);
            avance.etapa("Creando las imágenes…");
            VarianteGenerada v1 = lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM, Platform.FACEBOOK));
            avance.varianteLista(v1);
            return generado(v1, lista("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN)));
        });

        String id = trabajos.iniciar(usuario, peticion);
        assertThat(pensando.await(5, TimeUnit.SECONDS)).isTrue();

        ContenidoEstadoResponse mientras = trabajos.estado(usuario, id);
        assertThat(mientras.status()).isEqualTo("THINKING");
        assertThat(mientras.stage()).isEqualTo("Pensando la composición…");
        // Las dos versiones ya se conocen y todavía ninguna llegó.
        assertThat(mientras.versions()).extracting(ContenidoEstadoResponse.Version::status)
                .containsExactly("PENDING", "PENDING");
        assertThat(mientras.versions().get(0).ratio()).isEqualTo("4:5");
        assertThat(mientras.versions().get(0).networks()).containsExactly("INSTAGRAM", "FACEBOOK");
        assertThat(mientras.versions().get(1).ratio()).isEqualTo("1:1");

        soltar.countDown();
        ContenidoEstadoResponse listo = esperar(id, "READY");

        assertThat(listo.versions()).extracting(ContenidoEstadoResponse.Version::status).containsExactly("READY", "READY");
        assertThat(listo.versions().get(0).imageUrls()).containsExactly("https://cdn.test/v1.jpg");
        assertThat(listo.headline()).isEqualTo("Obra segura");
        assertThat(listo.captions()).containsEntry("INSTAGRAM", "Texto IG").containsEntry("LINKEDIN", "Texto LI");
        assertThat(listo.creditsRemaining()).isEqualTo(8);
    }

    @Test
    @DisplayName("el hilo de fondo trabaja con el workspace correcto, no con el de nadie")
    void elWorkspaceSePoneEnElHilo() throws Exception {
        AtomicReference<String> visto = new AtomicReference<>();
        when(generador.ejecutar(any(), any())).thenAnswer(i -> {
            visto.set(new TenantIdentifierResolver().resolveCurrentTenantIdentifier());
            return generado(lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM)),
                    lista("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN)));
        });

        String id = trabajos.iniciar(usuario, peticion);
        esperar(id, "READY");

        assertThat(visto.get()).isEqualTo(WS.toString());
    }

    @Test
    @DisplayName("un trabajo de otro workspace se ve igual que uno que no existe")
    void otroWorkspaceNoLoVe() throws Exception {
        when(generador.ejecutar(any(), any())).thenReturn(generado(
                lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM)),
                lista("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN))));
        String id = trabajos.iniciar(usuario, peticion);
        esperar(id, "READY");

        User ajeno = usuarioDe(UUID.randomUUID());

        assertThatThrownBy(() -> trabajos.estado(ajeno, id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> trabajos.estado(usuario, "no-existe")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("un workspace crea contenido de a uno: el segundo espera a que termine el primero")
    void unoALaVez() throws Exception {
        CountDownLatch empezo = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        when(generador.ejecutar(any(), any())).thenAnswer(i -> {
            empezo.countDown();
            soltar.await(5, TimeUnit.SECONDS);
            return generado(lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM)),
                    lista("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN)));
        });

        String primero = trabajos.iniciar(usuario, peticion);
        assertThat(empezo.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> trabajos.iniciar(usuario, peticion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ya estás creando contenido");
        // Otro workspace no se entera.
        User otro = usuarioDe(UUID.randomUUID());
        assertThat(trabajos.iniciar(otro, peticion)).isNotBlank();

        soltar.countDown();
        esperar(primero, "READY");
        // Ya libre, el workspace puede empezar otro.
        assertThat(trabajos.iniciar(usuario, peticion)).isNotEqualTo(primero);
    }

    @Test
    @DisplayName("lo que está mal se dice en la propia petición, sin crear trabajo ni dejar el workspace ocupado")
    void validacionFallaSinTrabajo() throws Exception {
        when(generador.preparar(any(User.class), any()))
                .thenThrow(new IllegalArgumentException("LinkedIn no admite historias. Quítala o cambia de formato."))
                .thenReturn(preparado());
        when(generador.ejecutar(any(), any())).thenReturn(generado(
                lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM)),
                lista("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN))));

        assertThatThrownBy(() -> trabajos.iniciar(usuario, peticion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LinkedIn no admite historias");

        // No quedó ocupado: el siguiente intento, ya corregido, arranca.
        assertThat(trabajos.iniciar(usuario, peticion)).isNotBlank();
    }

    @Test
    @DisplayName("un error inesperado deja el trabajo en FAILED con un mensaje entendible y libera al workspace")
    void falloInesperado() throws Exception {
        when(generador.ejecutar(any(), any())).thenThrow(new IllegalStateException("detalle interno de base de datos"));

        String id = trabajos.iniciar(usuario, peticion);
        ContenidoEstadoResponse fallo = esperar(id, "FAILED");

        // Lo interno no le llega a la persona.
        assertThat(fallo.error()).isEqualTo("No se pudo crear el contenido. Inténtalo de nuevo.");
        assertThat(fallo.error()).doesNotContain("base de datos");

        // Con doReturn: `when(...)` volvería a llamar al mock, que aún lanza.
        doReturn(generado(
                lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM)),
                lista("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN)))).when(generador).ejecutar(any(), any());
        assertThat(trabajos.iniciar(usuario, peticion)).isNotBlank();
    }

    @Test
    @DisplayName("si ninguna versión sale, FAILED con el motivo de la primera")
    void todasFallan() throws Exception {
        when(generador.ejecutar(any(), any())).thenReturn(generado(
                fallida("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM), "La IA está atendiendo muchas peticiones."),
                fallida("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN), "otra cosa")));

        String id = trabajos.iniciar(usuario, peticion);
        ContenidoEstadoResponse fallo = esperar(id, "FAILED");

        assertThat(fallo.error()).contains("muchas peticiones");
        assertThat(fallo.versions()).extracting(ContenidoEstadoResponse.Version::status).containsExactly("FAILED", "FAILED");
    }

    @Test
    @DisplayName("si una versión falla y otra sale, el trabajo está LISTO y la que falló lo dice")
    void unaSaleYOtraFalla() throws Exception {
        when(generador.ejecutar(any(), any())).thenReturn(generado(
                lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM, Platform.FACEBOOK)),
                fallida("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN), "La IA tardó demasiado en responder.")));

        // El estado por versión sale de lo que quien ejecuta fue avisando.
        when(generador.ejecutar(any(), any())).thenAnswer(i -> {
            Progreso avance = i.getArgument(1);
            VarianteGenerada ok = lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM, Platform.FACEBOOK));
            VarianteGenerada mal = fallida("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN),
                    "La IA tardó demasiado en responder.");
            avance.varianteLista(ok);
            avance.varianteLista(mal);
            return generado(ok, mal);
        });

        String id = trabajos.iniciar(usuario, peticion);
        ContenidoEstadoResponse listo = esperar(id, "READY");

        assertThat(listo.versions()).extracting(ContenidoEstadoResponse.Version::status).containsExactly("READY", "FAILED");
        assertThat(listo.versions().get(1).error()).contains("tardó demasiado");
        assertThat(listo.error()).isNull();
    }

    @Test
    @DisplayName("un trabajo terminado se conserva un rato y luego se tira")
    void seLimpia() throws Exception {
        when(generador.ejecutar(any(), any())).thenReturn(generado(
                lista("v1", Lienzo.CUATRO_QUINTOS, List.of(Platform.INSTAGRAM)),
                lista("v2", Lienzo.CUADRADO, List.of(Platform.LINKEDIN))));
        long[] ahora = { 1_000_000L };
        trabajos.reloj = () -> ahora[0];

        String id = trabajos.iniciar(usuario, peticion);
        esperar(id, "READY");
        assertThat(trabajos.estado(usuario, id).status()).isEqualTo("READY");

        ahora[0] += ContenidoJobs.RETENCION_MS + 1;

        assertThatThrownBy(() -> trabajos.estado(usuario, id)).isInstanceOf(ResourceNotFoundException.class);
    }
}
