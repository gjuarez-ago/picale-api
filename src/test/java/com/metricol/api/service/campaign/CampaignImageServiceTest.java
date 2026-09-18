package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.models.response.CampaignImageResponse;
import com.metricol.api.entity.Post;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.service.ai.AiQuotaGuard;
import com.metricol.api.service.ai.AiUsageRecorder;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.ai.OpenAiImageClient;
import com.metricol.api.service.ai.OpenAiImageClient.Resultado;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

class CampaignImageServiceTest {

    private static final UUID WORKSPACE = UUID.randomUUID();

    private OpenAiImageClient imagenes;
    private OpenAiClient texto;
    private R2StorageService storage;
    private MediaAssetRepository assets;
    private PostRepository posts;
    private StorageQuotaService cuota;
    private AiQuotaGuard cupo;
    private AiUsageRecorder usos;
    private CampaignImageService servicio;
    private User usuario;

    /** Lo que se subió a R2, en el orden en que se subió. */
    private final List<byte[]> subidos = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void preparar() throws Exception {
        imagenes = mock(OpenAiImageClient.class);
        texto = mock(OpenAiClient.class);
        storage = mock(R2StorageService.class);
        assets = mock(MediaAssetRepository.class);
        posts = mock(PostRepository.class);
        cuota = mock(StorageQuotaService.class);
        cupo = mock(AiQuotaGuard.class);
        usos = mock(AiUsageRecorder.class);
        servicio = new CampaignImageService(imagenes, texto, storage, assets, posts, cuota, cupo, usos);

        when(imagenes.disponible()).thenReturn(true);
        when(cupo.exigirCupoImagenes(anyInt())).thenReturn(9);
        when(texto.completeJson(any(), anyString(), anyString())).thenReturn(
                "{\"headline\":\"20% esta semana\",\"supportingCopy\":\"Solo hasta el domingo\","
                        + "\"caption\":\"Ven por tu descuento\"}");

        AtomicInteger contador = new AtomicInteger();
        when(storage.claveNueva(any(), anyString(), anyString()))
                .thenAnswer(i -> "media/" + WORKSPACE + "/" + contador.incrementAndGet() + ".jpg");
        when(storage.subirBytes(anyString(), any(byte[].class), anyString())).thenAnswer(i -> {
            subidos.add(i.getArgument(1));
            return "https://cdn.test/" + i.getArgument(0);
        });
        when(assets.saveAll(anyList())).thenAnswer(i -> {
            Collection<MediaAsset> lista = i.getArgument(0);
            lista.forEach(a -> a.setId(UUID.randomUUID()));
            return new ArrayList<>(lista);
        });

        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE);
        when(workspace.getName()).thenReturn("Tacos Doña Mary");
        when(workspace.getGiro()).thenReturn("Restaurante");
        when(workspace.getCiudad()).thenReturn("Mérida");
        usuario = mock(User.class);
        when(usuario.getWorkspace()).thenReturn(workspace);
    }

    // --------------------------------------------------------------- ayudas

    private static byte[] png(int color) throws Exception {
        BufferedImage imagen = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 200; x++) {
                imagen.setRGB(x, y, color);
            }
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(imagen, "png", salida);
        return salida.toByteArray();
    }

    private static CampaignImageRequest peticion(String formato, List<String> recursos, String logo) {
        return new CampaignImageRequest(
                2,
                new CampaignImageRequest.Format(formato, "4:5", "1024x1536"),
                recursos,
                logo == null ? null : new CampaignImageRequest.Brand(logo),
                "Anuncia el 20% de descuento",
                "Vender",
                List.of("Minimalista"),
                "Cercano",
                "Escríbenos");
    }

    private MediaAsset asset(String url, String tipoContenido) {
        return MediaAsset.builder()
                .id(UUID.randomUUID())
                .url(url)
                .storageKey("media/" + WORKSPACE + "/" + url.hashCode() + ".jpg")
                .type(MediaType.IMAGE)
                .status(MediaAssetStatus.READY)
                .contentType(tipoContenido)
                .sizeBytes(1000L)
                .build();
    }

    /** Hace que R2 "descargue" el contenido dado al archivo temporal. */
    private void r2Sirve(byte[] contenido) {
        when(storage.descargar(anyString(), any(Path.class))).thenAnswer(i -> {
            Files.write(i.getArgument(1), contenido);
            return true;
        });
    }

    private static int canalDominante(byte[] jpeg) throws Exception {
        BufferedImage imagen = ImageIO.read(new ByteArrayInputStream(jpeg));
        int pixel = imagen.getRGB(imagen.getWidth() / 2, imagen.getHeight() / 2);
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return r > g && r > b ? 0 : g > b ? 1 : 2;
    }

    // ------------------------------------------------------------ una pieza

    @Test
    @DisplayName("un post sin fotos se genera por texto, queda a 4:5 y cuenta una imagen")
    void postSinFotos() throws Exception {
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0xFF0000), 50, 1000, "gpt-image-1.5"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("post", List.of(), null));

        assertThat(respuesta.imageUrls()).hasSize(1);
        assertThat(respuesta.imageUrl()).isEqualTo(respuesta.imageUrls().get(0));
        assertThat(respuesta.headline()).isEqualTo("20% esta semana");
        assertThat(respuesta.caption()).isEqualTo("Ven por tu descuento");
        assertThat(respuesta.version()).isEqualTo(2);
        assertThat(respuesta.creditsUsed()).isEqualTo(1);
        assertThat(respuesta.creditsRemaining()).isEqualTo(9);

        BufferedImage guardada = ImageIO.read(new ByteArrayInputStream(subidos.get(0)));
        assertThat((double) guardada.getWidth() / guardada.getHeight()).isBetween(0.79, 0.81);

        verify(imagenes, never()).editar(anyString(), anyList(), anyString());
        verify(usos).registrarImagen("gpt-image-1.5", 50, 1000);
        verify(cupo).exigirCupoImagenes(1);
    }

    @Test
    @DisplayName("una historia queda a 9:16")
    void historia() throws Exception {
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0x00FF00), 0, 0, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("story", List.of(), null));

        BufferedImage guardada = ImageIO.read(new ByteArrayInputStream(subidos.get(0)));
        assertThat((double) guardada.getWidth() / guardada.getHeight()).isBetween(0.55, 0.57);
    }

    @Test
    @DisplayName("con fotos del workspace usa la edición y le pasa la foto y el logo")
    void conFotoYLogo() throws Exception {
        String foto = "https://cdn.test/foto.jpg";
        String logo = "https://cdn.test/logo.png";
        when(assets.findByUrlIn(anyList())).thenAnswer(i -> {
            List<String> urls = i.getArgument(0);
            List<MediaAsset> encontrados = new ArrayList<>();
            if (urls.contains(foto)) {
                encontrados.add(asset(foto, "image/jpeg"));
            }
            if (urls.contains(logo)) {
                encontrados.add(asset(logo, "image/png"));
            }
            return encontrados;
        });
        r2Sirve(new byte[] { 7, 7, 7 });
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x0000FF), 10, 20, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(foto), logo));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenAiImageClient.Referencia>> referencias = ArgumentCaptor.forClass(List.class);
        verify(imagenes).editar(prompt.capture(), referencias.capture(), eq("1024x1536"));
        assertThat(referencias.getValue()).hasSize(2);
        assertThat(referencias.getValue().get(0).contentType()).isEqualTo("image/jpeg");
        assertThat(referencias.getValue().get(1).contentType()).isEqualTo("image/png");
        assertThat(prompt.getValue())
                .contains("Tacos Doña Mary")
                .contains("Restaurante")
                .contains("Mérida")
                .contains("Anuncia el 20% de descuento")
                .contains("brand logo")
                .contains("cropped to 4:5");
        verify(imagenes, never()).generar(anyString(), anyString());
    }

    // -------------------------------------------------------------- carrusel

    @Test
    @DisplayName("un carrusel genera una pieza por foto y conserva el orden elegido")
    void carruselEnOrden() throws Exception {
        String a = "https://cdn.test/a.jpg";
        String b = "https://cdn.test/b.jpg";
        String c = "https://cdn.test/c.jpg";
        when(assets.findByUrlIn(anyList())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream()
                .map(u -> asset(u, "image/jpeg")).toList());
        r2Sirve(new byte[] { 1 });
        // El color de cada pieza depende de qué diapositiva pidió el prompt.
        when(imagenes.editar(anyString(), anyList(), anyString())).thenAnswer(i -> {
            String prompt = i.getArgument(0);
            int color = prompt.contains("slide 1 of 3") ? 0xFF0000
                    : prompt.contains("slide 2 of 3") ? 0x00FF00 : 0x0000FF;
            // La pieza 1 tarda más: el orden no puede depender de quién termina antes.
            if (prompt.contains("slide 1 of 3")) {
                Thread.sleep(150);
            }
            return new Resultado(png(color), 1, 1, "gpt-image-1.5");
        });

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("carousel", List.of(a, b, c), null));

        assertThat(respuesta.imageUrls()).hasSize(3);
        assertThat(respuesta.creditsUsed()).isEqualTo(3);
        verify(imagenes, times(3)).editar(anyString(), anyList(), anyString());
        verify(cupo).exigirCupoImagenes(3);
        verify(usos, times(3)).registrarImagen(anyString(), anyInt(), anyInt());
        assertThat(canalDominante(subidos.get(0))).as("primera pieza: roja").isEqualTo(0);
        assertThat(canalDominante(subidos.get(1))).as("segunda pieza: verde").isEqualTo(1);
        assertThat(canalDominante(subidos.get(2))).as("tercera pieza: azul").isEqualTo(2);
    }

    @Test
    @DisplayName("un carrusel con una sola foto se rechaza antes de gastar nada")
    void carruselConUnaFoto() {
        assertThatThrownBy(() -> servicio.generar(usuario, peticion("carousel", List.of("https://cdn.test/a.jpg"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 2 y 5");

        verify(imagenes, never()).editar(anyString(), anyList(), anyString());
        verify(cupo, never()).exigirCupoImagenes(anyInt());
    }

    @Test
    @DisplayName("si una pieza del carrusel falla no se guarda nada, pero las pagadas quedan anotadas")
    void unaPiezaFalla() throws Exception {
        String a = "https://cdn.test/a.jpg";
        String b = "https://cdn.test/b.jpg";
        when(assets.findByUrlIn(anyList())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream()
                .map(u -> asset(u, "image/jpeg")).toList());
        r2Sirve(new byte[] { 1 });
        when(imagenes.editar(anyString(), anyList(), anyString())).thenAnswer(i -> {
            String prompt = i.getArgument(0);
            if (prompt.contains("slide 2 of 2")) {
                throw new IllegalStateException("La IA está atendiendo muchas peticiones.");
            }
            return new Resultado(png(0xFF0000), 5, 5, "gpt-image-1.5");
        });

        assertThatThrownBy(() -> servicio.generar(usuario, peticion("carousel", List.of(a, b), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("muchas peticiones");

        verify(usos, times(1)).registrarImagen(anyString(), anyInt(), anyInt());
        verify(storage, never()).subirBytes(anyString(), any(byte[].class), anyString());
        verify(assets, never()).saveAll(anyList());
    }

    // ------------------------------------------------------------ seguridad

    @Test
    @DisplayName("una foto que no es de este workspace se rechaza sin llamar a OpenAI")
    void fotoAjena() {
        when(assets.findByUrlIn(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> servicio.generar(usuario,
                peticion("post", List.of("https://cdn.test/de-otro-espacio.jpg"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya no está disponible");

        verify(imagenes, never()).editar(anyString(), anyList(), anyString());
        verify(imagenes, never()).generar(anyString(), anyString());
        verify(storage, never()).descargar(anyString(), any(Path.class));
        verify(usos, never()).registrarImagen(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("un video no sirve de referencia")
    void videoComoReferencia() {
        MediaAsset video = asset("https://cdn.test/v.mp4", "video/mp4");
        video.setType(MediaType.VIDEO);
        when(assets.findByUrlIn(anyList())).thenReturn(List.of(video));

        assertThatThrownBy(() -> servicio.generar(usuario, peticion("post", List.of(video.getUrl()), null)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(imagenes, never()).editar(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("un formato de imagen no permitido por OpenAI se rechaza con un mensaje claro")
    void tipoNoAceptado() {
        String gif = "https://cdn.test/a.gif";
        when(assets.findByUrlIn(anyList())).thenReturn(List.of(asset(gif, "image/gif")));

        assertThatThrownBy(() -> servicio.generar(usuario, peticion("post", List.of(gif), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPG, PNG o WebP");
    }

    @Test
    @DisplayName("un logo que no es del workspace se ignora: la campaña sale sin él")
    void logoAjeno() throws Exception {
        when(assets.findByUrlIn(anyList())).thenReturn(List.of());
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0xFF0000), 0, 0, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(), "https://otro.dominio/logo.png"));

        verify(imagenes).generar(anyString(), anyString());
        verify(storage, never()).descargar(anyString(), any(Path.class));
    }

    @Test
    @DisplayName("con el tope diario agotado no se llama a OpenAI")
    void topeDiario() {
        when(cupo.exigirCupoImagenes(anyInt()))
                .thenThrow(new QuotaExceededException("IMAGE_QUOTA_EXCEEDED", "Ya generaste las 10 imágenes de hoy."));

        assertThatThrownBy(() -> servicio.generar(usuario, peticion("post", List.of(), null)))
                .isInstanceOf(QuotaExceededException.class);

        verify(imagenes, never()).generar(anyString(), anyString());
        verify(imagenes, never()).editar(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("sin espacio en el workspace no se llama a OpenAI")
    void sinEspacio() {
        doThrow(new QuotaExceededException("STORAGE_QUOTA_EXCEEDED", "No cabe."))
                .when(cuota).verificar(anyLong(), anyString());

        assertThatThrownBy(() -> servicio.generar(usuario, peticion("post", List.of(), null)))
                .isInstanceOf(QuotaExceededException.class);

        verify(imagenes, never()).generar(anyString(), anyString());
    }

    @Test
    @DisplayName("sin llave de OpenAI dice que no está disponible y no toca nada")
    void sinLlave() {
        when(imagenes.disponible()).thenReturn(false);

        assertThatThrownBy(() -> servicio.generar(usuario, peticion("post", List.of(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no está disponible");

        verify(cupo, never()).exigirCupoImagenes(anyInt());
    }

    @Test
    @DisplayName("un formato desconocido se rechaza")
    void formatoDesconocido() {
        assertThatThrownBy(() -> servicio.generar(usuario, peticion("banner", List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formato");
    }

    // ---------------------------------------------------------------- textos

    @Test
    @DisplayName("si falla la IA de texto la campaña sale igual, con el brief de titular y sin caption")
    void textoFalla() throws Exception {
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0xFF0000), 0, 0, "gpt-image-1.5"));
        when(texto.completeJson(any(AiOperacion.class), anyString(), anyString()))
                .thenThrow(new IllegalStateException("OpenAI caído"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("post", List.of(), null));

        assertThat(respuesta.imageUrls()).hasSize(1);
        assertThat(respuesta.headline()).isEqualTo("Anuncia el 20% de descuento");
        assertThat(respuesta.caption()).isNull();
    }

    @Test
    @DisplayName("si falla la subida a R2 no queda ninguna fila y se retira lo ya subido")
    void falloDeR2() throws Exception {
        when(assets.findByUrlIn(anyList())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream()
                .map(u -> asset(u, "image/jpeg")).toList());
        r2Sirve(new byte[] { 1 });
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0xFF0000), 0, 0, "gpt-image-1.5"));
        AtomicInteger subidas = new AtomicInteger();
        when(storage.subirBytes(anyString(), any(byte[].class), anyString())).thenAnswer(i -> {
            if (subidas.incrementAndGet() == 2) {
                throw new IllegalStateException("R2 caído");
            }
            return "https://cdn.test/" + i.getArgument(0);
        });

        assertThatThrownBy(() -> servicio.generar(usuario,
                peticion("carousel", List.of("https://cdn.test/a.jpg", "https://cdn.test/b.jpg"), null)))
                .isInstanceOf(IllegalStateException.class);

        verify(assets, never()).saveAll(anyList());
        verify(storage, times(1)).deleteByKey(anyString());
    }

    @Test
    @DisplayName("el texto se escribe con los captions que el negocio ya publicó como referencia de su voz")
    void usaCaptionsAnteriores() throws Exception {
        Post viejo = new Post();
        viejo.setCaption("Hoy toca taquiza en Mérida 🌮 Te esperamos.");
        Post vacio = new Post();
        vacio.setCaption("   ");
        when(posts.findTop8ByStatusAndArchivedAtIsNullOrderByPublishedAtDesc(PostStatus.PUBLISHED))
                .thenReturn(List.of(viejo, vacio));
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0xFF0000), 0, 0, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(), null));

        ArgumentCaptor<String> usuarioPrompt = ArgumentCaptor.forClass(String.class);
        verify(texto).completeJson(eq(AiOperacion.TEXTO_CAMPANA), anyString(), usuarioPrompt.capture());
        assertThat(usuarioPrompt.getValue())
                .contains("ya publicó")
                .contains("Hoy toca taquiza en Mérida")
                .doesNotContain("- \n");
    }

    @Test
    @DisplayName("si no se pueden leer los captions anteriores la campaña sale igual")
    void captionsAnterioresFallan() throws Exception {
        when(posts.findTop8ByStatusAndArchivedAtIsNullOrderByPublishedAtDesc(PostStatus.PUBLISHED))
                .thenThrow(new IllegalStateException("base caída"));
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0xFF0000), 0, 0, "gpt-image-1.5"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("post", List.of(), null));

        assertThat(respuesta.imageUrls()).hasSize(1);
        assertThat(respuesta.caption()).isEqualTo("Ven por tu descuento");
    }
}
