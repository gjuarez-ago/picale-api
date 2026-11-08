package com.metricol.api.enums;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Lo que el negocio quiere conseguir con sus redes. Se pregunta al registrarse
 * y se puede cambiar despues.
 *
 * <p>No es un dato de adorno: viaja al prompt del Redactor. Un texto para
 * "conseguir mas clientes" pide una llamada a la accion; uno para "crear
 * comunidad" pide conversacion. Sin esto la IA escribe siempre el mismo tipo
 * de publicacion, y preguntarlo al registrarse seria pedir un dato para nada.
 *
 * <p>Enum y no texto libre —al reves que el giro— porque son opciones fijas
 * de una pantalla con botones. Se eligen hasta {@link #MAX_ELEGIDOS}; el
 * primero es el principal.
 *
 * <p>Los que se agregaron despues de los cinco primeros traen un
 * {@link #getLegado() legado}: el de los cinco al que mas se parecen. Es lo
 * que se le contesta a la app ya instalada, que solo conoce los cinco y
 * tomaria uno nuevo por "sin objetivo" (y mandaria a la persona a llenarlo).
 */
public enum ObjetivoRedes {

    MAS_CLIENTES(
            "Conseguir mas clientes",
            // "Cierra invitando a escribir" a secas hacía que TODAS cerraran con
            // la misma fórmula de WhatsApp (4 nov 2026): se lee como robot.
            "atraer clientes nuevos: que se entienda que se puede contratar; invitar a escribir solo"
                    + " cuando el texto lo pide, con palabras propias y distintas cada vez, nunca como formula fija",
            null),

    VENDER_MAS(
            "Vender mas",
            "vender algo concreto: deja claro que se ofrece y como comprarlo",
            null),

    DAR_A_CONOCER(
            "Dar a conocer mi negocio",
            "que mas gente sepa que existe: cuenta quien es y que hace distinto",
            null),

    COMUNIDAD(
            "Crear comunidad",
            "conversar con quien ya lo sigue: pregunta y habla de tu a tu",
            null),

    CONSTANCIA(
            "Mantener mis redes activas",
            "estar presente sin cansar: algo util y sencillo, sin vender en cada linea",
            null),

    VISITAS(
            "Que me visiten en mi local",
            "que la gente vaya al local: que se sepa donde esta, cuando abre y por que vale la pena ir",
            MAS_CLIENTES),

    PEDIDOS(
            "Recibir pedidos y reservaciones",
            "que pidan o aparten: que quede claro que se puede pedir o reservar y como, sin repetir"
                    + " la misma frase en cada publicacion",
            VENDER_MAS),

    CONFIANZA(
            "Ganar confianza con mi trabajo",
            "que se vea que cumple: trabajos terminados, procesos y resultados reales, contados con hechos",
            DAR_A_CONOCER),

    EXPERTO(
            "Compartir lo que sé",
            "posicionarse como quien sabe del tema: ensenar algo util de su campo, sin vender en cada linea",
            DAR_A_CONOCER),

    QUE_REGRESEN(
            "Que mis clientes regresen",
            "que vuelvan quienes ya compraron: novedades, agradecer, recordar lo que les gusto",
            COMUNIDAD);

    /** Cuántos se pueden elegir: con más, ya no dicen qué importa. */
    public static final int MAX_ELEGIDOS = 3;

    /** Como se lee en la pantalla. */
    private final String label;

    /** Como se le explica a la IA, en una linea que entra en el prompt. */
    private final String instruccion;

    /** El de los cinco primeros al que equivale; nulo si es uno de ellos. */
    private final ObjetivoRedes legado;

    ObjetivoRedes(String label, String instruccion, ObjetivoRedes legado) {
        this.label = label;
        this.instruccion = instruccion;
        this.legado = legado;
    }

    public String getLabel() {
        return label;
    }

    public String getInstruccion() {
        return instruccion;
    }

    /** El que entiende la app ya instalada: él mismo si es de los cinco primeros. */
    public ObjetivoRedes getLegado() {
        return legado == null ? this : legado;
    }

    /** Sin nulos ni repetidos, en el orden en que se eligieron, y hasta {@link #MAX_ELEGIDOS}. */
    public static List<ObjetivoRedes> limpiar(Collection<ObjetivoRedes> elegidos) {
        if (elegidos == null) {
            return List.of();
        }
        List<ObjetivoRedes> limpios = new ArrayList<>();
        for (ObjetivoRedes o : elegidos) {
            if (o != null && !limpios.contains(o) && limpios.size() < MAX_ELEGIDOS) {
                limpios.add(o);
            }
        }
        return List.copyOf(limpios);
    }

    /** Lo que llegó al crear: la lista si viene, si no el único de la app ya instalada. */
    public static List<ObjetivoRedes> elegidos(Collection<ObjetivoRedes> lista, ObjetivoRedes unico) {
        if (lista != null && !lista.isEmpty()) {
            return limpiar(lista);
        }
        return unico == null ? List.of() : List.of(unico);
    }

    /** El principal de una lista ya limpia, o nulo. */
    public static ObjetivoRedes principal(List<ObjetivoRedes> elegidos) {
        return elegidos == null || elegidos.isEmpty() ? null : elegidos.get(0);
    }

    /** De "A,B,C" (como se guarda) a la lista. Lo que no se reconoce se ignora. */
    public static List<ObjetivoRedes> leer(String guardado) {
        if (guardado == null || guardado.isBlank()) {
            return List.of();
        }
        return limpiar(Arrays.stream(guardado.split(","))
                .map(String::strip)
                .map(c -> Arrays.stream(values()).filter(v -> v.name().equals(c)).findFirst().orElse(null))
                .filter(Objects::nonNull)
                .toList());
    }

    /** De la lista a "A,B,C", o nulo si no hay ninguno. */
    public static String escribir(Collection<ObjetivoRedes> elegidos) {
        List<ObjetivoRedes> limpios = limpiar(elegidos);
        return limpios.isEmpty() ? null : String.join(",", limpios.stream().map(Enum::name).toList());
    }
}
