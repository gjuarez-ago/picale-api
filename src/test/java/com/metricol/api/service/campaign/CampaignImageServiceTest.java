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
import java.util.Optional;
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
import com.metricol.api.service.ai.ArtDirector;
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
    private ArtDirector director;
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
        director = mock(ArtDirector.class);
        // Por defecto el director no está: es el camino de siempre. Las pruebas del
        // director lo encienden.
        when(director.disponible()).thenReturn(false);
        when(director.dirigir(any())).thenReturn(Optional.empty());
        servicio = new CampaignImageService(imagenes, texto, storage, assets, posts, cuota, cupo, usos, director);

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
                logo == null ? null : new CampaignImageRequest.Brand(logo, null),
                "Anuncia el 20% de descuento",
                "Vender",
                List.of("Minimalista"),
                "Cercano",
                "Escríbenos");
    }

    private static CampaignImageRequest peticion(String formato, List<String> recursos, String logo,
            String posicionLogo) {
        return new CampaignImageRequest(
                2,
                new CampaignImageRequest.Format(formato, "4:5", "1024x1536"),
                recursos,
                new CampaignImageRequest.Brand(logo, posicionLogo),
                "Anuncia el 20% de descuento",
                "Vender",
                List.of("Minimalista"),
                "Cercano",
                "Escríbenos");
    }

    /** Un logo como el de un negocio: hoja blanca con un bloque azul en el centro. */
    private static byte[] logoPng() throws Exception {
        BufferedImage logo = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 800; y++) {
            for (int x = 0; x < 1600; x++) {
                boolean bloque = x >= 500 && x < 1100 && y >= 300 && y < 500;
                logo.setRGB(x, y, bloque ? 0x0B2A5B : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(logo, "png", salida);
        return salida.toByteArray();
    }

    /** Cuántos píxeles azules del logo hay entre las filas dadas (proporción del alto). */
    private static int azulesEntre(byte[] jpeg, double desde, double hasta) throws Exception {
        BufferedImage imagen = ImageIO.read(new ByteArrayInputStream(jpeg));
        int n = 0;
        for (int y = (int) (imagen.getHeight() * desde); y < (int) (imagen.getHeight() * hasta); y++) {
            for (int x = 0; x < imagen.getWidth(); x++) {
                int p = imagen.getRGB(x, y);
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                if (b > 60 && b < 130 && r < 50 && g < 80) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String claveDe(String url) {
        return "media/" + WORKSPACE + "/" + url.hashCode() + ".jpg";
    }

    /** R2 sirve un contenido distinto según el archivo pedido. */
    private void r2SirveSegun(java.util.Map<String, byte[]> porUrl) {
        when(storage.descargar(anyString(), any(Path.class))).thenAnswer(i -> {
            String clave = i.getArgument(0);
            for (var entrada : porUrl.entrySet()) {
                if (claveDe(entrada.getKey()).equals(clave)) {
                    Files.write(i.getArgument(1), entrada.getValue());
                    return true;
                }
            }
            return false;
        });
    }

    private void assetsPorUrl() {
        when(assets.findByUrlIn(anyList())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream()
                .map(u -> asset(u, u.endsWith("logo.png") ? "image/png" : "image/jpeg")).toList());
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
    @DisplayName("con foto y logo: la IA recibe solo la foto y el logo real se pega después")
    void conFotoYLogo() throws Exception {
        String foto = "https://cdn.test/foto.jpg";
        String logo = "https://cdn.test/logo.png";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(foto, new byte[] { 7, 7, 7 }, logo, logoPng()));
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 10, 20, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(foto), logo));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenAiImageClient.Referencia>> referencias = ArgumentCaptor.forClass(List.class);
        verify(imagenes).editar(prompt.capture(), referencias.capture(), eq("1024x1536"));
        // El logo NO viaja a la IA: solo la foto.
        assertThat(referencias.getValue()).hasSize(1);
        assertThat(referencias.getValue().get(0).contentType()).isEqualTo("image/jpeg");
        assertThat(prompt.getValue())
                .contains("Tacos Doña Mary")
                .contains("HEADLINE: \"20% esta semana\"")
                .contains("cut to 4:5")
                .contains("logo will be placed there afterwards")
                .contains("top-left")
                .contains("Do not draw any logo")
                .doesNotContain("last reference image");
        verify(imagenes, never()).generar(anyString(), anyString());

        // El logo real quedó pegado arriba a la izquierda (lo de siempre en una publicación).
        assertThat(azulesEntre(subidos.get(0), 0.0, 0.5)).isGreaterThan(500);
        assertThat(azulesEntre(subidos.get(0), 0.5, 1.0)).isZero();
    }

    @Test
    @DisplayName("la posición que pide la app se respeta: arriba a la izquierda")
    void posicionPedida() throws Exception {
        String logo = "https://cdn.test/logo.png";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(logo, logoPng()));
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 0, 0, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(), logo, "TOP_LEFT"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imagenes).generar(prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("top-left");
        assertThat(azulesEntre(subidos.get(0), 0.0, 0.5)).isGreaterThan(500);
        assertThat(azulesEntre(subidos.get(0), 0.5, 1.0)).isZero();
    }

    @Test
    @DisplayName("en una historia el logo sube por defecto, lejos de la caja de respuesta")
    void historiaLogoArriba() throws Exception {
        String logo = "https://cdn.test/logo.png";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(logo, logoPng()));
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 0, 0, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("story", List.of(), logo));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imagenes).generar(prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("top-center").contains("x=130..894, y=240..1230");
        assertThat(azulesEntre(subidos.get(0), 0.0, 0.5)).isGreaterThan(300);
        assertThat(azulesEntre(subidos.get(0), 0.5, 1.0)).isZero();
    }

    @Test
    @DisplayName("con NONE no se pone logo ni se reserva espacio")
    void sinLogo() throws Exception {
        String logo = "https://cdn.test/logo.png";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(logo, logoPng()));
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 0, 0, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(), logo, "NONE"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imagenes).generar(prompt.capture(), anyString());
        assertThat(prompt.getValue()).doesNotContain("will be placed there afterwards");
        verify(storage, never()).descargar(anyString(), any(Path.class));
        assertThat(azulesEntre(subidos.get(0), 0.0, 1.0)).isZero();
    }

    @Test
    @DisplayName("un logo que no se puede leer no tumba la campaña: sale sin logo")
    void logoIlegible() throws Exception {
        String logo = "https://cdn.test/logo.png";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(logo, new byte[] { 1, 2, 3 }));
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0xFF0000), 0, 0, "gpt-image-1.5"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("post", List.of(), logo));

        assertThat(respuesta.imageUrls()).hasSize(1);
        assertThat(subidos).hasSize(1);
    }

    @Test
    @DisplayName("varias fotos en una publicación son contexto de UNA sola imagen, no un carrusel")
    void variasFotosSonContexto() throws Exception {
        List<String> urls = List.of("https://cdn.test/a.jpg", "https://cdn.test/b.jpg", "https://cdn.test/c.jpg");
        assetsPorUrl();
        r2Sirve(new byte[] { 1 });
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 5, 5, "gpt-image-1.5"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("post", urls, null));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenAiImageClient.Referencia>> referencias = ArgumentCaptor.forClass(List.class);
        verify(imagenes, times(1)).editar(prompt.capture(), referencias.capture(), anyString());
        assertThat(referencias.getValue()).hasSize(3);
        assertThat(prompt.getValue()).contains("Photo 1 is the hero").contains("context only");
        assertThat(respuesta.imageUrls()).hasSize(1);
        assertThat(respuesta.creditsUsed()).isEqualTo(1);
        verify(cupo).exigirCupoImagenes(1);
    }

    @Test
    @DisplayName("en una publicación solo se usan cuatro fotos de contexto")
    void maximoCuatroFotos() throws Exception {
        List<String> urls = List.of("https://cdn.test/a.jpg", "https://cdn.test/b.jpg", "https://cdn.test/c.jpg",
                "https://cdn.test/d.jpg", "https://cdn.test/e.jpg");
        assetsPorUrl();
        r2Sirve(new byte[] { 1 });
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 5, 5, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", urls, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenAiImageClient.Referencia>> referencias = ArgumentCaptor.forClass(List.class);
        verify(imagenes).editar(anyString(), referencias.capture(), anyString());
        assertThat(referencias.getValue()).hasSize(4);
    }

    @Test
    @DisplayName("el logo elegido como foto se quita de las fotos: se pega solo, no es una diapositiva")
    void logoEntreLasFotos() throws Exception {
        String a = "https://cdn.test/a.jpg";
        String b = "https://cdn.test/b.jpg";
        String logo = "https://cdn.test/logo.png";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(a, new byte[] { 1 }, b, new byte[] { 2 }, logo, logoPng()));
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 1, 1, "gpt-image-1.5"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("carousel", List.of(a, logo, b), logo));

        // Dos diapositivas (a y b), no tres.
        verify(imagenes, times(2)).editar(anyString(), anyList(), anyString());
        verify(cupo).exigirCupoImagenes(2);
        assertThat(respuesta.imageUrls()).hasSize(2);
    }

    @Test
    @DisplayName("un carrusel de una foto más el logo no llega a dos fotos y lo dice")
    void carruselConLogoYUnaFoto() {
        String logo = "https://cdn.test/logo.png";

        assertThatThrownBy(() -> servicio.generar(usuario,
                peticion("carousel", List.of("https://cdn.test/a.jpg", logo), logo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tu logo no cuenta");

        verify(imagenes, never()).editar(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("el prompt pide márgenes seguros según el recorte del formato")
    void zonaSeguraEnElPrompt() throws Exception {
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 0, 0, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(), null));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imagenes).generar(prompt.capture(), anyString());
        assertThat(prompt.getValue())
                .contains("x=64..960, y=200..1336")
                .contains("letter for letter")
                .contains("with their accents")
                .contains("no small print")
                .contains("HEADLINE: \"20% esta semana\"");
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

    // -------------------------------------------------------- director de arte

    private static ArtDirector.Brief plan(String layout, int heroe, String caption) {
        return new ArtDirector.Brief(layout, heroe, "Warm afternoon light, natural grade.",
                "Obra segura y a tiempo", "Manzanillo, Colima", "Cotiza hoy", caption);
    }

    @Test
    @DisplayName("con director: el plan manda la composición, los textos y la foto protagonista")
    void conDirector() throws Exception {
        String a = "https://cdn.test/a.jpg";
        String b = "https://cdn.test/b.jpg";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(a, new byte[] { 1 }, b, new byte[] { 2 }));
        when(director.disponible()).thenReturn(true);
        when(director.dirigir(any())).thenReturn(Optional.of(plan("framed_photo", 2, "Caption del director")));
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 1, 1, "gpt-image-1.5"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("post", List.of(a, b), null));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenAiImageClient.Referencia>> referencias = ArgumentCaptor.forClass(List.class);
        verify(imagenes).editar(prompt.capture(), referencias.capture(), anyString());
        assertThat(prompt.getValue())
                .contains("HEADLINE: \"Obra segura y a tiempo\"")
                .contains("SUBTITLE: \"Manzanillo, Colima\"")
                // El botón lleva lo que la persona escribió, no lo que sugirió el director.
                .contains("BUTTON: \"Escríbenos\"")
                .contains("solid background in the primary brand color")
                .contains("Warm afternoon light");
        // La foto protagonista (la 2) va PRIMERA: para el modelo, la primera manda.
        assertThat(referencias.getValue().get(0).bytes()).containsExactly(2);
        assertThat(referencias.getValue().get(1).bytes()).containsExactly(1);
        assertThat(respuesta.headline()).isEqualTo("Obra segura y a tiempo");
        assertThat(respuesta.supportingCopy()).isEqualTo("Manzanillo, Colima");
        assertThat(respuesta.caption()).isEqualTo("Caption del director");
        // El plan ya trae el caption: no se paga otra llamada de texto.
        verify(texto, never()).completeJson(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("el director recibe las fotos, los colores del logo y el perfil del negocio")
    void contextoDelDirector() throws Exception {
        String foto = "https://cdn.test/foto.jpg";
        String logo = "https://cdn.test/logo.png";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(foto, new byte[] { 7 }, logo, logoPng()));
        when(director.disponible()).thenReturn(true);
        when(director.dirigir(any())).thenReturn(Optional.of(plan("photo_bottom_band", 0, "c")));
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 1, 1, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(foto), logo));

        ArgumentCaptor<ArtDirector.Contexto> contexto = ArgumentCaptor.forClass(ArtDirector.Contexto.class);
        verify(director).dirigir(contexto.capture());
        assertThat(contexto.getValue().fotoUrls()).containsExactly(foto);
        assertThat(contexto.getValue().negocio()).isEqualTo("Tacos Doña Mary");
        assertThat(contexto.getValue().giro()).isEqualTo("Restaurante");
        assertThat(contexto.getValue().idea()).isEqualTo("Anuncia el 20% de descuento");
        assertThat(contexto.getValue().formato()).isEqualTo("post");
        // El azul del logo, leído del archivo real.
        assertThat(contexto.getValue().paleta()).hasSize(1);
        assertThat(contexto.getValue().paleta().get(0)).isEqualTo("#0B2A5B");
    }

    @Test
    @DisplayName("los colores del logo llegan al prompt de imagen con su código")
    void paletaEnElPrompt() throws Exception {
        String logo = "https://cdn.test/logo.png";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(logo, logoPng()));
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 0, 0, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(), logo));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imagenes).generar(prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("#0B2A5B").contains("brand colors, read from the logo");
    }

    @Test
    @DisplayName("si el director falla la campaña sigue: el texto lo escribe el modelo de siempre y va literal en la imagen")
    void directorFalla() throws Exception {
        when(director.disponible()).thenReturn(true);
        when(director.dirigir(any())).thenReturn(Optional.empty());
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 0, 0, "gpt-image-1.5"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("post", List.of(), null));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imagenes).generar(prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("HEADLINE: \"20% esta semana\"").contains("solid panel");
        assertThat(respuesta.caption()).isEqualTo("Ven por tu descuento");
        verify(texto).completeJson(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("un plan sin caption pide solo el caption al modelo de texto")
    void planSinCaption() throws Exception {
        when(director.disponible()).thenReturn(true);
        when(director.dirigir(any())).thenReturn(Optional.of(plan("photo_top_title", 0, "")));
        when(imagenes.generar(anyString(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 0, 0, "gpt-image-1.5"));

        CampaignImageResponse respuesta = servicio.generar(usuario, peticion("post", List.of(), null));

        assertThat(respuesta.headline()).isEqualTo("Obra segura y a tiempo");
        assertThat(respuesta.caption()).isEqualTo("Ven por tu descuento");
    }

    @Test
    @DisplayName("un carrusel no usa director: cada diapositiva sale con su foto")
    void carruselSinDirector() throws Exception {
        String a = "https://cdn.test/a.jpg";
        String b = "https://cdn.test/b.jpg";
        assetsPorUrl();
        r2Sirve(new byte[] { 1 });
        when(director.disponible()).thenReturn(true);
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 1, 1, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("carousel", List.of(a, b), null));

        verify(director, never()).dirigir(any());
    }

    @Test
    @DisplayName("una foto protagonista fuera de rango no reordena nada")
    void heroeInvalido() throws Exception {
        String a = "https://cdn.test/a.jpg";
        String b = "https://cdn.test/b.jpg";
        assetsPorUrl();
        r2SirveSegun(java.util.Map.of(a, new byte[] { 1 }, b, new byte[] { 2 }));
        when(director.disponible()).thenReturn(true);
        when(director.dirigir(any())).thenReturn(Optional.of(plan("photo_bottom_band", 9, "c")));
        when(imagenes.editar(anyString(), anyList(), anyString()))
                .thenReturn(new Resultado(png(0x808080), 1, 1, "gpt-image-1.5"));

        servicio.generar(usuario, peticion("post", List.of(a, b), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpenAiImageClient.Referencia>> referencias = ArgumentCaptor.forClass(List.class);
        verify(imagenes).editar(anyString(), referencias.capture(), anyString());
        assertThat(referencias.getValue().get(0).bytes()).containsExactly(1);
    }
}
