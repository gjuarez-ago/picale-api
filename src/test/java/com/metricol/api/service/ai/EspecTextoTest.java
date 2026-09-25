package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;

/**
 * Los topes de texto de cada red, y el del campo que va una sola vez.
 *
 * <p>Esto se prueba porque pasarse no se nota a medias: la red no recorta el
 * sobrante, tira la publicación entera con un 400 que no dice qué pasó hasta
 * que se le lee el cuerpo a la respuesta. Ya ocurrió dos veces —TikTok con 115
 * caracteres, Facebook con 359— y las dos se descubrieron en producción.
 */
class EspecTextoTest {

    /** El texto que tiró la publicación del 11 de septiembre. */
    private static String largo(int caracteres) {
        return "palabra ".repeat(caracteres / 8 + 1).substring(0, caracteres);
    }

    @Test
    @DisplayName("Facebook admite 300 en el caption, no los 2000 de un post")
    void facebookSonTrescientos() {
        // 359 es el largo exacto del que rechazó upload-post. Con el tope
        // viejo de 2000 pasaba entero y se perdía la publicación. El 300
        // (24 sep 2026) sube un poco el 255 anterior sin volver a ese caso.
        String recortado = EspecTexto.de(Platform.FACEBOOK).recortar(largo(359));

        assertThat(recortado).hasSizeLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("La más estricta de varias redes es la que menos admite")
    void laMasEstrictaEsLaQueMenosAdmite() {
        // LinkedIn admite 3000 y TikTok 300 en el caption: manda TikTok.
        EspecTexto estricta = EspecTexto.masEstricta(
                List.of(Platform.LINKEDIN, Platform.TIKTOK, Platform.INSTAGRAM));

        assertThat(estricta.maxCaracteres()).isEqualTo(300);
    }

    @Test
    @DisplayName("El título es aparte del caption: 90, una línea, sin hashtags ni punto final")
    void elTituloVaAparte() {
        assertThat(EspecTexto.TITULO_MAX).isEqualTo(90);
        assertThat(EspecTexto.recortarTitulo("  Mallas ciclónicas\nsegunda línea ")).isEqualTo("Mallas ciclónicas");
        assertThat(EspecTexto.recortarTitulo(largo(120))).hasSizeLessThanOrEqualTo(90);
        assertThat(EspecTexto.recortarTitulo("   ")).isNull();
        assertThat(EspecTexto.recortarTitulo(null)).isNull();
        // YouTube rechaza < y > en el título, y el título es uno para todas.
        assertThat(EspecTexto.recortarTitulo("Promo <2x1> hoy")).isEqualTo("Promo 2x1 hoy");
        assertThat(EspecTexto.recortarTitulo("<>")).isNull();
    }

    @Test
    @DisplayName("Sin título escrito se saca uno del caption: la primera frase, sin hashtags")
    void elTituloDeRespaldoSaleDelCaption() {
        String caption = "La seguridad en tu predio es vital. Las mallas ciclónicas ayudan. #Seguridad #CMRG";

        assertThat(EspecTexto.tituloDesde(caption)).isEqualTo("La seguridad en tu predio es vital");
        assertThat(EspecTexto.tituloDesde("#Solo #Hashtags")).isNull();
        assertThat(EspecTexto.tituloDesde(largo(300))).hasSizeLessThanOrEqualTo(90);
    }

    @Test
    @DisplayName("Con Facebook entre las elegidas, el común cabe en 300")
    void conFacebookElComunCabeEnTrescientos() {
        // El caso real: video a Facebook e Instagram con un texto de 359.
        EspecTexto estricta =
                EspecTexto.masEstricta(List.of(Platform.FACEBOOK, Platform.INSTAGRAM));

        assertThat(estricta.recortar(largo(359))).hasSizeLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("Una sola red se ajusta a la suya, sin recortar de más")
    void unaSolaRedUsaSuPropioTope() {
        assertThat(EspecTexto.masEstricta(List.of(Platform.LINKEDIN)).maxCaracteres())
                .isEqualTo(3000);
    }

    @Test
    @DisplayName("Un texto que ya cabe no se toca")
    void loQueCabeNoSeToca() {
        String corto = "Tres palabras justas.";

        assertThat(EspecTexto.de(Platform.FACEBOOK).recortar(corto)).isEqualTo(corto);
    }

    @Test
    @DisplayName("Ninguna red se pasa de lo que admite upload-post")
    void ningunaRedSePasaDeSuTope() {
        // Barre las cinco: el fallo de Facebook estuvo meses ahí porque nadie
        // volvió a mirar los topes después de arreglar el de TikTok.
        for (Platform red : Platform.values()) {
            EspecTexto espec = EspecTexto.de(red);
            assertThat(espec.recortar(largo(4000)))
                    .as("texto recortado para %s", red)
                    .hasSizeLessThanOrEqualTo(espec.maxCaracteres());
        }
    }
}
