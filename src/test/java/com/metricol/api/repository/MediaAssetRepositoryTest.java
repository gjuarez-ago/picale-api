package com.metricol.api.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.PostStatus;

/**
 * Las consultas que deciden qué se borra del bucket.
 *
 * <p>Van contra la base de verdad y no contra un doble porque lo que puede
 * fallar es justo lo que un doble daría por bueno: el {@code not exists} sobre
 * {@code post_media}, que es una tabla que nadie escribe a mano —la genera el
 * {@code @ElementCollection} de {@link Post}— y cuyo nombre no aparece en
 * ninguna entidad.
 *
 * <p>La prueba que importa es la del borrador: si esa consulta se equivoca,
 * la limpieza le borra a alguien las fotos de algo que estaba escribiendo.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // Nada de esto publica ni limpia: los procesos de fondo solo añadirían
        // ruido y consultas mientras corre la prueba.
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000"
})
class MediaAssetRepositoryTest {

    private static final String WS = "33333333-3333-3333-3333-333333333333";

    /**
     * Tope de las consultas, muy por encima del de producción (50).
     *
     * <p>Las consultas traen los más VIEJOS primero, y la base del perfil dev
     * puede ser un Postgres que persiste entre corridas. Con 50, en cuanto se
     * juntaban cincuenta filas de corridas anteriores, lo recién creado quedaba
     * fuera de la lista: "encuentra" fallaba, y las pruebas de "no sale"
     * pasaban sin comprobar nada.
     */
    private static final int TOPE = 100_000;

    @Autowired
    private MediaAssetRepository mediaAssets;

    @Autowired
    private PostRepository posts;

    /** Todo lo de esta prueba pasa dentro del workspace de arriba. */
    private void enElWorkspace(Runnable accion) {
        TenantIdentifierResolver.comoTenant(WS, accion);
    }

    private MediaAsset subir(String nombre) {
        return mediaAssets.save(MediaAsset.builder()
                .fileName(nombre)
                .url("https://cdn.test/media/" + WS + "/" + nombre)
                .storageKey("media/" + WS + "/" + nombre)
                .type(MediaType.IMAGE)
                .contentType("image/jpeg")
                .sizeBytes(1024L)
                .status(MediaAssetStatus.READY)
                .build());
    }

    private List<String> idsSinUsar() {
        // Un límite en el futuro: las filas se acaban de crear, y el plazo real
        // —un día— dejaría fuera todo lo que esta prueba escribe.
        return mediaAssets.findListosSinUsar(LocalDateTime.now().plusMinutes(1), TOPE).stream()
                .map(fila -> String.valueOf(fila[0]))
                .toList();
    }

    @Test
    @DisplayName("Un archivo que ninguna publicación usa sale en la lista")
    void encuentraElQueNadieUsa() {
        enElWorkspace(() -> {
            MediaAsset huerfano = subir("nadie-me-usa.jpg");

            assertThat(idsSinUsar()).contains(huerfano.getId().toString());
        });
    }

    @Test
    @DisplayName("La foto de un BORRADOR no se toca")
    void respetaLasFotosDeUnBorrador() {
        enElWorkspace(() -> {
            MediaAsset enBorrador = subir("estoy-escribiendo.jpg");

            posts.save(Post.builder()
                    .caption("A medio escribir")
                    .mediaUrls(new ArrayList<>(List.of(enBorrador.getUrl())))
                    .mediaType(MediaType.IMAGE)
                    .status(PostStatus.DRAFT)
                    .build());

            assertThat(idsSinUsar()).doesNotContain(enBorrador.getId().toString());
        });
    }

    @Test
    @DisplayName("La foto de una publicación ya publicada tampoco")
    void respetaLasFotosDeLoPublicado() {
        enElWorkspace(() -> {
            MediaAsset publicada = subir("ya-salio.jpg");

            posts.save(Post.builder()
                    .caption("Ya salió")
                    .mediaUrls(new ArrayList<>(List.of(publicada.getUrl())))
                    .mediaType(MediaType.IMAGE)
                    .status(PostStatus.PUBLISHED)
                    .publishedAt(LocalDateTime.now())
                    .build());

            assertThat(idsSinUsar()).doesNotContain(publicada.getId().toString());
        });
    }

    @Test
    @DisplayName("En un carrusel cuentan todas las fotos, no solo la primera")
    void respetaElCarruselEntero() {
        enElWorkspace(() -> {
            MediaAsset primera = subir("carrusel-1.jpg");
            MediaAsset ultima = subir("carrusel-6.jpg");

            posts.save(Post.builder()
                    .caption("Seis fotos")
                    .mediaUrls(new ArrayList<>(List.of(primera.getUrl(), ultima.getUrl())))
                    .mediaType(MediaType.IMAGE)
                    .status(PostStatus.DRAFT)
                    .build());

            assertThat(idsSinUsar())
                    .doesNotContain(primera.getId().toString())
                    .doesNotContain(ultima.getId().toString());
        });
    }

    @Test
    @DisplayName("Un archivo a medio subir no es asunto de esta limpieza")
    void ignoraLosPendientes() {
        enElWorkspace(() -> {
            MediaAsset pendiente = mediaAssets.save(MediaAsset.builder()
                    .fileName("a-medio-subir.jpg")
                    .url("https://cdn.test/media/" + WS + "/a-medio-subir.jpg")
                    .storageKey("media/" + WS + "/a-medio-subir.jpg")
                    .type(MediaType.IMAGE)
                    .contentType("image/jpeg")
                    .sizeBytes(1024L)
                    // De estos se ocupa MediaOrphanWorker, que sabe preguntarle
                    // a R2 si el archivo llegó. Borrarlo aquí tiraría una
                    // subida que puede estar en curso.
                    .status(MediaAssetStatus.PENDING)
                    .build());

            assertThat(idsSinUsar()).doesNotContain(pendiente.getId().toString());
        });
    }

    @Test
    @DisplayName("Las claves vivas salen con su workspace, para barrer derivados")
    void devuelveLasClavesVivasConSuWorkspace() {
        enElWorkspace(() -> {
            MediaAsset asset = subir("tengo-derivados.jpg");

            List<Object[]> vivas = mediaAssets.findClavesVivas();

            assertThat(vivas)
                    .anySatisfy(fila -> {
                        assertThat(String.valueOf(fila[0])).isEqualTo(WS);
                        assertThat(String.valueOf(fila[1])).isEqualTo(asset.getStorageKey());
                    });
        });
    }

    @Test
    @DisplayName("Un archivo recién subido tiene su plazo antes de que lo miren")
    void respetaElPlazo() {
        enElWorkspace(() -> {
            MediaAsset reciente = subir("acabo-de-llegar.jpg");

            // Con el límite en el pasado —que es como corre de verdad: ahora
            // menos un día— no debe salir todavía. Es lo que protege a quien
            // está componiendo sin prisa.
            List<Object[]> conPlazoReal =
                    mediaAssets.findListosSinUsar(LocalDateTime.now().minusHours(24), TOPE);

            assertThat(conPlazoReal)
                    .noneSatisfy(fila ->
                            assertThat(String.valueOf(fila[0])).isEqualTo(reciente.getId().toString()));
        });
    }

    private MediaAsset subirVideo(String nombre) {
        return mediaAssets.save(MediaAsset.builder()
                .fileName(nombre)
                .url("https://cdn.test/media/" + WS + "/" + nombre)
                .storageKey("media/" + WS + "/" + nombre)
                .type(MediaType.VIDEO)
                .contentType("video/mp4")
                .sizeBytes(90L * 1024 * 1024)
                .status(MediaAssetStatus.READY)
                .thumbnailUrl("https://cdn.test/derivados/miniaturas/x.jpg")
                .build());
    }

    private Post publicada(MediaAsset medio, LocalDateTime cuando) {
        return posts.save(Post.builder()
                .caption("Ya salió")
                .mediaUrls(new ArrayList<>(List.of(medio.getUrl())))
                .mediaType(medio.getType())
                .status(PostStatus.PUBLISHED)
                .publishedAt(cuando)
                .build());
    }

    private List<String> idsLiberables() {
        return mediaAssets.findVideosLiberables(LocalDateTime.now().minusDays(30), TOPE).stream()
                .map(fila -> String.valueOf(fila[0]))
                .toList();
    }

    @Test
    @DisplayName("Un video publicado hace más de 30 días se puede liberar")
    void liberaElVideoViejoYaPublicado() {
        enElWorkspace(() -> {
            MediaAsset video = subirVideo("viejo.mp4");
            publicada(video, LocalDateTime.now().minusDays(60));

            assertThat(idsLiberables()).contains(video.getId().toString());
        });
    }

    @Test
    @DisplayName("Un video publicado ayer todavía no")
    void respetaElPlazoDeLiberacion() {
        enElWorkspace(() -> {
            MediaAsset video = subirVideo("reciente.mp4");
            publicada(video, LocalDateTime.now().minusDays(1));

            assertThat(idsLiberables()).doesNotContain(video.getId().toString());
        });
    }

    @Test
    @DisplayName("Las FOTOS no se liberan nunca, por viejas que sean")
    void noLiberaFotos() {
        enElWorkspace(() -> {
            // Son la biblioteca que se reutiliza y pesan mil veces menos.
            MediaAsset foto = subir("foto-antigua.jpg");
            publicada(foto, LocalDateTime.now().minusDays(365));

            assertThat(idsLiberables()).doesNotContain(foto.getId().toString());
        });
    }

    @Test
    @DisplayName("Un video que además está en una programada NO se toca")
    void noLiberaSiSigueEsperandoSalir() {
        enElWorkspace(() -> {
            // El caso que hace daño: el mismo archivo en una publicada de hace
            // dos meses y en una programada para mañana. Liberarlo dejaría a la
            // segunda sin nada que publicar.
            MediaAsset video = subirVideo("reutilizado.mp4");
            publicada(video, LocalDateTime.now().minusDays(60));
            posts.save(Post.builder()
                    .caption("Sale mañana")
                    .mediaUrls(new ArrayList<>(List.of(video.getUrl())))
                    .mediaType(MediaType.VIDEO)
                    .status(PostStatus.SCHEDULED)
                    .scheduledAt(LocalDateTime.now().plusDays(1))
                    .build());

            assertThat(idsLiberables()).doesNotContain(video.getId().toString());
        });
    }

    @Test
    @DisplayName("Lo liberado deja de contar para la cuota")
    void loLiberadoNoOcupa() {
        enElWorkspace(() -> {
            MediaAsset video = subirVideo("pesa.mp4");
            long conElVideo = mediaAssets.espacioUsado();

            video.setStatus(MediaAssetStatus.RELEASED);
            mediaAssets.save(video);

            // Es el punto de todo esto: devolverle el espacio a la persona sin
            // que tenga que borrar su historial.
            assertThat(mediaAssets.espacioUsado()).isEqualTo(conElVideo - video.getSizeBytes());
        });
    }
}
