package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.Post;

/**
 * Cuándo sale una publicación con música de fondo en TikTok.
 *
 * <p>Lo que más importa aquí no es que la ponga, es que NO la ponga donde
 * haría daño: el error de poner música a un aviso de luto no se puede
 * deshacer, y el de no ponérsela a unos tacos no se lo nota nadie.
 */
class MusicaDeTikTokTest {

    private static final List<String> TIKTOK = List.of("tiktok");
    private static final List<String> TIKTOK_E_INSTAGRAM = List.of("tiktok", "instagram");

    private static Post post(Boolean decidido, String caption) {
        Post p = new Post();
        p.setMusicaAutomatica(decidido);
        p.setCaption(caption);
        return p;
    }

    // ------------------------------------------------- dónde aplica

    @Test
    @DisplayName("un carrusel de fotos a TikTok sale con música sin que nadie lo pida")
    void fotosATikTok() {
        assertThat(MusicaDeTikTok.decidir(post(null, "Tacos al pastor desde las 7"), TIKTOK, false)).isTrue();
        assertThat(MusicaDeTikTok.decidir(post(null, "Promo del día"), TIKTOK_E_INSTAGRAM, false)).isTrue();
    }

    @Test
    @DisplayName("en video no aplica: ahí el audio es el del propio video")
    void enVideoNo() {
        assertThat(MusicaDeTikTok.decidir(post(null, "Mira cómo quedó"), TIKTOK, true)).isFalse();
    }

    @Test
    @DisplayName("sin TikTok en el envío no se manda el campo")
    void sinTikTok() {
        assertThat(MusicaDeTikTok.decidir(post(null, "Hola"), List.of("instagram", "facebook"), false)).isFalse();
    }

    @Test
    @DisplayName("si TikTok se cayó por cuota, tampoco")
    void tiktokFueraDelEnvio() {
        assertThat(MusicaDeTikTok.aplicaSola(List.of("facebook"), false, "Promo", null)).isFalse();
    }

    // ------------------------------------------------- quién manda

    @Test
    @DisplayName("si la persona la prendió, va, aunque el texto fuera de los que callan")
    void laPersonaMandaAlPrender() {
        assertThat(MusicaDeTikTok.decidir(post(true, "Lamentamos el fallecimiento"), TIKTOK, false)).isTrue();
    }

    @Test
    @DisplayName("si la persona la apagó, no va, aunque todo lo demás encaje")
    void laPersonaMandaAlApagar() {
        assertThat(MusicaDeTikTok.decidir(post(false, "Tacos al pastor"), TIKTOK, false)).isFalse();
    }

    @Test
    @DisplayName("si la persona la prendió, ni siquiera en video se le lleva la contraria")
    void loQueDecidioSeRespetaSiempre() {
        // Que el proveedor lo ignore en video es cosa suya; aquí no se
        // sobrescribe una decisión de la persona por creer saber más.
        assertThat(MusicaDeTikTok.decidir(post(true, "Mira"), TIKTOK, true)).isTrue();
    }

    // ------------------------------------------------- cuándo se calla

    @Test
    @DisplayName("un luto nunca sale con música, con acentos o sin ellos")
    void luto() {
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "Lamentamos el fallecimiento de Don José", null))
                .isFalse();
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "Nuestro pesame a la familia", null)).isFalse();
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "Nuestro pésame a la familia", null)).isFalse();
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "QEPD, siempre en nuestra memoria", null)).isFalse();
    }

    @Test
    @DisplayName("una disculpa, un cierre o una emergencia tampoco")
    void malasNoticias() {
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "Una disculpa por la demora de ayer", null)).isFalse();
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "Hoy permanecemos CERRADOS por mantenimiento", null))
                .isFalse();
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "Aviso importante: suspendemos el servicio", null))
                .isFalse();
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "Se busca: perrito perdido en la colonia", null))
                .isFalse();
    }

    @Test
    @DisplayName("el título también se mira, no solo el texto largo")
    void elTituloTambienCuenta() {
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "Gracias a todos", "Comunicado urgente")).isFalse();
    }

    @Test
    @DisplayName("una publicación sin texto sí lleva música: suele ser puro producto")
    void sinTexto() {
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, null, null)).isTrue();
        assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, "   ", null)).isTrue();
    }

    @Test
    @DisplayName("lo normal del día a día sí lleva música")
    void loNormalSiSuena() {
        for (String texto : List.of(
                "Nuevo sabor de la semana 🍓",
                "Promoción 2x1 hasta el domingo",
                "Así preparamos el pan cada mañana",
                "Ya abrimos, pásale")) {
            assertThat(MusicaDeTikTok.aplicaSola(TIKTOK, false, texto, null))
                    .describedAs(texto)
                    .isTrue();
        }
    }
}
