package com.metricol.api.service.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.enums.ObjetivoRedes;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.Platform;

/**
 * Mejora lo que la persona dictó y lo adapta a cada red.
 *
 * <p>Lo que se dicta ES la publicación, dicha con sus palabras. Aquí no se
 * escribe otra: se arregla la redacción que el dictado se come, se ordena la
 * idea y se ajusta el largo a cada red. El mensaje, los datos y el tono de
 * quien lo dijo se respetan — si se cambiaran, la publicación dejaría de ser
 * suya, que es justo lo que nadie quiere de una herramienta así.
 *
 * <p>Sale una versión por red porque un solo párrafo no vale para las cinco:
 * en TikTok son 90 caracteres y en LinkedIn 3000.
 *
 * <p><b>Una sola llamada para las cinco.</b> Se le pide al modelo un JSON con
 * una llave por red. Cinco llamadas serían cinco viajes y cinco esperas
 * sumadas; una sola es una espera. Es la diferencia entre que esto se sienta
 * instantáneo o no, y lo instantáneo es el producto.
 */
@Service
public class Redactor {

    private static final Logger log = LoggerFactory.getLogger(Redactor.class);

    /** Salto de linea. Como constante porque este archivo se arma desde
     * scripts y una barra invertida suelta no sobrevive el viaje. */
    private static final String SALTO = System.lineSeparator();

    private static final String SISTEMA = """
            Eres un community manager que MEJORA lo que escribe el dueno de un
            negocio pequeno o mediano en Latinoamerica. Escribes en espanol
            neutro.

            Lo que recibes es lo que la persona DICTO con sus propias palabras,
            y puede venir de dos formas. Distinguelas antes de escribir:

            1. DESCRIBE su publicacion ("hoy tenemos 2x1 en tacos al pastor
               hasta las 6"). Entonces esa es su publicacion y tu trabajo es
               dejarla bien, no escribir otra. Mejorar aqui es: arreglar la
               redaccion y la puntuacion que el dictado se come, quitar
               muletillas y repeticiones, ordenar la idea, darle un cierre. NO
               es cambiar el mensaje, subir el tono ni alargarlo. Si dijo algo
               corto, queda corto.

            2. Pide un OBJETIVO ("haz una publicacion profesional para vender
               esto y que me escriban"). Entonces no hay texto que mejorar: lo
               que hay es un encargo, y lo que se vende sale de las imagenes.
               Escribe la publicacion que cumpla ese objetivo, apoyandote en lo
               que se ve.

            Puede ser mezcla de las dos. Ante la duda, trata lo que dijo como
            contenido y no como instruccion: inventarle una publicacion a quien
            solo queria que le arreglaran la suya es el error caro.

            Sus datos, sus palabras propias y su intencion se respetan siempre.

            Tambien recibes que se ve en las imagenes y en que redes se publica.

            Devuelves UN JSON con esta forma:
            {"titulo": "...", "guion": "...", "textos": {"INSTAGRAM": "...", "TIKTOK": "..."}}

            - "titulo" es UN titulo profesional de una sola linea, el mismo para
              todas las redes: nombra lo que se ofrece o el tema de la
              publicacion, como el titulo de un video o de un anuncio. Maximo
              90 caracteres. Sin hashtags, sin emojis, sin punto final y sin
              repetir el caption. Ejemplos: "Mallas ciclonicas para predios
              industriales", "2x1 en tacos al pastor hasta las 6".
            - "guion" es su mensaje ya limpio, en version general: lo mismo que
              dijo, bien escrito. Sirve para las redes sin texto propio.
            - "textos" lleva una llave por cada red que te pidan, con SU mensaje
              adaptado a esa red. Ni una llave de mas. Es el caption: lo que se
              lee bajo la publicacion. No lo empieces con el titulo.

            Reglas que no se rompen:
            - Respeta el limite de caracteres de cada red. Es un limite duro.
            - Adaptar por red es cambiar el largo y la forma, no el fondo. En TikTok
              cabe un gancho; en LinkedIn cabe el contexto. El mensaje es el
              mismo.
            - Si las imagenes dicen algo concreto puedes apoyarte en ello, pero
              solo si encaja con lo que la persona dijo.
            - No inventes datos que nadie te dio: ni precios, ni horarios, ni
              direcciones, ni promesas.
            - Nada de preambulos ni comillas envolviendo el texto.
            """;

    /**
     * Para los retoques de un toque (ver {@link #ajustar}).
     *
     * <p>El limite va como regla dura y no entre parentesis, igual que en
     * {@link #SISTEMA}: dicho de pasada, el modelo lo trata como una
     * sugerencia, y los botones que mas lo tientan a pasarse —alargar,
     * agregar hashtags— son justo los de este prompt.
     */
    private static final String SISTEMA_AJUSTE = """
            Retocas un texto de redes sociales que ya esta escrito.

            Cambias la FORMA, nunca el fondo: los datos, el mensaje y lo
            que la persona quiso decir siguen siendo exactamente los
            mismos. No inventes nada que no estuviera ya ahi.

            Reglas que no se rompen:
            - El limite de caracteres de la red es un limite DURO. Cuenta
              todo: espacios, emojis y hashtags. Si lo que se pide no cabe
              —alargar, agregar hashtags—, gana el limite: haz lo que quepa.
            - Nunca pongas mas hashtags de los indicados para la red.
            - Si hay que quitar algo para que quepa, quita lo menos
              importante. Nunca dejes una frase cortada a la mitad.

            Contestas SOLO con el texto retocado. Sin comillas, sin
            explicaciones, sin decir que cambiaste ni cuantos caracteres tiene.
            """;

    private final OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public Redactor(OpenAiClient client) {
        this.client = client;
    }

    /**
     * @param titulo      el titulo comun a todas las redes, ya recortado a
     *                    {@link EspecTexto#TITULO_MAX}; nunca nulo si hay algun texto
     * @param guion       resumen editable de la idea
     * @param textos      el caption final por red, ya recortado al limite
     */
    public record Borrador(String titulo, String guion, Map<Platform, String> textos) {
    }

    /**
     * A que se dedica quien publica. Sale de su workspace.
     *
     * <p>Un record y no la entidad {@code Workspace} a proposito: aqui entra
     * solo lo que el prompt va a leer. Pasar la entidad habria metido en la
     * capa de IA su id, su logo y su perfil de upload-post, y el dia que se le
     * agregue un campo delicado nadie se acordaria de que esto lo recibe.
     *
     * <p>Todos los campos pueden venir nulos: el registro se puede omitir.
     */
    public record Negocio(
            String nombre,
            String giro,
            String ciudad,
            String descripcion,
            ObjetivoRedes objetivo,
            MarcaDelNegocio marca) {

        /** Sin perfil de marca: lo que se sabía antes de que existiera. */
        public Negocio(String nombre, String giro, String ciudad, String descripcion, ObjetivoRedes objetivo) {
            this(nombre, giro, ciudad, descripcion, objetivo, MarcaDelNegocio.VACIA);
        }

        /** Cuando no se sabe nada del negocio. */
        public static final Negocio DESCONOCIDO = new Negocio(null, null, null, null, null);
    }

    public Borrador redactar(
            String loQueDijo, List<String> queSeVe, Collection<Platform> redes, Negocio negocio) {
        List<Platform> destino = redes == null || redes.isEmpty()
                ? List.of(Platform.INSTAGRAM)
                : new ArrayList<>(redes);

        String respuesta = client.completeJson(AiOperacion.REDACTAR, SISTEMA, prompt(loQueDijo, queSeVe, destino, negocio));
        return interpretar(respuesta, destino);
    }

    /**
     * Retoca UN texto ya escrito, para UNA red.
     *
     * <p>Es una llamada aparte de {@link #redactar} y mucho mas barata: no
     * vuelve a mirar imagenes, no escribe cinco versiones y no necesita JSON.
     * Se usa cuando alguien lee el texto de una red y quiere lo mismo pero
     * mas corto, mas vendedor o mas serio — y a ese toque hay que contestar
     * casi al instante, porque es un retoque, no un rehacer.
     *
     * <p>Si algo falla devuelve el texto tal como entro: quedarse con el que
     * ya habia es un resultado aceptable; vaciar el cuadro no.
     *
     * <p><b>El limite de la red manda sobre el boton.</b> "Mas largo" en
     * TikTok o "Con hashtags" en Facebook piden justo lo que menos cabe, y un
     * modelo no cuenta caracteres de forma fiable. Si aun con la regla dura
     * la respuesta se pasa, se le pide UNA vez que la acorte en vez de
     * recortarla: el recorte deja una frase a medias con "…", y como los
     * hashtags van al final, son lo primero que se pierde. Recortar queda
     * como ultima red de seguridad.
     */
    public String ajustar(String texto, Platform red, Ajuste ajuste) {
        EspecTexto espec = EspecTexto.de(red);

        String retocado;
        try {
            retocado = client.complete(AiOperacion.AJUSTAR, SISTEMA_AJUSTE,
                    promptAjuste(texto, red, espec, ajuste.getInstruccion())).strip();
        } catch (Exception ex) {
            log.warn("No se pudo ajustar el texto de {}: {}", red, ex.toString());
            return texto;
        }

        if (retocado.isBlank()) {
            return texto;
        }
        if (retocado.length() <= espec.maxCaracteres()) {
            return retocado;
        }
        return espec.recortar(acortar(retocado, red, espec));
    }

    /**
     * Segunda y ultima vuelta para un retoque que se paso del limite.
     *
     * <p>Una sola: si tampoco cabe, lo que queda es recortar. Insistir mas
     * seria sumar esperas a un boton que tiene que contestar casi al
     * instante. Si la llamada falla devuelve el texto tal como llego, y el
     * recorte de quien llama lo deja dentro del limite.
     */
    private String acortar(String texto, Platform red, EspecTexto espec) {
        int sobran = texto.length() - espec.maxCaracteres();
        String instruccion = "Tiene " + sobran + " caracteres de mas. Acortalo hasta que"
                + " quepa con margen, quitando lo menos importante: conserva el dato"
                + " principal y, si hay hashtags, deja al menos uno. Ninguna frase cortada"
                + " a la mitad.";
        try {
            String corto = client.complete(AiOperacion.ACORTAR, SISTEMA_AJUSTE,
                    promptAjuste(texto, red, espec, instruccion)).strip();
            return corto.isBlank() ? texto : corto;
        } catch (Exception ex) {
            log.warn("No se pudo acortar el texto de {}: {}", red, ex.toString());
            return texto;
        }
    }

    private static String promptAjuste(String texto, Platform red, EspecTexto espec, String instruccion) {
        String limpio = texto.strip();
        return "Red: " + red.getLabel() + SALTO
                + "Limite duro: " + espec.maxCaracteres() + " caracteres en total, contando"
                + " espacios, emojis (cada uno cuenta como dos) y hashtags. Apunta a no pasar"
                + " de " + objetivo(espec) + "." + SALTO
                + "Hashtags: como maximo " + espec.hashtagsSugeridos() + "." + SALTO
                + "Estilo de la red: " + espec.estilo() + "." + SALTO + SALTO
                + "Que hacer: " + instruccion + SALTO + SALTO
                // El largo actual va dicho: sin el, "mas largo" o "agrega
                // hashtags" no tienen contra que medirse, y el modelo no sabe
                // que al texto de TikTok le quedan diez caracteres libres.
                + "Texto actual (" + limpio.length() + " caracteres):" + SALTO + limpio;
    }

    /**
     * El largo al que se apunta: el 90% del limite. El modelo cuenta a ojo, y
     * pedirle el numero exacto es pedirle que se pase buena parte de las veces.
     */
    private static int objetivo(EspecTexto espec) {
        return (int) Math.floor(espec.maxCaracteres() * 0.9);
    }

    private String prompt(
            String loQueDijo, List<String> queSeVe, List<Platform> redes, Negocio negocio) {
        StringBuilder sb = new StringBuilder();
        String contexto = describir(negocio);
        if (!contexto.isEmpty()) {
            sb.append("El negocio que publica:\n").append(contexto)
                    // El limite va pegado al dato, no en el sistema: es donde
                    // el modelo lo esta leyendo. Sin esta linea, dos datos
                    // sueltos —giro y ciudad— le bastan para inventar
                    // horarios, precios y direcciones que nadie le dio, y eso
                    // se publica tal cual en la red del negocio.
                    .append("Usalo para el tono y el encuadre. NO inventes nada")
                    .append(" que no este aqui: ni precios, ni horarios, ni")
                    .append(" direcciones, ni promociones.\n\n");
        }

        sb.append("Lo que dijo la persona (mejoralo, no lo reemplaces):\n")
                .append(loQueDijo.strip()).append("\n\n");

        if (queSeVe != null && !queSeVe.isEmpty()) {
            sb.append("Lo que se ve en las imagenes:\n");
            queSeVe.forEach(linea -> sb.append("- ").append(linea).append('\n'));
            sb.append('\n');
        } else {
            sb.append("No hay imagenes o no se pudieron ver: escribe solo con la intencion.\n\n");
        }

        sb.append("Titulo: maximo ").append(EspecTexto.TITULO_MAX)
                .append(" caracteres, uno solo para todas las redes. Estilo: ")
                .append(EspecTexto.TITULO.estilo()).append(".\n\n");
        sb.append("Redes y su formato (el caption de cada una):\n");
        for (Platform red : redes) {
            EspecTexto espec = EspecTexto.de(red);
            sb.append("- ").append(red.name())
                    .append(": maximo ").append(espec.maxCaracteres()).append(" caracteres, ")
                    .append("alrededor de ").append(espec.hashtagsSugeridos()).append(" hashtags. ")
                    .append("Estilo: ").append(espec.estilo()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Lee el JSON del modelo y se queda solo con las redes pedidas.
     *
     * <p>Aunque se pidio JSON válido, el CONTENIDO puede venir incompleto: una
     * red de menos, una llave con otro nombre. Lo que falte se rellena con el
     * guion recortado a esa red, para que ninguna red se quede sin texto — que
     * publicaria vacia.
     */
    private Borrador interpretar(String json, List<Platform> redes) {
        String titulo = null;
        String guion = "";
        Map<Platform, String> textos = new LinkedHashMap<>();

        try {
            JsonNode raiz = mapper.readTree(json);
            guion = raiz.path("guion").asText("").strip();
            titulo = EspecTexto.recortarTitulo(raiz.path("titulo").asText(""));

            JsonNode nodo = raiz.path("textos");
            for (Platform red : redes) {
                String texto = nodo.path(red.name()).asText("").strip();
                if (!texto.isBlank()) {
                    textos.put(red, EspecTexto.de(red).recortar(texto));
                }
            }
        } catch (Exception ex) {
            log.warn("La IA devolvio algo que no se pudo leer como JSON: {}", ex.toString());
        }

        if (guion.isBlank()) {
            guion = textos.values().stream().findFirst().orElse("");
        }
        for (Platform red : redes) {
            if (!textos.containsKey(red)) {
                log.warn("La IA no devolvio texto para {}; se usa el guion", red);
                textos.put(red, EspecTexto.de(red).recortar(guion));
            }
        }

        // Sin titulo de la IA se saca uno del guion: es el respaldo, no lo
        // deseado, y por eso se registra. Un titulo vacio haria que el
        // proveedor publicara sin `title`, y en YouTube eso es un rechazo.
        if (titulo == null) {
            log.warn("La IA no devolvio titulo; se saca del guion");
            titulo = EspecTexto.tituloDesde(guion);
        }

        return new Borrador(titulo, guion, textos);
    }

    /**
     * El negocio en lineas cortas, o vacio si no se sabe nada de el.
     *
     * <p>Vacio y no "sin datos": una cabecera con nada debajo gasta tokens y
     * encima le sugiere al modelo que rellene el hueco el solo.
     */
    private static String describir(Negocio negocio) {
        if (negocio == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        agregar(sb, "Nombre", negocio.nombre());
        agregar(sb, "Giro", negocio.giro());
        agregar(sb, "Ciudad", negocio.ciudad());
        agregar(sb, "A que se dedica", negocio.descripcion());
        if (negocio.objetivo() != null) {
            sb.append("- Lo que busca con sus redes: ")
                    .append(negocio.objetivo().getInstruccion())
                    .append('\n');
        }

        MarcaDelNegocio marca = negocio.marca() == null ? MarcaDelNegocio.VACIA : negocio.marca();
        agregar(sb, "Que vende o que destaca", marca.queVende());
        agregar(sb, "A quien le habla", marca.publico());
        agregar(sb, "Como suena su marca (escribe asi)", marca.personalidadEs());
        agregar(sb, "Nunca digas ni hagas esto", marca.evitar());
        if (marca.hayContacto()) {
            // Los datos van con su limite pegado: se usan cuando la publicacion invita a escribir o visitar, y
            // no se inventan otros.
            sb.append("- Como contactarlo (usa SOLO estos datos, y solo si el texto invita a escribir o visitar): ")
                    .append(marca.contactoEs()).append('\n');
        }
        return sb.toString();
    }

    private static void agregar(StringBuilder sb, String etiqueta, String valor) {
        if (valor != null && !valor.isBlank()) {
            sb.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }
}
