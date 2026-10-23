package com.metricol.api.service.ai;

import org.springframework.stereotype.Component;

import com.metricol.api.enums.AiOperacion;

/** Propone una descripción breve y factual para completar el perfil inicial. */
@Component
public class DescripcionDelNegocio {

    static final String SISTEMA = """
            Eres especialista en perfiles de marca para pequeños y medianos negocios de México.
            Escribe una descripción clara y natural que ayude a una IA de marketing a entender
            a qué se dedica el negocio.

            LO QUE LA PERSONA YA ESCRIBIÓ DE SU MARCA NO ES INVENCIÓN: ES SUYO.
            Si abajo viene qué vende, a quién le habla, su historia, sus valores o de qué
            quiere hablar, eso son datos confirmados por ella igual que su nombre, y es
            justo el material que hace que la descripción sea de ESTE negocio y no de
            cualquiera de su giro. Úsalo. Lo que no se puede es agregar lo que nadie dijo.

            Una descripción que serviría para cualquier otro negocio del mismo giro está
            mal aunque esté bien escrita: si le quitas el nombre y podría ser de otro,
            vuelve a escribirla con lo que esta persona sí contó.

            Reglas:
            - Devuelve SOLO la descripción, sin título, comillas, viñetas, emojis ni explicaciones.
            - Un solo párrafo de 2 a 4 frases y máximo 500 caracteres.
            - Describe qué hace y el tipo de valor que ofrece, con español de México y palabras sencillas.
            - Si existe una descripción actual, consérvala como fuente de verdad y complétala o aclárala.
            - El giro sirve como orientación, no como permiso para inventar productos o servicios específicos.
            - Nunca inventes precios, años, certificaciones, cobertura, instalaciones, clientes, garantías,
              ubicación exacta, especialidades ni resultados. Tampoco cuando el dato "suene" probable.
            - No uses frases vacías como "somos líderes", "la mejor calidad" o "excelencia garantizada".
            """;

    private final OpenAiClient client;

    public DescripcionDelNegocio(OpenAiClient client) {
        this.client = client;
    }

    /** Sin perfil de marca: durante el registro todavía no hay ninguno. */
    public String sugerir(String nombre, String giro, String ciudad, String descripcionActual) {
        return sugerir(nombre, giro, ciudad, descripcionActual, MarcaDelNegocio.VACIA);
    }

    /**
     * Con lo que la persona ya escribió de su marca.
     *
     * <p>Antes esto solo veía nombre, giro, ciudad y la descripción actual, y por
     * eso devolvía una paráfrasis del giro que servía para cualquier negocio
     * igual. Mientras tanto, en la misma cuenta ya estaba escrito qué vende, a
     * quién le habla, su historia y de qué quiere hablar —a veces mil y pico
     * caracteres— y nada de eso llegaba hasta aquí.
     *
     * <p>Dárselo no es dejarlo inventar: es lo contrario. Lo que lo hacía
     * genérico era no tener con qué ser específico.
     */
    public String sugerir(String nombre, String giro, String ciudad, String descripcionActual,
            MarcaDelNegocio marca) {
        String respuesta = client.complete(AiOperacion.SUGERIR_DESCRIPCION_NEGOCIO, SISTEMA,
                pedido(nombre, giro, ciudad, descripcionActual, marca));
        String limpia = limpiar(respuesta);
        if (limpia.isBlank()) {
            throw new IllegalStateException("No pude proponer la descripción ahora. Intenta nuevamente.");
        }
        return limpia;
    }

    static String pedido(String nombre, String giro, String ciudad, String descripcionActual) {
        return pedido(nombre, giro, ciudad, descripcionActual, MarcaDelNegocio.VACIA);
    }

    static String pedido(String nombre, String giro, String ciudad, String descripcionActual,
            MarcaDelNegocio marca) {
        StringBuilder texto = new StringBuilder("Datos confirmados por la persona:\n");
        linea(texto, "Nombre", nombre);
        linea(texto, "Giro", giro);
        linea(texto, "Ciudad", ciudad);
        linea(texto, "Descripción actual", descripcionActual);

        // Van bajo la MISMA cabecera de "confirmados" a proposito: son datos que
        // escribio la persona, no sugerencias nuestras, y separarlos invitaba a
        // tratarlos como material opcional.
        MarcaDelNegocio suya = marca == null ? MarcaDelNegocio.VACIA : marca;
        linea(texto, "Qué vende o qué destaca", suya.queVende());
        linea(texto, "A quién le habla", suya.publico());
        linea(texto, "Su historia (quién está detrás, por qué existe)", suya.historia());
        linea(texto, "Sus valores", suya.valores());
        linea(texto, "Cómo trabaja", suya.comoTrabajaEs());
        linea(texto, "De qué quiere hablar en redes", suya.pilaresEs());
        linea(texto, "Nunca digas esto", suya.evitar());

        texto.append("Redacta una propuesta editable sin agregar datos que no estén confirmados.");
        return texto.toString();
    }

    private static void linea(StringBuilder texto, String etiqueta, String valor) {
        if (valor != null && !valor.isBlank()) {
            texto.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }

    static String limpiar(String respuesta) {
        String texto = respuesta == null ? "" : respuesta
                .replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (texto.length() >= 2
                && ((texto.startsWith("\"") && texto.endsWith("\""))
                        || (texto.startsWith("“") && texto.endsWith("”")))) {
            texto = texto.substring(1, texto.length() - 1).strip();
        }
        return texto.length() <= 500 ? texto : texto.substring(0, 500).strip();
    }
}
