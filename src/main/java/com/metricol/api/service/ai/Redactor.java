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
               es cambiar el mensaje, subir el tono ni rellenarlo. Si dijo algo
               corto queda corto, con una excepcion: el porque que su propia
               voz pidio contar (ver EXPLICAR EL PORQUE NO ES ALARGAR). Eso no
               es relleno, es su contenido.

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

            QUIEN MANDA SOBRE QUE, en este orden:

            1. Lo que dijo la persona. Nadie lo contradice.
            2. Lo que se ve en la foto: decide DE QUE habla la publicacion. El
               trabajo que se esta haciendo, la herramienta, el lugar, la hora,
               lo que esa escena tiene de propio.
            3. Su voz —historia, frases, valores, de que quiere hablar—: decide
               COMO suena y que se puede decir. Una foto igual en dos negocios
               distintos tiene que dar dos textos distintos, y la diferencia
               sale de aqui.
            4. Lo que vende: contexto, lo ultimo. Nunca es el texto.

            Un texto que podria firmar cualquier negocio de su giro esta mal
            aunque este bien escrito: si quitas el nombre y podria ser de otra
            cuenta, reescribelo.

            EXPLICAR EL PORQUE NO ES ALARGAR. Hay negocios cuya voz dice que
            ensenan su oficio: el paso a paso, por que se hace asi, que sale
            mal cuando alguien se salta un paso. Cuando su voz dice eso, ese
            porque ES su mensaje y va escrito, aunque lo que dicto fuera una
            linea. No es adorno encima de lo que dijo: es lo que pidio contar.

            La diferencia esta en si la frase aporta algo que SOLO este negocio
            sabe. "Se nivela antes de colar porque saltarse ese paso deja
            asientos y grietas a los seis meses" es su oficio y va. "Con la
            calidad que nos caracteriza" es relleno y no va. Si no sabes el
            porque concreto, no lo inventes: callarlo es mejor que rellenarlo.

            Lo que sigue prohibido es cambiar lo que dijo, subirle el tono o
            prometer lo que no dio. Eso no se toca.

            APORTA ALGO QUE NO SE SABIA. Una publicacion que solo describe la
            foto —"hoy nivelamos el terreno", "ya quedo instalado"— no le sirve
            a nadie: eso ya se ve en la imagen. Lo que hace que alguien se
            detenga, y mas si entiende del tema, es el CRITERIO que hay detras:
            que se revisa y en que orden, por que se hace asi y no de otra
            forma, que se rompe cuando se hace mal, en que se nota meses
            despues un trabajo bien hecho, cuanto tarda, que lo complica.

            Eso sale de lo que la persona dijo, de lo que se ve en las imagenes,
            de su oficio tal como ELLA lo describio, Y DE LO QUE TU SABES DEL
            TEMA: como funciona, por que se hace asi, que errores son comunes,
            que conviene revisar, que temporada o fecha de Mexico viene al caso.
            Usalo como lo haria un experto que escribe para sus clientes: con
            datos generales y verdaderos del oficio, explicados en simple. Lo
            que NO puedes inventar es lo de ESTE negocio: precios, horarios,
            promociones, resultados, certificaciones, garantias. Ante la duda
            de si algo es cierto, no lo pongas.

            CADA TEXTO DEJA UNA IDEA. Quien lo lea tiene que quedarse con algo
            util aunque no compre hoy: un criterio para elegir, un error que
            evitar, el porque de algo, un dato que no sabia. Esa idea es el
            cuerpo del texto. Si el texto solo dice que existe el producto o
            que la foto es bonita, no genera valor: reescribelo.

            QUE SUENE A PERSONA. Escribe como habla alguien del oficio cuando le
            explica algo a un cliente que le cae bien: frases naturales, con
            ritmo distinto entre ellas, en segunda persona cuando ayuda. Nada
            de frases de folleto ni de plantillas.

            Donde cabe —Instagram, LinkedIn— apunta a tres partes: el gancho
            concreto, un cuerpo que ensene algo, y el cierre. En TikTok y
            Facebook sigue siendo una o dos lineas.

            Dos textos para dos fotos distintas del mismo negocio tienen que
            poder distinguirse: si lo que escribiste serviria igual para
            cualquier otra foto suya, no has escrito nada. Nada de "calidad y
            compromiso en cada proyecto" ni "soluciones a tu medida": eso no
            dice nada y se nota.

            Devuelves UN JSON con esta forma:
            {"titulo": "...", "guion": "...", "textos": {"INSTAGRAM": "...", "TIKTOK": "..."}}

            - "titulo" es UN titulo profesional de una sola linea, el mismo para
              todas las redes: nombra lo que se ofrece o el tema de la
              publicacion, como el titulo de un video o de un anuncio. Maximo
              90 caracteres. Sin hashtags, sin emojis, sin punto final, sin
              los simbolos < ni > (YouTube los rechaza) y sin repetir el
              caption. Ejemplos: "Mallas ciclonicas para predios
              industriales", "2x1 en tacos al pastor hasta las 6".
            - "guion" es su mensaje ya limpio, en version general: lo mismo que
              dijo, bien escrito. Sirve para las redes sin texto propio. Es un
              texto LISTO PARA PUBLICAR, como los de "textos": nunca describas la
              publicacion ("carrusel de 4 fotos sobre...", "las imagenes
              muestran...", "cerrar invitando a...") ni repitas la instruccion
              que te dieron. Eso es el encargo, no el post.
            - "textos" lleva una llave por cada red que te pidan, con SU mensaje
              adaptado a esa red. Ni una llave de mas. Es el caption: lo que se
              lee bajo la publicacion. No lo empieces con el titulo.

            COMO SE VE EL TEXTO. Importa tanto como lo que dice, porque esto
            se lee en un telefono y un bloque corrido de seis lineas no se lee:
            - Frases cortas. Una idea por frase.
            - Separa en parrafos con UNA linea en blanco entre ellos. Usa
              "\\n\\n" dentro del valor JSON para representar esa linea en
              blanco. Nunca devuelvas saltos sin escapar que rompan el JSON.
            - En Instagram y LinkedIn: el gancho en la primera linea, linea en
              blanco, el cuerpo, linea en blanco, el cierre.
            - Gancho, cuerpo y cierre son como se ORDENA el texto, no palabras
              que se escriben. Nunca pongas etiquetas como "Gancho:", "Cuerpo:",
              "Cierre:", "CTA:", "Tip:", "Revisa:" o "Lo concreto:" al inicio de
              una linea: se lee como plantilla, no como alguien que habla.
            - No hables de las fotos como fotos ("las imagenes usan...", "en la
              imagen ves..."): habla de lo que muestran.
            - Si enumeras cosas (sabores, servicios, horarios, medidas), va UNA
              POR LINEA, cada una abierta con "- ". Nunca todas apretadas en
              una frase separadas por comas.
            - Los hashtags van siempre al final, en su propia linea, despues de
              una linea en blanco. Nunca dentro de la frase.
            - En Facebook y TikTok, que son cortos, basta con una o dos lineas
              y los hashtags abajo: ahi un parrafo ya es demasiado.

            EMOJIS CON INTENCION, NO COMO DECORACION:
            - Usa entre CERO y DOS emojis relevantes en todo el caption. No es
              obligatorio usarlos: una marca profesional, elegante o de un
              tema regulado normalmente queda mejor sin ellos.
            - Si aportan, coloca uno en el gancho o en el cierre. Nunca abras
              cada linea con un emoji, nunca repitas el mismo y nunca pongas
              varios juntos.
            - El emoji tiene que reforzar una palabra concreta del mensaje. Si
              se puede quitar sin perder nada, no lo uses.

            LO CONCRETO VA PRIMERO. Lo que abre el texto es el dato que SOLO
            ese negocio puede dar: el producto, el precio, la hora, el lugar,
            lo que se ve en la foto. Lo general va despues, si cabe. Una
            publicacion sin ningun dato concreto es una publicacion generica,
            y esa si conviene que sea corta: lo que no se puede es usar eso
            como excusa para no contar lo que si se sabe.

            NUNCA GROSERIAS NI LENGUAJE VULGAR, aunque la persona las haya dicho
            al dictar o las escriba: esto se publica a nombre del negocio. Cambia
            "esta bien chingon" por "esta buenisimo". Tampoco insultos, contenido
            sexual, discriminatorio o que ataque a alguien.

            LO QUE NO SE ESCRIBE NUNCA, porque vale para cualquier negocio y
            por eso no dice nada de este:
            - "no te lo pierdas", "te esperamos", "ven y disfruta", "somos tu
              mejor opcion", "la mejor calidad", "calidad y servicio", "a
              precios increibles", "lo que necesitas", "para ti y tu familia",
              "contactanos para mas informacion", "no esperes mas".
            - Preguntas retoricas de apertura: "¿Buscas...?", "¿Sabias que...?".
            - Superlativos sin con que respaldarlos: "el mejor", "el unico",
              "insuperable", "de primer nivel".
            - Emojis de adorno abriendo cada linea.
            Prueba antes de entregar: si quitas una frase y la publicacion
            sigue diciendo lo mismo, esa frase sobraba. Quitala.

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
            - Mas corto y concreto gana a mas largo y bonito. El limite es un
              techo, no una meta: si la idea cabe en dos lineas, son dos
              lineas. Pero corto y vacio no le gana a nada: si su voz pedia
              ensenar el porque, ese porque no es "bonito", es la idea.
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
            - Si te piden emojis, usa como maximo dos, relevantes y separados;
              nunca uno al inicio de cada linea. Si no te los piden, conserva
              la densidad del texto original.
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
            MarcaDelNegocio marca,
            /** Lo que sus métricas dicen que le funciona (hashtags), en una línea; nulo si aún no se sabe. */
            String aprendido,
            /**
             * Lo que el dueño rescató de Observación o Descartadas ("sí va"), en
             * pocas líneas: para que quien revisa no vuelva a dudar de esos temas.
             * Nulo si nunca rescató nada.
             */
            String loQueSiVa) {

        /** Sin lo rescatado: el que escribe no lo necesita. */
        public Negocio(String nombre, String giro, String ciudad, String descripcion, ObjetivoRedes objetivo,
                MarcaDelNegocio marca, String aprendido) {
            this(nombre, giro, ciudad, descripcion, objetivo, marca, aprendido, null);
        }

        /** El mismo negocio con lo que el dueño ya dijo que sí va. */
        public Negocio conLoQueSiVa(String siVa) {
            return new Negocio(nombre, giro, ciudad, descripcion, objetivo, marca, aprendido, siVa);
        }

        /** Sin perfil de marca: lo que se sabía antes de que existiera. */
        public Negocio(String nombre, String giro, String ciudad, String descripcion, ObjetivoRedes objetivo) {
            this(nombre, giro, ciudad, descripcion, objetivo, MarcaDelNegocio.VACIA);
        }

        /** Sin métricas todavía. */
        public Negocio(String nombre, String giro, String ciudad, String descripcion, ObjetivoRedes objetivo,
                MarcaDelNegocio marca) {
            this(nombre, giro, ciudad, descripcion, objetivo, marca, null);
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
        return sinGroserias(interpretar(respuesta, destino));
    }

    /**
     * Lo que se publica no lleva groserías, aunque la persona las haya dictado. Cada texto
     * que traiga alguna se reescribe UNA vez; si aun así queda, se tapa. Es lo último que
     * pasa antes de que el texto pueda salir a una red real.
     */
    private Borrador sinGroserias(Borrador borrador) {
        boolean alguna = LenguajeCuidado.tieneGroserias(borrador.guion())
                || borrador.textos().values().stream().anyMatch(LenguajeCuidado::tieneGroserias);
        if (!alguna) {
            return borrador;
        }
        Map<Platform, String> limpios = new LinkedHashMap<>();
        borrador.textos().forEach((red, texto) -> limpios.put(red, limpiar(texto, red)));
        String guion = LenguajeCuidado.tieneGroserias(borrador.guion())
                ? LenguajeCuidado.cubrir(borrador.guion())
                : borrador.guion();
        return new Borrador(LenguajeCuidado.cubrir(borrador.titulo()), guion, limpios);
    }

    private String limpiar(String texto, Platform red) {
        if (!LenguajeCuidado.tieneGroserias(texto)) {
            return texto;
        }
        EspecTexto espec = EspecTexto.de(red);
        try {
            String reescrito = normalizarFormato(client.complete(AiOperacion.AJUSTAR, SISTEMA_AJUSTE,
                    promptAjuste(texto, red, espec, "Quita toda grosería o palabra vulgar y cámbiala por una"
                            + " expresión limpia que diga lo mismo, con el mismo tono cercano. No cambies nada más.")));
            if (!reescrito.isBlank() && !LenguajeCuidado.tieneGroserias(reescrito)) {
                return espec.recortar(reescrito);
            }
        } catch (Exception ex) {
            log.warn("No se pudo reescribir sin groserias el texto de {}: {}", red, ex.toString());
        }
        return LenguajeCuidado.cubrir(texto);
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
            retocado = normalizarFormato(client.complete(AiOperacion.AJUSTAR, SISTEMA_AJUSTE,
                    promptAjuste(texto, red, espec, ajuste.getInstruccion())));
        } catch (Exception ex) {
            log.warn("No se pudo ajustar el texto de {}: {}", red, ex.toString());
            return texto;
        }

        if (retocado.isBlank()) {
            return texto;
        }
        // Un retoque ("más vendedor", "más cercano") tampoco puede meter groserías.
        retocado = limpiar(retocado, red);
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
            guion = normalizarFormato(raiz.path("guion").asText(""));
            titulo = EspecTexto.recortarTitulo(raiz.path("titulo").asText(""));

            JsonNode nodo = raiz.path("textos");
            for (Platform red : redes) {
                String texto = normalizarFormato(nodo.path(red.name()).asText(""));
                if (!texto.isBlank()) {
                    textos.put(red, EspecTexto.de(red).recortar(texto));
                }
            }
        } catch (Exception ex) {
            log.warn("La IA devolvio algo que no se pudo leer como JSON: {}", ex.toString());
        }

        // Aunque el prompt lo prohibe, a veces llega. Se corrige aqui, que es
        // donde se puede garantizar: un "Gancho:" o un guion que describe el
        // carrusel salieron programados a redes reales (oct 2026).
        guion = sinEtiquetas(guion);
        textos.replaceAll((red, texto) -> sinEtiquetas(texto));
        if (pareceInstruccion(guion)) {
            log.warn("El guion describia la publicacion en vez de serlo; se usa el texto de una red");
            guion = "";
        }
        if (guion.isBlank()) {
            guion = mejorTexto(textos);
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

    /** Las etiquetas de estructura que el modelo a veces escribe al inicio de una linea. */
    private static final java.util.regex.Pattern ETIQUETA = java.util.regex.Pattern.compile(
            "^(?:gancho|cuerpo|cierre|remate|hook|cta|llamado a la acci[oó]n|lo concreto|tip(?: pr[aá]ctico)?)\\s*:\\s*",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /** Un guion que habla DE la publicacion en lugar de ser la publicacion. */
    private static final java.util.regex.Pattern INSTRUCCION = java.util.regex.Pattern.compile(
            "(?:publicaci[oó]n tipo carrusel|carrusel (?:de \\d+ fotos|\\(\\d+ fotos\\))|\\(\\d+ fotos\\)"
                    + "|(?:cerrar|terminar|abrir) invitando|las im[aá]genes (?:usan|muestran)"
                    + "|usar (?:la|las|esta|estas) im[aá]gen(?:es)? para|haz una publicaci[oó]n)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /**
     * Quita "Gancho:", "Cierre:", "Tip:"… del inicio de cada linea. Una linea
     * que era solo la etiqueta desaparece; si queda texto, empieza en mayuscula.
     */
    static String sinEtiquetas(String texto) {
        if (texto == null || texto.isBlank()) {
            return texto == null ? "" : texto;
        }
        StringBuilder salida = new StringBuilder();
        for (String linea : texto.split("\n", -1)) {
            java.util.regex.Matcher m = ETIQUETA.matcher(linea.stripLeading());
            String limpia = linea;
            if (m.find()) {
                String resto = linea.stripLeading().substring(m.end());
                if (resto.isBlank()) {
                    continue;
                }
                limpia = Character.toUpperCase(resto.charAt(0)) + resto.substring(1);
            }
            if (salida.length() > 0) {
                salida.append('\n');
            }
            salida.append(limpia);
        }
        return normalizarFormato(salida.toString());
    }

    static boolean pareceInstruccion(String guion) {
        return guion != null && INSTRUCCION.matcher(guion).find();
    }

    /** El texto mas completo de las redes: Instagram, LinkedIn, o el mas largo. */
    private static String mejorTexto(Map<Platform, String> textos) {
        for (Platform preferida : List.of(Platform.INSTAGRAM, Platform.LINKEDIN)) {
            String t = textos.get(preferida);
            if (t != null && !t.isBlank()) {
                return t;
            }
        }
        return textos.values().stream().max(java.util.Comparator.comparingInt(String::length)).orElse("");
    }

    /**
     * Deja el texto listo para leer en telefono aunque el modelo mande espacios
     * al final de cada linea o demasiados saltos. Conserva la estructura y
     * reduce cualquier separacion grande a una sola linea en blanco.
     */
    static String normalizarFormato(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        String limpio = texto.replace("\r\n", "\n").replace('\r', '\n').strip();
        String[] lineas = limpio.split("\n", -1);
        StringBuilder salida = new StringBuilder();
        int vacias = 0;
        for (String linea : lineas) {
            String actual = linea.stripTrailing();
            if (actual.isBlank()) {
                vacias++;
                continue;
            }
            if (salida.length() > 0) {
                salida.append(vacias > 0 ? "\n\n" : "\n");
            }
            salida.append(actual.stripLeading());
            vacias = 0;
        }
        return salida.toString();
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

        // Primero quien es, al final que vende. El orden importa: lo que va
        // arriba es lo que el modelo toma como el centro del texto, y con el
        // catalogo arriba toda publicacion acababa siendo el mismo folleto con
        // otra foto encima.
        // Solo si hay voz que anunciar: con el negocio desconocido no se abre
        // una cabecera vacia, que es invitar al modelo a rellenar el hueco.
        if (hayAlguno(marca.historia(), marca.valores(), marca.frases(), marca.personalidadEs(), marca.pilaresEs())) {
            sb.append("- SU VOZ manda sobre cualquier forma 'correcta' de escribir."
                    + " Esto es lo que lo hace el y no otro negocio del mismo giro:\n");
        }
        agregar(sb, "Su historia (quien esta detras, por que existe)", marca.historia());
        agregar(sb, "Sus valores", marca.valores());
        agregar(sb, "Sus frases (usalas tal cual o inspirate en su estilo; no le atribuyas frases inventadas)",
                marca.frases() == null ? null : marca.frases().replace('\n', '|'));
        agregar(sb, "Como suena su marca (escribe asi)", marca.personalidadEs());
        agregar(sb, "De que quiere hablar en redes", marca.pilaresEs());
        agregar(sb, "A quien le habla", marca.publico());
        agregar(sb, "Nunca digas ni hagas esto", marca.evitar());
        agregar(sb, "Como trabaja (respetalo al escribir)", marca.comoTrabajaEs());
        agregar(sb, "Que vende o que destaca (CONTEXTO para saber de que va el negocio, NO material"
                + " para el texto: no enumeres sus servicios salvo que se vean en la imagen o la persona"
                + " los haya dicho)", marca.queVende());
        if (marca.hayContacto()) {
            // Los datos van con su limite pegado: se usan cuando la publicacion invita a escribir o visitar, y
            // no se inventan otros.
            sb.append("- Como contactarlo (usa SOLO estos datos, y solo si el texto invita a escribir o visitar): ")
                    .append(marca.contactoEs()).append('\n');
        }
        // Sale de cómo les fue a sus publicaciones, no de una suposición: pesa más que la intuición.
        agregar(sb, "Lo que le ha funcionado en sus redes", negocio.aprendido());
        return sb.toString();
    }

    /** Si alguno de esos campos trae algo. */
    private static boolean hayAlguno(String... valores) {
        for (String v : valores) {
            if (v != null && !v.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static void agregar(StringBuilder sb, String etiqueta, String valor) {
        if (valor != null && !valor.isBlank()) {
            sb.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }
}
