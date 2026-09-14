package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.media.AdaptadorDeImagenes;
import com.metricol.api.service.media.MiniaturaDeVideo;

/**
 * Lo que decide si un archivo del bucket se borra.
 *
 * <p>Se prueba aparte de todo lo demás porque es la única parte de la limpieza
 * que puede hacer daño. El resto —listar, agrupar, pedirle a R2 que borre— es
 * fontanería; esto es el juicio, y equivocarse borra archivos de alguien.
 */
class MediaDerivadosWorkerTest {

    private static final String WS = "11111111-1111-1111-1111-111111111111";
    private static final String OTRO_WS = "22222222-2222-2222-2222-222222222222";

    private static final String CLAVE_ORIGINAL = "media/" + WS + "/una-foto.jpg";

    /** Lo que vería el worker con esa foto viva y nada más. */
    private static Map<String, Set<String>> soloLaFotoViva() {
        return Map.of(WS, Set.of(AdaptadorDeImagenes.carpetaDe(CLAVE_ORIGINAL)));
    }

    @Test
    @DisplayName("El derivado de una imagen viva se conserva")
    void conservaElDerivadoDeUnaImagenViva() {
        String derivado = AdaptadorDeImagenes.prefijoDe(UUID.fromString(WS), CLAVE_ORIGINAL)
                + "abc123.jpg";

        assertThat(MediaDerivadosWorker.estaHuerfana(derivado, soloLaFotoViva(), Set.of())).isFalse();
    }

    @Test
    @DisplayName("El derivado de una imagen que ya se borró se va con ella")
    void borraElDerivadoDeUnaImagenQueYaNoEsta() {
        String derivado = AdaptadorDeImagenes.prefijoDe(
                UUID.fromString(WS), "media/" + WS + "/otra-que-ya-no-esta.jpg") + "abc123.jpg";

        assertThat(MediaDerivadosWorker.estaHuerfana(derivado, soloLaFotoViva(), Set.of())).isTrue();
    }

    @Test
    @DisplayName("La carpeta de un workspace no reclama la de otro")
    void noCruzaWorkspaces() {
        // Misma carpeta, otro workspace: si se comparara solo el resumen sin
        // mirar de quién es, el derivado de un cliente mantendría vivo el de
        // otro —o peor, al revés.
        String derivado = AdaptadorDeImagenes.prefijoDe(UUID.fromString(OTRO_WS), CLAVE_ORIGINAL)
                + "abc123.jpg";

        assertThat(MediaDerivadosWorker.estaHuerfana(derivado, soloLaFotoViva(), Set.of())).isTrue();
    }

    @Test
    @DisplayName("Las claves planas del formato anterior sobran siempre")
    void borraLasClavesDelFormatoViejo() {
        // Así se veían antes: un solo resumen, de imagen y especificación
        // juntas, del que no se puede sacar de qué foto salió.
        String vieja = AdaptadorDeImagenes.prefijoRaiz() + WS + "/deadbeefdeadbeef.jpg";

        assertThat(MediaDerivadosWorker.estaHuerfana(vieja, soloLaFotoViva(), Set.of())).isTrue();
    }

    @Test
    @DisplayName("Un workspace sin nada vivo pierde todos sus derivados")
    void borraLosDeUnWorkspaceSinFilas() {
        String derivado = AdaptadorDeImagenes.prefijoDe(UUID.fromString(WS), CLAVE_ORIGINAL)
                + "abc123.jpg";

        assertThat(MediaDerivadosWorker.estaHuerfana(derivado, Map.of(), Set.of())).isTrue();
    }

    @Test
    @DisplayName("Una clave que no es de derivados no se toca")
    void ignoraLoQueNoEsUnDerivado() {
        // No debería llegar —solo se listan las de derivados/— pero si llegara,
        // leerla como si lo fuera acabaría borrando la foto original.
        assertThat(AdaptadorDeImagenes.leerDerivado(CLAVE_ORIGINAL)).isNull();
    }

    @Test
    @DisplayName("La clave de un derivado dice de qué imagen y de quién es")
    void laClaveSeLeeDeVuelta() {
        String derivado = AdaptadorDeImagenes.prefijoDe(UUID.fromString(WS), CLAVE_ORIGINAL)
                + "abc123.jpg";

        AdaptadorDeImagenes.RefDerivado ref = AdaptadorDeImagenes.leerDerivado(derivado);

        assertThat(ref).isNotNull();
        assertThat(ref.workspaceId()).isEqualTo(WS);
        assertThat(ref.carpeta()).isEqualTo(AdaptadorDeImagenes.carpetaDe(CLAVE_ORIGINAL));
    }

    @Test
    @DisplayName("La miniatura de un video vivo NO se borra")
    void conservaLaMiniaturaDeUnVideoVivo() {
        // El fallo que esto cierra: las miniaturas cuelgan de derivados/ pero
        // son claves planas, así que caían por el caso "no tiene la forma" y
        // la barrida las borraba todas cada noche, las de los videos vivos
        // incluidas. Se veía como videos que perdían su portada solos.
        String video = "media/" + WS + "/un-video.mp4";
        String miniatura = MiniaturaDeVideo.claveDe(video);

        assertThat(MediaDerivadosWorker.estaHuerfana(
                miniatura, Map.of(), Set.of(miniatura))).isFalse();
    }

    @Test
    @DisplayName("La miniatura de un video que ya se borró se va con él")
    void borraLaMiniaturaDeUnVideoQueYaNoEsta() {
        String miniatura = MiniaturaDeVideo.claveDe("media/" + WS + "/video-borrado.mp4");

        assertThat(MediaDerivadosWorker.estaHuerfana(
                miniatura, Map.of(), Set.of())).isTrue();
    }

    @Test
    @DisplayName("La clave de la miniatura se calcula igual que al generarla")
    void laClaveDeLaMiniaturaEsEstable() {
        // Si los dos sitios no coinciden, la barrida no reconoce ninguna y las
        // borra todas: por eso el cálculo vive en un solo método.
        String video = "media/" + WS + "/otro.mp4";

        assertThat(MiniaturaDeVideo.claveDe(video))
                .startsWith(MiniaturaDeVideo.prefijo())
                .endsWith(".jpg");
        assertThat(MiniaturaDeVideo.claveDe(video)).isEqualTo(MiniaturaDeVideo.claveDe(video));
    }
}
