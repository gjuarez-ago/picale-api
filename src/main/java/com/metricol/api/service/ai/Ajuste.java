package com.metricol.api.service.ai;

/**
 * Los retoques de un toque que se ofrecen sobre un texto ya escrito.
 *
 * <p>Son botones y no un campo libre a propósito. Pedirle a alguien que
 * escriba "qué le cambiarías" es devolverle el trabajo que esta pantalla le
 * quitó; estos cubren lo que de verdad se pide al leer un texto que casi
 * sirve, y cada uno se resuelve con un dedo.
 *
 * <p>Cada uno cambia la FORMA, nunca el fondo: los datos y el mensaje de quien
 * lo dictó siguen siendo los mismos. Por eso no hay ninguno que diga "más
 * completo" o "añade detalles" — eso sería inventar, y lo inventado sobre un
 * negocio que no es nuestro se publica igual.
 */
public enum Ajuste {

    // ---------- Largo ----------

    CORTO("Hazlo mas corto. Quita todo lo que no sea imprescindible y dejalo "
            + "en la mitad de largo o menos, sin perder el dato principal."),

    LARGO("Desarrollalo un poco mas: explica mejor lo que ya esta dicho y dale "
            + "aire, sin pasarte del limite de la red. No agregues datos nuevos "
            + "ni te inventes nada; si no hay mas que decir o ya esta cerca del "
            + "limite, dejalo como esta."),

    // ---------- Tono ----------

    VENDEDOR("Hazlo mas vendedor: gancho en la primera linea, beneficio claro "
            + "y una llamada a la accion al final. Sin exagerar ni prometer "
            + "nada que no estuviera ya en el texto."),

    PROFESIONAL("Hazlo mas profesional y sobrio: menos emojis, menos "
            + "exclamaciones, redaccion cuidada. Que siga sonando a una "
            + "persona y no a un comunicado."),

    DIVERTIDO("Hazlo mas divertido y ligero, con chispa. Sin chistes forzados "
            + "y sin perder de vista lo que se esta anunciando."),

    CERCANO("Hazlo mas cercano y humano, como si se lo contaras a un cliente "
            + "de toda la vida. Tutea, habla en primera persona y baja "
            + "cualquier palabra que suene a folleto."),

    // ---------- Forma ----------

    HASHTAGS("Agrega hashtags relevantes al final, sin pasar del numero indicado "
            + "para esa red. Que salgan de lo que ya dice el texto: nada de "
            + "etiquetas genericas de relleno ni inventarse marcas o lugares. "
            + "Si no caben, acorta el texto para hacerles sitio."),

    SIN_EMOJIS("Quita todos los emojis y deja el texto limpio. Si alguno "
            + "sostenia una pausa o una lista, reescribe esa parte con "
            + "puntuacion para que no se note el hueco.");

    private final String instruccion;

    Ajuste(String instruccion) {
        this.instruccion = instruccion;
    }

    public String getInstruccion() {
        return instruccion;
    }
}
