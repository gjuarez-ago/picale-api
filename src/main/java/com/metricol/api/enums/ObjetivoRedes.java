package com.metricol.api.enums;

/**
 * Lo que el negocio quiere conseguir con sus redes. Se pregunta al registrarse
 * y se puede cambiar despues.
 *
 * <p>No es un dato de adorno: viaja al prompt del Redactor. Un texto para
 * "conseguir mas clientes" pide una llamada a la accion; uno para "crear
 * comunidad" pide conversacion. Sin esto la IA escribe siempre el mismo tipo
 * de publicacion, y preguntarlo al registrarse seria pedir un dato para nada.
 *
 * <p>Enum y no texto libre —al reves que el giro— porque son cinco opciones
 * fijas de una pantalla con botones: aqui no hay nada que la persona pueda
 * escribir que no este en la lista.
 */
public enum ObjetivoRedes {

    MAS_CLIENTES(
            "Conseguir mas clientes",
            "atraer clientes nuevos: cierra invitando a escribir, visitar o preguntar"),

    VENDER_MAS(
            "Vender mas",
            "vender algo concreto: deja claro que se ofrece y como comprarlo"),

    DAR_A_CONOCER(
            "Dar a conocer mi negocio",
            "que mas gente sepa que existe: cuenta quien es y que hace distinto"),

    COMUNIDAD(
            "Crear comunidad",
            "conversar con quien ya lo sigue: pregunta y habla de tu a tu"),

    CONSTANCIA(
            "Mantener mis redes activas",
            "estar presente sin cansar: algo util y sencillo, sin vender en cada linea");

    /** Como se lee en la pantalla. */
    private final String label;

    /** Como se le explica a la IA, en una linea que entra en el prompt. */
    private final String instruccion;

    ObjetivoRedes(String label, String instruccion) {
        this.label = label;
        this.instruccion = instruccion;
    }

    public String getLabel() {
        return label;
    }

    public String getInstruccion() {
        return instruccion;
    }
}
