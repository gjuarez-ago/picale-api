package com.metricol.api.service.media.subtitulos;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lo que la cuenta ajusta de cómo se ven sus videos, encima del estilo de
 * subtítulos: colores, letra, tamaño y cuántos letreros. Todo es opcional;
 * nulo en un campo es "lo de siempre", y así una cuenta que nunca tocó nada
 * sigue viendo sus videos igual.
 *
 * <p>Los "looks" de la web y la app (Explicador, Impacto, Limpio, Clásico) no
 * existen aquí: son combinaciones ya armadas de estos mismos ajustes más el
 * estilo, que la pantalla manda de un toque.
 *
 * @param resalte       el color de la palabra que suena y de las clave
 *                      ("#FFD400"); nulo = el de la marca o amarillo, como siempre
 * @param colorLetreros el color del recuadro de los letreros; nulo = el de resalte
 * @param letreros      cuántos letreros: pocos, normal o muchos
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisenoVideo(String resalte, String colorLetreros, Letra letra, Tamano tamano, Cantidad letreros) {

    /** La letra de los subtítulos. Las cuatro son libres (OFL) y van en la imagen del servidor. */
    public enum Letra {
        /** Redonda y gruesa: la de siempre. */
        MONTSERRAT("Montserrat ExtraBold", "Montserrat Black", 1.0),
        /** Geométrica, más amable. */
        POPPINS("Poppins ExtraBold", "Poppins Black", 0.96),
        /** Alta y apretada, de titular. */
        ANTON("Anton", "Anton", 1.2),
        /** Solo mayúsculas, de cartel. */
        BEBAS("Bebas Neue", "Bebas Neue", 1.32);

        private final String gruesa;
        private final String masGruesa;
        private final double factor;

        Letra(String gruesa, String masGruesa, double factor) {
            this.gruesa = gruesa;
            this.masGruesa = masGruesa;
            this.factor = factor;
        }

        /** El nombre con que libass la encuentra; la más gruesa es para el estilo de rebote. */
        public String nombre(boolean laMasGruesa) { return laMasGruesa ? masGruesa : gruesa; }

        /** Las letras apretadas se ven más chicas a la misma altura: se compensan. */
        public double factor() { return factor; }
    }

    public enum Tamano {
        CHICO(0.85), MEDIANO(1.0), GRANDE(1.18);

        private final double factor;

        Tamano(double factor) { this.factor = factor; }

        public double factor() { return factor; }
    }

    public enum Cantidad {
        POCOS(9, "unos dos"), NORMAL(5, "cuatro o cinco"), MUCHOS(3.5, "unos siete");

        private final double ritmo;
        private final String porMinuto;

        Cantidad(double ritmo, String porMinuto) {
            this.ritmo = ritmo;
            this.porMinuto = porMinuto;
        }

        /** Segundos entre el inicio de un letrero y el del siguiente. */
        public double ritmo() { return ritmo; }

        /** Como se le dice a la IA cuántos momentos escoger por minuto. */
        public String porMinuto() { return porMinuto; }
    }

    /** Sin nada ajustado: todo como siempre. */
    public static final DisenoVideo DE_SIEMPRE = new DisenoVideo(null, null, null, null, null);

    /** Los colores bien escritos ("#RRGGBB" en mayúsculas); uno que no es un color se quita. */
    public DisenoVideo limpio() {
        return new DisenoVideo(color(resalte), color(colorLetreros), letra, tamano, letreros);
    }

    public boolean deSiempre() {
        return resalte == null && colorLetreros == null && letra == null && tamano == null && letreros == null;
    }

    public Letra letraONormal() { return letra == null ? Letra.MONTSERRAT : letra; }

    public Tamano tamanoONormal() { return tamano == null ? Tamano.MEDIANO : tamano; }

    public Cantidad letrerosONormal() { return letreros == null ? Cantidad.NORMAL : letreros; }

    static String color(String crudo) {
        if (crudo == null) return null;
        String hex = crudo.strip().replace("#", "");
        return hex.matches("[0-9A-Fa-f]{6}") ? "#" + hex.toUpperCase(Locale.ROOT) : null;
    }
}
