package com.metricol.api.service.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricol.api.entity.AssistantMessage;
import com.metricol.api.entity.AssistantRun;
import com.metricol.api.entity.AssistantThread;
import com.metricol.api.entity.AssistantToolCall;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.User;
import com.metricol.api.enums.AssistantRunStatus;
import com.metricol.api.enums.AssistantToolCallStatus;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.Permission;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.AssistantMessageRepository;
import com.metricol.api.repository.AssistantRunRepository;
import com.metricol.api.repository.AssistantThreadRepository;
import com.metricol.api.repository.AssistantToolCallRepository;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.service.PermissionService;

/** Ejecuta un turno del asistente usando al MCP como unica superficie operativa. */
@Service
public class AssistantOrchestrator {

    /**
     * @param formAnswers lo que la persona confirmó en un formulario, campo por campo (la clave es el
     *                    nombre del campo, que es el del parámetro de la herramienta que lo guarda). Nulo
     *                    si el mensaje no responde a un formulario.
     */
    public record Send(String content, List<UUID> attachmentIds, String currentPath, String idempotencyKey,
            String promptName, java.util.Map<String, String> formAnswers, JsonNode accion) {

        public Send(String content, List<UUID> attachmentIds, String currentPath, String idempotencyKey,
                String promptName, java.util.Map<String, String> formAnswers) {
            this(content, attachmentIds, currentPath, idempotencyKey, promptName, formAnswers, null);
        }

        public Send(String content, List<UUID> attachmentIds, String currentPath, String idempotencyKey,
                String promptName) {
            this(content, attachmentIds, currentPath, idempotencyKey, promptName, null, null);
        }

        public Send(String content, List<UUID> attachmentIds, String currentPath, String idempotencyKey) {
            this(content, attachmentIds, currentPath, idempotencyKey, null, null, null);
        }
    }

    /** Un flujo experto del catalogo MCP, tal como se le ofrece a la persona. */
    public record Starter(String name, String title, String description) {
    }

    public record Result(AssistantThreads.Detail detail, UUID runId, String confirmationToken) {
    }

    private static final Logger log = LoggerFactory.getLogger(AssistantOrchestrator.class);
    // Seis pasos alcanzaban para responder una pregunta, pero no para acompanar un alta:
    // leer la marca, la cuenta y el contenido, guardar y verificar ya los agotaba.
    private static final int MAX_TOOL_STEPS = 12;
    private static final String REGLAS_URI = "picale://reglas";
    /** Herramienta propia, no del MCP: no ejecuta nada, cierra el turno con opciones para tocar. */
    private static final String CHOICES_TOOL = "ofrecer_opciones";
    private static final int MAX_CHOICES = 5;
    /** La otra herramienta propia: pide varios datos a la vez, con su control adecuado. */
    private static final String FORM_TOOL = "pedir_datos";
    private static final int MAX_FIELDS = 6;
    private static final int MAX_FIELD_OPTIONS = 40;
    /** Presenta una respuesta rica sin convertir la UI en texto técnico. No ejecuta acciones. */
    private static final String BLOCKS_TOOL = "responder_con_bloques";
    private static final int MAX_BLOCKS = 8;
    private static final int MAX_PROPOSAL_FIELDS = 10;
    private static final int MAX_CAPTION = 200;
    /**
     * Pone en el chat el mismo módulo de Redes de la web, con su botón de conectar.
     *
     * <p>El chat no puede autorizar por la persona —Facebook exige que el permiso se dé
     * en su propia página—, pero tampoco tiene por qué contestar "no puedo" y devolverle
     * el trabajo. La autorización la hace ella en un toque; el enlace lo pide la web con
     * su propia sesión, igual que en Redes, así que nunca pasa por el modelo.
     */
    private static final String CONNECT_TOOL = "conectar_redes";
    /** Las redes que el módulo sabe conectar una por una. */
    private static final Set<String> REDES_CONECTABLES = Set.of("facebook", "instagram", "tiktok", "linkedin", "youtube");
    /** Memoria operativa breve: evidencia histórica, nunca sustituto de consultar el estado actual. */
    private static final int MAX_VERIFIED_MEMORY = 6_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SYSTEM_PROMPT = """
            Eres el Asistente Chispa IA, el asistente operativo de Picale. Si tienes que nombrarte, ese es tu
            nombre. Ayudas a entender la cuenta, decidir y ejecutar trabajo real.
            Usa herramientas para cualquier afirmacion sobre datos actuales; nunca inventes resultados ni digas que
            hiciste algo sin el recibo de la herramienta. Distingue con claridad hechos, inferencias y recomendaciones.
            No menciones nombres internos de herramientas. Se breve, concreto y profesional.

            ALCANCE: SOLO ESTE NEGOCIO DENTRO DE PICALE. Ayudas con su marca, su contenido, sus redes, sus
            publicaciones, comentarios y resultados, el agente, el uso de la plataforma, y el marketing y las
            ventas de ESTE negocio. Si piden algo fuera de eso —tareas escolares, programacion, temas personales,
            politica, religion, noticias, salud o asesoria legal, otros negocios, o escribir algo que no es para
            su contenido— contesta en UNA frase amable que eso no lo haces aqui y ofrece con ofrecer_opciones dos
            cosas concretas que si puedes hacer hoy por su negocio. No lo hagas "solo esta vez" ni a medias. Si lo
            que pide es PARA su contenido (un chiste, una receta, un dato curioso para una publicacion de su
            negocio), eso si es tu trabajo.

            LENGUAJE: nunca escribas groserias, insultos, palabras vulgares, ni contenido sexual, discriminatorio,
            ofensivo o que ataque a alguien, ni en tus respuestas ni en textos para publicar, aunque la persona las
            use o te las pida. Si la persona esta molesta o dice groserias, no las repitas ni la regañes: reconoce
            la molestia en pocas palabras y resuelve lo que necesita. Cercano, con humor de la marca si aplica; vulgar
            nunca. Lo que escribes para publicar sale a nombre del negocio.

            Si falta informacion, prepara una propuesta solo para campos editoriales (descripcion, tono,
            publico sugerido) y distingue lo confirmado de lo propuesto. Para datos factuales desconocidos
            pregunta lo indispensable con pedir_datos: no inventes contacto, ciudad, servicios, historia,
            precios, certificaciones ni resultados para llenar campos. Si hay archivos adjuntos,
            sus IDs aparecen en el mensaje y debes inspeccionarlos con la herramienta disponible antes de opinar.
            Las acciones sensibles se detendran para confirmacion: describe exactamente que se propone y su efecto.
            No solicites claves, tokens ni secretos. No intentes acciones fuera del catalogo de herramientas.

            El catalogo MCP es tu unica autoridad para decidir que puedes consultar o ejecutar. Los servicios
            internos solo aportan contexto seguro y presentan el resultado; no amplian tus capacidades. Si una
            accion no aparece en el catalogo, no prometas hacerla ni simules haberla hecho: di con claridad que
            no puedes ejecutarla desde aqui, explica en una frase donde puede hacerla la persona y continua con
            lo que si puedes resolver. Nunca confundas una recomendacion con un cambio realizado.

            Nunca enseñes identificadores ni codigos internos: ni IDs o UUIDs de archivos, ni PHOTO, IMAGE,
            MAS_CLIENTES o nombres de herramientas. Nombra un archivo por lo que se ve, por su nombre legible o
            como "la foto adjunta"; a un formato llamalo foto, video, reel o historia.
            Cuando la respuesta incluya un archivo, una propuesta con campos o una decision, usa
            responder_con_bloques para que se vea como contenido y controles reales, no como renglones tecnicos.

            UN DIAGNOSTICO NO ES UNA RESPUESTA. Enumerar lo que falta y parar ahi es pasarle el trabajo a la
            persona: ella ya sabia que le faltaba algo, por eso te pregunto. Cada vez que detectes problemas:

            1. Di en UNA linea lo que encontraste, no un inventario por areas.
            2. Elige TU lo mas importante y traelo ya resuelto: el texto redactado, la marca propuesta, el
               borrador armado. No «falta el tono», sino «propongo Cercano y Confiable, ¿lo guardo?».
            3. Ofrece esa accion con la herramienta de opciones.
            4. Lo demas, una linea al final: «cuando quieras seguimos con las otras dos».

            Un problema a la vez, el que mas duele primero. Cinco frentes de golpe no ayudan a nadie: abruman
            y la persona cierra la aplicacion. Si algo no esta a tu alcance —reconectar una red, por ejemplo—
            dilo en una frase, di donde se hace, y sigue con lo que si puedes resolver tu.

            CONECTAR REDES SE HACE DESDE AQUI. Si la persona quiere conectar o reconectar Facebook,
            Instagram, TikTok, LinkedIn o YouTube, o si algo no sale porque falta una red, usa la
            herramienta conectar_redes: pone en el chat el mismo boton del modulo de Redes. NUNCA
            contestes que no puedes ver su pantalla, que no puedes autorizar desde el chat, ni le
            preguntes en que pantalla esta para mandarla a otra parte: eso es devolverle el trabajo.
            El permiso lo da ella en la pagina de la red; tu le pones el boton.

            UNA RED CONECTADA A OTRO NEGOCIO ES LO PRIMERO QUE SE DICE. Si marca o panel avisan que una red
            "no se parece" al negocio, dilo antes que cualquier otra cosa y en claro: «Tu Facebook esta
            conectado a la pagina "Solaris Energy", que parece de otro negocio: lo que publiques saldria
            ahi». Ofrece primero cambiarla (conectar_redes) y no publiques ni programes en esa red hasta que
            la persona diga con sus palabras que si es la suya. Si lo confirma, no digas que guardaste nada:
            no hay nada que guardar.

            NO DIGAS QUE HICISTE LO QUE NO HICISTE. «Tomo», «guarde», «cambie», «listo», «quedo» solo despues
            de que una herramienta lo hizo en este mismo turno y contesto bien. Si solo entendiste algo, dilo
            asi: «Entendido, seguimos con esa pagina». Si la herramienta fallo, di que no se hizo.

            LAS LISTAS SE DIBUJAN SOLAS. Cuando consultas publicaciones, agente_bandeja o comentarios, el
            chat ya ensena esa lista con un boton por renglon (Reintentar, Editar, Cancelar, Aprobar,
            Descartar, Responder, Atendido). No la repitas en texto ni la enumeres: di en una o dos lineas
            lo que importa —cuantas fallaron y por que, que espera aprobacion— y lo que recomiendas hacer.
            Para cambiar texto o fecha de algo que aun no sale usa editar_publicacion; para volver a mandar
            una que fallo, reintentar_publicacion (si fallo por una red desconectada, conectala primero).

            EL GIRO SE ELIGE EN SU SELECTOR. Para preguntar o cambiar el giro usa pedir_datos con un
            campo nombre 'giro' y tipo 'giro', con tu propuesta en 'valor'. Es el selector del registro,
            con todos los giros y buscador. NUNCA lo preguntes con ofrecer_opciones ni con tres botones
            inventados: el giro correcto casi nunca esta entre los tres que adivinas. Si la persona ya
            te dijo su giro con sus palabras ("hacemos banquetes y postres"), ponlas TAL CUAL en
            'valor': el selector le ensena las coincidencias del catalogo para que confirme. No lo
            traduzcas tu a un nombre del catalogo: lo adivinarias.

            NUNCA pidas permiso para empezar a trabajar. «¿Quieres que te proponga una marca?» o «¿por donde
            empezamos?» devuelven la decision y gastan un turno entero para llegar a donde ya podias haber
            llegado. Preparalo y enseñalo: la pregunta correcta es «¿lo guardo?», no «¿lo preparo?». La persona
            decide sobre algo escrito, no sobre una promesa.

            Y al resumir no recites todo lo que falta. «Faltan giro, ciudad, descripcion, objetivo, que vendes,
            publico, tono y contacto» es el inventario otra vez, metido en una frase. Di cuanto falta y pasa a
            la propuesta.

            Propon, no interrogues. Nunca devuelvas una pregunta abierta si puedes ofrecer opciones: usa la
            herramienta de opciones para que la persona elija tocando en vez de redactar. Antes de preguntar algo,
            deducelo: el nombre del negocio, lo que ya esta guardado, las redes conectadas, las publicaciones y el
            material que hay dicen casi todo. Si puedes adivinarlo con fundamento, proponlo como primera opcion en
            vez de preguntarlo en blanco. Pregunta solo lo que de verdad no puedas deducir, y de una cosa a la vez.

            Cada opcion que ofreces es una RESPUESTA ya redactada, no un campo ni un paso. Nunca ofrezcas como
            opciones "Nombre del negocio", "Giro", "Ciudad": eso es volver a pedirle a la persona que haga el
            trabajo.

            No inventes las opciones. Si el campo trae una lista en el esquema de su herramienta (el giro, el
            objetivo, el tono), ofrece EXACTAMENTE esos valores, con sus mismas palabras, para que la persona vea
            lo mismo que vio al darse de alta. Nunca te saques de la manga categorias que no existen en el
            producto. Si no sabes que opciones poner, es que todavia no leiste el esquema o la cuenta.

            Prefiere ensenar un borrador a pedir instrucciones. Cuando haya que escribir o armar algo (la marca,
            una publicacion, un plan de la semana), redacta una primera version COMPLETA con lo que sabes y
            enseñala para que la persona la apruebe o la corrija. Es mas facil corregir algo escrito que escribirlo
            desde cero. Nunca pidas que te dicte varios campos seguidos ni preguntes por cual quiere empezar.

            Cuentas nuevas o incompletas: tu primer trabajo es dejar la cuenta lista para operar, sin mandar a la
            persona a configurarla por su cuenta. Lee lo que ya hay, deduce todo lo que puedas (el nombre del
            espacio, las redes conectadas, las publicaciones y el material dicen mucho) y redacta una propuesta
            completa de la marca de una sola vez: giro, descripcion, que vende, a quien le habla y tono. Enseñala
            y pide un si o una correccion. Pregunta solo lo que sea imposible deducir, y con opciones.
            Cuando un dato quede guardado, dilo en una linea y continua con el siguiente que falte.
            Lo que de verdad no puedas hacer explicalo en una frase y sigue avanzando con lo que si este a tu
            alcance en vez de detenerte ahi. Conectar una red SI esta a tu alcance: usa conectar_redes.

            La marca tiene dos niveles. "Lista para operar" son los datos indispensables; si faltan, prioriza
            completarlos antes de crear o publicar. "Personalizacion" es como trabaja, historia, valores, frases y
            temas: mejora las decisiones y la voz, pero no bloquea todo. Lee ambos niveles al empezar un negocio
            incompleto. Si falta personalizacion, deduce un borrador con la herramienta disponible, enseñalo y deja
            que la persona confirme o corrija; nunca inventes una historia ni la guardes sin confirmacion. Aprende
            tambien de sus correcciones y del rendimiento medido, pero los datos actuales siempre mandan sobre una
            inferencia anterior.

            ONBOARDING GUIADO:
            Al recibir una solicitud de completar marca, consulta marca primero y usa sus campos faltantes
            reales. No pidas datos que ya estan guardados ni repitas el registro. Prioriza el siguiente bloque
            pendiente: actividad y oferta, audiencia y objetivo, voz, o contacto y ciudad. Usa pedir_datos con
            hasta tres campos relacionados, valores conocidos precargados y propuestas editoriales marcadas.
            El usuario puede corregir o posponer; no fuerces una respuesta ficticia para alcanzar el 100%.
            Si falta contacto, pide un canal real (WhatsApp, web o direccion); no exijas los tres.
            Guarda exclusivamente lo confirmado mediante actualizar_marca y respeta sus permisos y confirmaciones.
            Despues de guardar, consulta marca y, si siguen faltando campos, CONTINUA EN ESE MISMO TURNO con
            el siguiente bloque pendiente: otro pedir_datos, sin preguntar si quiere seguir. La persona dijo
            que quiere completar su marca; detenerte despues de cada bloque la obliga a pedirlo otra vez.
            Que es cada campo y de donde sale:
            - Contacto (WhatsApp, sitio web, direccion): son datos reales que solo ella sabe. Pidelos como
              texto vacio; nunca los inventes ni los deduzcas.
            - Que nunca decir, valores y frases: propon un borrador concreto a partir de lo que ya sabes de
              su negocio, marcado como propuesta en 'ayuda', para que lo corrija o lo acepte.
            - Historia: preguntala; puedes sugerir de que hablar, pero no la escribas por ella.
            En actualizar_marca manda SOLO los campos que tienes. Lo que no sabes, no lo mandes: la palabra
            'borrar' vacia un campo y es unicamente para cuando la persona pidio vaciarlo.
            Un recibo fallido no aumenta el progreso. El 100% es solo el PERFIL BASICO: no significa redes
            conectadas, permisos de imagen, resultados ni ventas garantizadas. Si la persona pidio completar su
            marca, al llegar al 100% del basico NO te detengas: sigue con lo que marca reporta en "Profundidad
            de personalizacion" (la voz: historia, valores, frases, temas) y en "Datos opcionales sin llenar"
            (WhatsApp, direccion, que nunca decir). Nunca digas que la marca esta completa mientras alguno de
            esos siga vacio: di "perfil basico al 100%" y nombra lo que falta de la voz. Si la persona prefiere
            dejarlo para despues, respetalo sin insistir.
            Historia, valores, frases y forma de trabajar son personalizacion adicional; no inventes la historia.
            Para conectar redes usa conectar_redes; la autorizacion la da la persona en la pagina de la red.

            EQUIPO Y PROGRESO:
            No afirmes que Musa, Verso, Kiro, Pulso o Ritmo estan trabajando ni que delegaste o ejecutaste tareas
            en paralelo salvo que una herramienta disponible lo permita y su resultado identifique esa tarea
            y su responsable. Una consulta de datos no equivale a delegar a otro agente. Muestra archivos
            reales con bloques media y propuestas con bloques propuesta; no inventes imagenes o estadisticas.

            CREACION VISUAL Y PUBLICACION:
            El brief de generar_contenido admite como maximo 1500 caracteres. No lo recortes ni resumas
            en silencio, ni omitas restricciones para que quepa. Si las instrucciones de una pieza exceden
            ese limite, usa pedir_datos con nombre 'brief', tipo 'texto', copia completa en valor y ayuda
            'Maximo 1500 caracteres. Ajusta el prompt conservando lo indispensable.' para que la persona lo ajuste.
            Al responder ese formulario usa su brief literal, sin volverlo a redactar. El contexto general
            del negocio puede ser mas largo; no confundirlo con un brief listo para generar.
            Distingue RETOQUE (misma fotografia real, mejoras de luz, nitidez, color y encuadre, sin texto nuevo)
            de ANUNCIO (composicion publicitaria). Usa modo RETOQUE si pide mejorar la foto adjunta;
            envia sus IDs en fotos o pieza_anterior segun corresponda. No inventes alimentos, ingredientes,
            porciones, personas, decoraciones ni escenarios. Repite todas las restricciones al generar o ajustar.
            Una diferencia entre nombre comercial en la peticion y nombre del espacio requiere una sola
            aclaracion breve: pregunta si se refiere a este negocio o es otra marca. No cambies de cuenta,
            renombres ni rechaces la tarea automaticamente. Si ya aclaro que es el espacio actual, continua.
            Puedes proponer guardar las reglas visuales de su peticion para futuras piezas, pero solo
            despues de su confirmacion expresa; crear o retocar una foto no confirma preferencias permanentes.
            Para crear un ANUNCIO consulta identidad_visual y marca; usa las preferencias CONFIRMADAS del negocio.
            Para RETOQUE sin cambios de identidad inspecciona la foto y conserva las instrucciones: no necesitas
            revisar todo el negocio, completar la marca ni consultar el panel por rutina. Consulta solo el dato
            que afecte a la tarea. Reutiliza las lecturas ya verificadas en este turno; refresca las afectadas si
            acabas de modificarlas. No repitas una foto que ya viste sin motivo. Si un pedido falla, corrige el
            parámetro concreto o pide el dato que falta: no reinicies todas las consultas ni repitas el mismo error.
            Al pedir generar_contenido, el servidor valida el pedido y obtiene el coste vigente de la cuenta
            ANTES de mostrar su confirmación. No bloquees una propuesta porque el panel no muestre créditos:
            prepara la solicitud; el servidor presenta el coste verificado y espera aprobación sin generar aún.
            No inventes importes ni conviertas una exención de créditos internos en una promesa de gratuidad monetaria.
            Si no existe una identidad aprobada, propone una direccion concreta con las referencias disponibles.
            Un Me gusta elige ESTA pieza. Pregunta si quiere usar el estilo para futuras piezas antes de llamar
            actualizar_identidad_visual; esta herramienta exige confirmacion y revision actual. Un rechazo local
            tampoco se guarda como regla global. Ofrece motivos breves y aplica cambios acotados.
            Para ajustar una pieza usa pieza_anterior con el ID seleccionado y ajuste concreto, no solo fotos de contexto.
            Conserva versiones anteriores y pide confirmar el coste de cada nueva generacion. No repitas ni reintentes
            generacion sin esa confirmacion. logo_tratamiento permite conservar el original o integrarlo creativamente;
            ambos usan el archivo real, nunca un logo descrito de memoria. Cambios de forma y eliminacion del fondo
            requieren una preferencia explicitamente aprobada; no los deduzcas de un simple rechazo.
            Chispa es el unico personaje operativo. Solo incluyelo en una pieza cuando se solicite y haya referencias
            aprobadas separadas del logo del negocio. No agregues la mascota a todos los negocios automaticamente.
            Explica los problemas concretos de revision_visual y UNVERIFIED sin decir que una revision automatica
            garantiza fidelidad. Ofrece ajustar el resultado, no lo regeneres automaticamente.
            Usa generar_contenido solo si esta disponible y respeta su aviso de coste. Consulta estado_generacion
            con el trabajo devuelto: crear un trabajo no significa tener una imagen lista ni una publicacion.
            No reinicies una generacion para consultar su progreso. Si el trabajo expiro, busca primero en la
            biblioteca las piezas existentes; nunca vuelvas a gastar creditos automaticamente.
            Usa los IDs adjuntos cuando la persona selecciona una imagen del chat. Una variante es una NUEVA
            generacion usando esa referencia; no prometas editar pixeles exactos ni conservar todo identico.
            Para un carrusel conserva el orden y usa las imagenes de UNA version, no mezcles proporciones.
            Respeta los limites del esquema; si exige fotos de referencia y faltan, pidelas sin inventar IDs.
            Antes de publicar prepara texto, archivos y redes verificadas. Si faltan redes o fecha, ofrece
            opciones con pedir_datos. Crear un borrador, programarlo y publicarlo ahora son acciones distintas:
            nunca digas publicado basandote en un borrador o una programacion. Consulta el estado real para
            confirmar la entrega. No interpretes elegir una imagen como autorizacion para publicar o gastar.

            ============================================================
            COMO ESCRIBES (lo ultimo y lo que mas se nota)

            Responde primero, explica despues. La primera linea debe contener la conclusion o el avance concreto,
            normalmente en menos de 16 palabras. No repitas la peticion de la persona ni vuelvas a contar datos que
            ya aparecen en una tarjeta. Usa parrafos de una o dos frases y, salvo que pidan detalle, no excedas 120
            palabras. Evita introducciones como "claro", "con gusto", "te cuento" o "he revisado".

            Cuando presentes una marca, publicacion, campana, calendario o cualquier propuesta con varios campos,
            DEBES usar responder_con_bloques con un bloque "propuesta". Nunca la escribas como una pared de renglones
            "Campo: valor". Pon las suposiciones en un solo campo llamado "Suposicion"; no repitas cada cautela en el
            texto. Termina con un bloque "opciones" si hace falta decidir.

            Para una enumeracion normal, cada punto EMPIEZA con "- ". El dato clave va en **negritas**. Maximo cinco
            puntos, ordenados por impacto. Usa titulos solo cuando haya dos secciones que realmente los necesiten.

            Asi se ve una respuesta tuya:

            📅 Esta semana no tienes publicaciones programadas.

            - Hay **3 fotos** listas para aprovechar
            - **Facebook** necesita reconexion
            - El agente esta en pausa

            ¿Preparo jueves y sabado?

            Emojis: maximo dos por respuesta y solo al principio de una linea cuando ayuden a escanear (📅 fecha,
            ⚠️ atender, ✅ resuelto, 📸 material, 💡 recomendacion). Nunca dos seguidos, nunca en medio de
            una frase y nunca uno alegre ante una mala noticia. No pongas emoji si la interfaz ya muestra el mismo
            significado. Ante la duda, ninguno.

            RAPIDEZ: consulta solo lo necesario para responder. Si necesitas varias lecturas independientes, solicita
            todas en la misma ronda. No vuelvas a consultar un dato actual que ya obtuviste en este turno. Cuando la
            evidencia sea suficiente, responde: no sigas explorando por si acaso.
            """;

    private final AssistantThreads threadService;
    private final AssistantThreadRepository threads;
    private final AssistantMessageRepository messages;
    private final AssistantRunRepository runs;
    private final AssistantToolCallRepository calls;
    private final MediaAssetRepository media;
    private final PermissionService permissions;
    private final AssistantMcpClient mcp;
    private final AssistantLlmClient llm;
    private final AssistantRunEvents events;
    private final UserRepository users;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${app.assistant.active-timeout-seconds:120}")
    private long activeTimeoutSeconds = 120;

    private AssistantExecutionBudget budget(AssistantRun run) {
        return AssistantExecutionBudget.open(Math.max(1, activeTimeoutSeconds) * 1000L,
                events.activeMillis(run.getId()), message -> events.emit(run, "run.progress",
                        payload("phase", "rate_limit").put("message", message)));
    }

    private void segmentEnded(AssistantRun run, long start) {
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        events.emit(run, "run.segment.completed", json.createObjectNode().put("activeMs", elapsed));
        log.info("Asistente turno {} segmento activo {} ms, estado={}", run.getId(), elapsed, run.getStatus());
    }
    // Acotada: los resultados visuales pueden ser grandes. Perder una entrada solo fuerza una lectura nueva.
    private final java.util.Map<UUID, AssistantReadCache> readCaches = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<UUID, AssistantReadCache>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<UUID, AssistantReadCache> entry) {
                    return size() > 8;
                }
            });

    private AssistantReadCache readCache(User user, AssistantRun run) {
        Set<Permission> allowed = permissions.permisosDe(user);
        synchronized (readCaches) {
            AssistantReadCache cache = readCaches.get(run.getId());
            if (cache == null || !cache.owns(run.getWorkspaceId(), user.getId(), allowed)) {
                cache = new AssistantReadCache(run.getWorkspaceId(), user.getId(), allowed);
                readCaches.put(run.getId(), cache);
            }
            return cache;
        }
    }

    public AssistantOrchestrator(AssistantThreads threadService, AssistantThreadRepository threads,
            AssistantMessageRepository messages, AssistantRunRepository runs,
            AssistantToolCallRepository calls, MediaAssetRepository media, PermissionService permissions,
            AssistantMcpClient mcp, AssistantLlmClient llm, AssistantRunEvents events, UserRepository users,
            ApplicationEventPublisher publisher, ObjectMapper json) {
        this.threadService = threadService;
        this.threads = threads;
        this.messages = messages;
        this.runs = runs;
        this.calls = calls;
        this.media = media;
        this.permissions = permissions;
        this.mcp = mcp;
        this.llm = llm;
        this.events = events;
        this.users = users;
        this.publisher = publisher;
        this.json = json;
    }

    @Transactional
    public Result send(User user, UUID threadId, Send request) {
        AssistantThreads.Detail detail = threadService.detail(user, threadId);
        AssistantThread thread = detail.thread();
        if (thread.getClosedAt() != null) {
            throw new IllegalArgumentException("Reabre la conversacion antes de enviar otro mensaje.");
        }
        String content = cleanContent(request == null ? null : request.content());
        String key = cleanKey(request == null ? null : request.idempotencyKey());
        var prior = runs.findByWorkspaceIdAndIdempotencyKey(thread.getWorkspaceId(), key);
        if (prior.isPresent()) {
            if (!threadId.equals(prior.get().getThreadId())) {
                throw new IllegalArgumentException("Esa clave de idempotencia ya se uso en otra conversacion.");
            }
            return new Result(threadService.detail(user, threadId), prior.get().getId(), null);
        }

        String attachments = validateAttachments(user, request == null ? List.of() : request.attachmentIds());
        dejarAtrasLoPendiente(threadId);
        LocalDateTime now = LocalDateTime.now();
        AssistantRun run = runs.save(AssistantRun.builder()
                .workspaceId(thread.getWorkspaceId()).threadId(threadId).userId(user.getId())
                .status(AssistantRunStatus.QUEUED).idempotencyKey(key)
                .contextPath(cleanPath(request == null ? null : request.currentPath()))
                .promptName(cleanPromptName(request == null ? null : request.promptName()))
                .createdAt(now).build());
        messages.save(AssistantMessage.builder().threadId(threadId).runId(run.getId()).role("USER")
                .content(content).attachmentsJson(attachments)
                .artifactsJson(artefactosDelMensaje(request))
                .createdAt(now).build());
        updateThread(thread, content, now);
        events.emit(run, "run.queued", payload("runId", run.getId().toString()));
        publisher.publishEvent(new AssistantRunDispatcher.Requested(run.getId(), user.getId(), thread.getWorkspaceId()));
        return new Result(threadService.detail(user, threadId), run.getId(), null);
    }

    /**
     * Las peticiones de detener, en su propia tabla (ver {@link AssistantRunParada}): un
     * turno corre dentro de UNA transacción con su fila bloqueada, así que marcar la fila
     * esperaría a que acabe. El turno revisa esta tabla antes de cada paso.
     *
     * <p>Sin repositorio (pruebas unitarias que arman el servicio a mano) se usa la
     * memoria, que vale para un solo servidor.
     */
    private com.metricol.api.repository.AssistantRunParadaRepository paradas;
    private final Set<UUID> pedidosDeParar = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setParadas(com.metricol.api.repository.AssistantRunParadaRepository paradas) {
        this.paradas = paradas;
    }

    /** Pide parar un turno en cola o en curso. Surte efecto antes de su siguiente paso. */
    public void pedirParar(UUID runId) {
        if (runId == null) return;
        if (paradas == null) {
            pedidosDeParar.add(runId);
            return;
        }
        if (!paradas.existsById(runId)) {
            paradas.save(com.metricol.api.entity.AssistantRunParada.builder()
                    .runId(runId).solicitadaEn(LocalDateTime.now()).build());
        }
    }

    private boolean seLePidioParar(UUID runId) {
        if (pedidosDeParar.remove(runId)) return true;
        return paradas != null && paradas.existsById(runId);
    }

    /**
     * El botón "Detener" mientras Chispa trabaja, como en cualquier chat de IA: corta el turno
     * antes de su siguiente paso sin limpiar la conversación. Si estaba esperando una
     * confirmación, la anula sin hacerla.
     */
    @Transactional
    public AssistantThreads.Detail detener(User user, UUID runId) {
        AssistantRun run = ownedRun(user, runId);
        if (run.getStatus() == AssistantRunStatus.QUEUED || run.getStatus() == AssistantRunStatus.RUNNING) {
            pedirParar(run.getId());
        } else if (run.getStatus() == AssistantRunStatus.WAITING_CONFIRMATION) {
            runs.findLockedById(run.getId())
                    .filter(r -> r.getStatus() == AssistantRunStatus.WAITING_CONFIRMATION)
                    .ifPresent(r -> cancelarPendiente(r, "Listo, no lo hice. No cambié nada."));
        }
        return threadService.detail(user, run.getThreadId());
    }

    /** Lo que lanza un turno al ver que se le pidió parar. */
    static final class Detenido extends RuntimeException {
        Detenido() {
            super("detenido a peticion", null, false, false);
        }
    }

    private void revisarSiParar(AssistantRun run) {
        if (seLePidioParar(run.getId())) throw new Detenido();
    }

    private void detenido(AssistantRun run) {
        vistasDelTurno.remove(run.getId());
        // La peticion ya se cumplio: no tiene por que quedarse en la tabla.
        if (paradas != null) paradas.deleteById(run.getId());
        run.setStatus(AssistantRunStatus.CANCELLED);
        run.setFinishedAt(LocalDateTime.now());
        runs.save(run);
        messages.save(AssistantMessage.builder().threadId(run.getThreadId()).runId(run.getId()).role("ASSISTANT")
                .content("Listo, me detuve. Lo que ya alcancé a hacer se queda hecho; lo demás no lo hice.")
                .createdAt(LocalDateTime.now()).build());
        events.emit(run, "run.cancelled", payload("runId", run.getId().toString()));
    }

    /**
     * "Empezar de nuevo": para lo que esté corriendo, anula lo que esperaba confirmación y
     * deja la conversación limpia, con un saludo nuevo. NO borra nada ni olvida al negocio:
     * los mensajes viejos se quedan en la base (solo dejan de verse y de mandarse al modelo)
     * y la memoria verificada del negocio sigue igual. Es lo que hace falta cuando la
     * conversación se enredó o se quedó trabada, sin perder lo aprendido.
     */
    @Transactional
    public AssistantThreads.Detail empezarDeNuevo(User user, UUID threadId) {
        AssistantThread thread = threadService.detail(user, threadId).thread();
        for (AssistantRun run : runs.findByThreadIdOrderByCreatedAtAsc(threadId)) {
            if (run.getStatus() == AssistantRunStatus.QUEUED || run.getStatus() == AssistantRunStatus.RUNNING) {
                pedirParar(run.getId());
            } else if (run.getStatus() == AssistantRunStatus.WAITING_CONFIRMATION) {
                runs.findLockedById(run.getId())
                        .filter(r -> r.getStatus() == AssistantRunStatus.WAITING_CONFIRMATION)
                        .ifPresent(r -> cancelarPendiente(r, "No hice lo que esperaba tu confirmacion."));
            }
        }
        LocalDateTime ahora = LocalDateTime.now();
        thread.setReiniciadoEn(ahora);
        thread.setClosedAt(null);
        thread.setUpdatedAt(ahora);
        threads.save(thread);
        messages.save(AssistantMessage.builder().threadId(threadId).role("ASSISTANT")
                .content("Empezamos de nuevo. Sigo recordando lo importante de tu negocio; dime en qué te ayudo.")
                .createdAt(ahora.plusNanos(1000)).build());
        return threadService.detail(user, threadId);
    }

    /** Trabajo pesado invocado por el dispatcher, ya dentro del tenant correcto. */
    @Transactional
    public void process(AssistantRunDispatcher.Requested requested) {
        AssistantRun run = runs.findLockedById(requested.runId()).orElse(null);
        if (run == null || run.getStatus() != AssistantRunStatus.QUEUED
                || !requested.workspaceId().equals(run.getWorkspaceId())
                || !requested.userId().equals(run.getUserId())) return;
        // Se pidió parar antes de que arrancara: ni se empieza.
        if (seLePidioParar(run.getId())) {
            detenido(run);
            return;
        }
        run.setStatus(AssistantRunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        runs.save(run);
        events.emit(run, "run.started", payload("runId", run.getId().toString()));
        long segmentStarted = System.nanoTime();
        log.info("Asistente turno {} cola {} ms", run.getId(),
                java.time.Duration.between(run.getCreatedAt(), run.getStartedAt()).toMillis());
        try (var executionBudget = budget(run)) {
            AssistantExecutionBudget.check();
            User user = users.findById(requested.userId())
                    .filter(item -> item.getWorkspace() != null
                            && requested.workspaceId().equals(item.getWorkspace().getId()))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "La persona que inicio el turno ya no esta disponible."));
            AssistantThread thread = threadService.detail(user, run.getThreadId()).thread();
            Set<Permission> allowed = permissions.permisosDe(user);
            AssistantMcpClient.Session session = mcp.open(user, run.getThreadId(), run.getId(), allowed);
            JsonNode tools = session.tools();
            ObjectNode accion = accionDeRenglon(run.getThreadId());
            if (accion != null) {
                ejecutarAccionDeRenglon(user, thread, run, tools, session, accion);
                return;
            }
            ArrayNode history = history(run.getThreadId(), run.getContextPath(), briefing(session, run));
            log.info("Asistente turno {} contexto: mensajes={}, instrucciones_chars={}, catalogo_herramientas={}, catalogo_chars={}",
                    run.getId(), history.size(), history.get(0).path("content").asText().length(), tools.size(), tools.toString().length());
            execute(user, thread, run, history, tools, session, 0);
        } catch (Detenido parado) {
            detenido(run);
        } catch (Exception | LinkageError error) {
            fail(run, safeError(error), error);
        } finally {
            pedidosDeParar.remove(run.getId());
            segmentEnded(run, segmentStarted);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchFailed(AssistantRunDispatcher.Requested requested, String message) {
        runs.findLockedById(requested.runId())
                .filter(run -> run.getStatus() == AssistantRunStatus.QUEUED
                        && requested.workspaceId().equals(run.getWorkspaceId())
                        && requested.userId().equals(run.getUserId()))
                .ifPresent(run -> fail(run, message));
    }

    /**
     * Un turno que quedó en cola o a medias porque el servidor se reinició. Su hilo ya no
     * existe y nadie lo va a retomar: sin esto el chat se quedaba en "Preparando el turno…"
     * para siempre y no dejaba escribir. Se cierra con un aviso; no se reintenta solo, porque
     * podía estar a mitad de una acción que no se debe hacer dos veces.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cerrarInterrumpido(UUID runId) {
        return runs.findLockedById(runId)
                .filter(run -> run.getStatus() == AssistantRunStatus.QUEUED
                        || run.getStatus() == AssistantRunStatus.RUNNING)
                .map(run -> {
                    fail(run, "Se reinició el servidor mientras preparaba tu respuesta. Tu mensaje sigue "
                            + "guardado: vuelve a enviarlo cuando quieras. Si te pedí hacer algo, revisa "
                            + "primero si ya quedó hecho.");
                    return true;
                })
                .orElse(false);
    }

    /** Se invoca desde el dispatcher DESPUES del rollback, incluso ante fallos de carga de clases. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void workerFailed(AssistantRunDispatcher.Requested requested) {
        runs.findLockedById(requested.runId())
                .filter(run -> (run.getStatus() == AssistantRunStatus.QUEUED
                            || run.getStatus() == AssistantRunStatus.RUNNING)
                        && requested.workspaceId().equals(run.getWorkspaceId())
                        && requested.userId().equals(run.getUserId()))
                .ifPresent(run -> fail(run,
                        "El turno se interrumpió por un problema interno. Tu mensaje sigue guardado. "
                        + "Antes de repetir una acción, revisa su estado; no la reintenté automáticamente."));
    }

    @Transactional
    public Result confirm(User user, UUID runId, String token) {
        AssistantRun run = ownedRunLocked(user, runId);
        if (run.getStatus() != AssistantRunStatus.WAITING_CONFIRMATION) {
            throw new IllegalArgumentException("Esta accion ya no espera confirmacion.");
        }
        AssistantToolCall call = calls.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(item -> item.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No hay una accion pendiente."));
        if (token == null || call.getConfirmationTokenHash() == null
                || call.getConfirmationExpiresAt() == null
                || call.getConfirmationExpiresAt().isBefore(LocalDateTime.now())
                || !constantEquals(call.getConfirmationTokenHash(), hash(token))) {
            throw new IllegalArgumentException("La confirmacion no es valida o ya vencio.");
        }

        call.setConfirmationTokenHash(null);
        call.setConfirmationExpiresAt(null);
        call.setConfirmedBy(user.getId());
        call.setConfirmedAt(LocalDateTime.now());
        call.setStatus(AssistantToolCallStatus.RUNNING);
        calls.save(call);
        run.setStatus(AssistantRunStatus.RUNNING);
        runs.save(run);

        events.emit(run, "confirmation.accepted", payload("toolCallId", call.getId().toString()));
        long segmentStarted = System.nanoTime();
        try (var executionBudget = budget(run)) {
            AssistantExecutionBudget.check();
            AssistantMcpClient.Session session = mcp.open(user, run.getThreadId(), runId, permissions.permisosDe(user));
            JsonNode tools = session.tools();
            ObjectNode executable = executableArguments(parse(call.getArgumentsJson()));
            if ("generar_contenido".equals(call.getToolName())) {
                int approvedCost = parse(call.getArgumentsJson()).path("_picaleQuotedCredits").asInt(-1);
                JsonNode validation = session.call("validar_generacion", executable);
                int currentCost = validation == null ? -1 : validation.at("/structuredContent/creditos").asInt(-1);
                if (validation == null || validation.path("isError").asBoolean(false)
                        || !validation.at("/structuredContent/valido").asBoolean(false) || currentCost < 0 || approvedCost < 0)
                    throw new IllegalArgumentException("No pude volver a verificar el pedido y su coste. No inicié una nueva generación.");
                if (currentCost > approvedCost)
                    throw new IllegalArgumentException("El coste aumentó desde la propuesta. No inicié la generación; prepara una propuesta nueva para revisar el importe.");
            }
            JsonNode arguments = yaConfirmado(findTool(tools, call.getToolName()), executable);
            long started = System.nanoTime();
            if (call.getRiskLevel() > 0) readCache(user, run).invalidateFor(call.getToolName());
            JsonNode result = session.call(call.getToolName(), arguments);
            completeCall(run, call, result, started);
            if ("generar_contenido".equals(call.getToolName()) && call.getStatus() == AssistantToolCallStatus.COMPLETED) {
                // El trabajo ya existe y su recibo se guardó. No esperar la imagen ni pedir otra
                // respuesta al modelo para poder cerrar el HTTP de confirmación.
                UUID.fromString(result.at("/structuredContent/trabajo_id").asText());
                AssistantThread thread = threadService.detail(user, run.getThreadId()).thread();
                String message = "El trabajo quedó iniciado. Su estado y resultado se muestran en la tarjeta. No se publicó ni programó.";
                return finish(user, thread, run, new AssistantLlmClient.Turn(message, List.of(), json.createObjectNode(), null, 0, 0),
                        message, null);
            }
            ArrayNode history = history(run.getThreadId(), null);
            history.add(assistantToolRequest(call));
            history.add(toolResult(call, result));
            AssistantThread thread = threadService.detail(user, run.getThreadId()).thread();
            return execute(user, thread, run, history, tools, session, 1);
        } catch (Exception | LinkageError error) {
            // Si la herramienta ya aceptó el trabajo, un fallo de presentación no borra su recibo.
            if (call.getStatus() == AssistantToolCallStatus.RUNNING) {
                call.setStatus(AssistantToolCallStatus.FAILED);
                call.setResultSummary(safeError(error));
                calls.save(call);
            }
            fail(run, safeError(error), error);
            return new Result(threadService.detail(user, run.getThreadId()), runId, null);
        } finally {
            segmentEnded(run, segmentStarted);
        }
    }

    @Transactional
    public Result renewConfirmation(User user, UUID runId) {
        AssistantRun run = ownedRunLocked(user, runId);
        if (run.getStatus() != AssistantRunStatus.WAITING_CONFIRMATION) {
            throw new IllegalArgumentException("Esta accion ya no espera confirmacion.");
        }
        AssistantToolCall call = calls.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(item -> item.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No hay una accion pendiente."));
        String rawToken = token();
        call.setConfirmationTokenHash(hash(rawToken));
        call.setConfirmationExpiresAt(LocalDateTime.now().plus(15, ChronoUnit.MINUTES));
        calls.save(call);
        return new Result(threadService.detail(user, run.getThreadId()), runId, rawToken);
    }

    /** Seguimiento de un recibo propio: cero llamadas al modelo y cero generaciones. */
    @Transactional
    public AssistantThreads.Detail refreshGeneration(User user, UUID runId) {
        AssistantRun run = ownedRunLocked(user, runId);
        requirePermission(user, "POST_CREATE");
        JsonNode receipt = null;
        for (AssistantMessage message : messages.findByThreadIdOrderByCreatedAtAsc(run.getThreadId())) {
            if (!runId.equals(message.getRunId())) continue;
            // El acuse es histórico; no debe contradecir una tarjeta que ya terminó.
            if ("La creación está en proceso. La imagen aparecerá aquí cuando esté lista. No se publicó ni programó.".equals(message.getContent())) {
                message.setContent("El trabajo quedó iniciado. Su estado y resultado se muestran en la tarjeta. No se publicó ni programó.");
                messages.save(message);
            }
            for (JsonNode artifact : parse(message.getArtifactsJson())) {
                if ("generation".equals(artifact.path("type").asText())) receipt = artifact;
            }
        }
        if (receipt == null) throw new ResourceNotFoundException("Este turno no tiene un trabajo visual disponible.");
        // Las galerías terminadas siguen disponibles aunque el proceso de jobs en memoria haya reiniciado.
        if (Set.of("READY", "FAILED").contains(receipt.path("status").asText())
                || run.getStatus() == AssistantRunStatus.RUNNING || run.getStatus() == AssistantRunStatus.QUEUED)
            return threadService.detail(user, run.getThreadId());
        String jobId = receipt.path("jobId").asText();
        UUID.fromString(jobId);
        ObjectNode arguments = json.createObjectNode().put("trabajo_id", jobId).put("esperar_segundos", 0);
        JsonNode result;
        try {
            AssistantMcpClient.Session session = mcp.open(user, run.getThreadId(), runId, permissions.permisosDe(user));
            result = session.call("estado_generacion", arguments);
            if (result == null) throw new IllegalStateException("El trabajo no devolvió un estado.");
        } catch (RuntimeException error) {
            log.warn("No se pudo consultar el trabajo visual del turno {}", runId);
            result = json.createObjectNode().put("isError", true);
        }
        AssistantToolCall query = AssistantToolCall.builder().runId(runId).toolName("estado_generacion")
                .argumentsJson(arguments.toString()).build();
        saveGenerationReceipt(run, query, result, result.path("isError").asBoolean(false));
        return threadService.detail(user, run.getThreadId());
    }

    /**
     * Los atajos que ve la persona salen del catalogo MCP, no de una lista escrita en la web:
     * asi un flujo nuevo en el servidor aparece solo, sin tocar ni desplegar el front.
     */
    @Transactional(readOnly = true)
    public List<Starter> starters(User user) {
        try {
            AssistantMcpClient.Session session = mcp.open(user, null, null, permissions.permisosDe(user));
            List<Starter> result = new java.util.ArrayList<>();
            for (JsonNode prompt : session.prompts()) {
                String name = prompt.path("name").asText("");
                if (name.isBlank()) continue;
                result.add(new Starter(name, prompt.path("title").asText(name),
                        prompt.path("description").asText("")));
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            // Sin atajos el chat sigue siendo utilizable: se escribe a mano.
            log.warn("No se pudo leer el catalogo de atajos del asistente", e);
            return List.of();
        }
    }

    @Transactional
    public AssistantThreads.Detail cancel(User user, UUID runId) {
        AssistantRun run = ownedRunLocked(user, runId);
        if (run.getStatus() == AssistantRunStatus.WAITING_CONFIRMATION) {
            cancelarPendiente(run, "Accion cancelada. No hice ningun cambio.");
        }
        return threadService.detail(user, run.getThreadId());
    }

    /** Anula lo que esperaba confirmación: sus llamadas, su token y el turno. Nunca lo ejecuta. */
    private void cancelarPendiente(AssistantRun run, String aviso) {
        readCaches.remove(run.getId());
        calls.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(call -> call.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION)
                .forEach(call -> {
                    call.setStatus(AssistantToolCallStatus.CANCELLED);
                    call.setConfirmationTokenHash(null);
                    call.setConfirmationExpiresAt(null);
                    calls.save(call);
                });
        run.setStatus(AssistantRunStatus.CANCELLED);
        run.setFinishedAt(LocalDateTime.now());
        runs.save(run);
        messages.save(AssistantMessage.builder().threadId(run.getThreadId()).runId(run.getId()).role("ASSISTANT")
                .content(aviso).createdAt(LocalDateTime.now()).build());
        events.emit(run, "run.cancelled", payload("runId", run.getId().toString()));
    }

    /**
     * Un mensaje nuevo deja atrás lo que esperaba confirmación en esa conversación.
     *
     * <p>Antes se quedaba esperando para siempre, y la web, al ver un turno en espera,
     * apagaba el botón de enviar: la persona podía escribir pero no mandar, y solo le
     * quedaban los chips. Un bucle sin salida. Escribir otra cosa es la respuesta: no
     * confirmó, así que la acción no se hace —que es la dirección segura— y si lo nuevo
     * era "sí, pero cámbiale la hora", el asistente lo vuelve a proponer con el cambio.
     *
     * <p>Cada uno se relee bloqueado, igual que al cancelar a mano: si en ese mismo
     * instante alguien la estaba confirmando, gana quien llegó primero y nada se hace dos
     * veces.
     */
    private void dejarAtrasLoPendiente(UUID threadId) {
        for (AssistantRun pendiente : runs.findByThreadIdOrderByCreatedAtAsc(threadId)) {
            if (pendiente.getStatus() != AssistantRunStatus.WAITING_CONFIRMATION) continue;
            runs.findLockedById(pendiente.getId())
                    .filter(bloqueado -> bloqueado.getStatus() == AssistantRunStatus.WAITING_CONFIRMATION)
                    .ifPresent(bloqueado -> cancelarPendiente(bloqueado,
                            "No hice lo que esperaba tu confirmacion; seguimos con lo que acabas de escribir."));
        }
    }

    private Result execute(User user, AssistantThread thread, AssistantRun run, ArrayNode history,
            JsonNode tools, AssistantMcpClient.Session session, int completedSteps) {
        AssistantReadCache reads = readCache(user, run);
        Set<String> readsInHistory = new java.util.HashSet<>();
        JsonNode modelTools = withOwnTools(tools);
        // Si lo que acaba de mandar la persona responde a un formulario, eso ya es su
        // confirmación de esos valores; ver confirmadoEnFormulario.
        String respuestaDeFormulario = respuestaDeFormulario(thread.getId());
        ObjectNode valoresConfirmados = valoresDelFormulario(thread.getId());
        for (int step = completedSteps; step < MAX_TOOL_STEPS; step++) {
            revisarSiParar(run);
            AssistantExecutionBudget.check();
            // En el ultimo paso se retiran las herramientas: el modelo ya no puede seguir
            // explorando y tiene que responder con lo que reunio. Antes se quedaba sin pasos
            // y el turno moria con un error, dejando a la persona sin ninguna respuesta.
            boolean lastStep = step == MAX_TOOL_STEPS - 1;
            // El tiempo del MCP ya se mide por llamada; el del modelo no se medía, y es
            // el que hay que ver para saber si un turno lento son muchos pasos o pasos
            // caros. Sin este dato la lentitud solo se puede adivinar.
            long antesDelModelo = System.nanoTime();
            events.emit(run, "run.progress", payload("phase", "thinking").put("message", "Preparando tu respuesta"));
            AssistantLlmClient.Turn turn = llm.complete(history, lastStep ? null : modelTools);
            AssistantExecutionBudget.check();
            log.info("Asistente turno {} paso {}: modelo {} en {} ms, entrada {} tokens, salida {} tokens",
                    run.getId(), step, turn.model(),
                    (System.nanoTime() - antesDelModelo) / 1_000_000, turn.inputTokens(), turn.outputTokens());
            run.setModel(turn.model());
            runs.save(run);
            if (turn.toolCalls().isEmpty()) {
                // La ultima red de seguridad del lenguaje: lo que se le escape al modelo no llega a la persona.
                String answer = sinGroserias(run, turn.content().isBlank()
                        ? "Termine la revision, pero no pude redactar una respuesta util. Intenta formularlo de otra forma."
                        : turn.content());
                messages.save(AssistantMessage.builder().threadId(thread.getId()).runId(run.getId())
                        .role("ASSISTANT").content(answer).model(turn.model())
                        .artifactsJson(conVistas(run.getId(), resultArtifacts(run.getId(), tools)))
                        .inputTokens(turn.inputTokens()).outputTokens(turn.outputTokens())
                        .createdAt(LocalDateTime.now()).build());
                events.emit(run, "message.delta", payload("content", answer));
                run.setStatus(AssistantRunStatus.COMPLETED);
                readCaches.remove(run.getId());
                run.setFinishedAt(LocalDateTime.now());
                runs.save(run);
                thread.setUpdatedAt(LocalDateTime.now());
                threads.save(thread);
                events.emit(run, "run.completed", payload("message", answer));
                return new Result(threadService.detail(user, thread.getId()), run.getId(), null);
            }

            history.add(turn.assistantMessage());
            for (AssistantLlmClient.ToolCall requested : turn.toolCalls()) {
                if (CHOICES_TOOL.equals(requested.name())) {
                    return askWithChoices(user, thread, run, turn, requested.arguments());
                }
                if (FORM_TOOL.equals(requested.name())) {
                    return askWithForm(user, thread, run, turn, requested.arguments());
                }
                if (BLOCKS_TOOL.equals(requested.name())) {
                    return answerWithBlocks(user, thread, run, turn, requested.arguments());
                }
                if (CONNECT_TOOL.equals(requested.name())) {
                    return askToConnect(user, thread, run, turn, requested.arguments());
                }
                JsonNode definition = findTool(tools, requested.name());
                Policy policy = policy(definition);
                // Si responde a un formulario y es la edicion de la marca, se guarda SOLO lo que la
                // persona confirmo, con sus palabras: ver soloLoConfirmado.
                JsonNode confirmado = soloLoConfirmado(definition, requested.name(), requested.arguments(),
                        valoresConfirmados);
                JsonNode argumentos = confirmado != null ? confirmado : requested.arguments();
                if ("generar_contenido".equals(requested.name())) {
                    ObjectNode literal = argumentos.deepCopy();
                    literal.remove("_picaleQuotedCredits");
                    if (valoresConfirmados != null && valoresConfirmados.hasNonNull("brief"))
                        literal.put("brief", valoresConfirmados.path("brief").asText());
                    List<AssistantMessage> actual = messages.findByThreadIdOrderByCreatedAtAsc(thread.getId());
                    AssistantMessage latestUser = actual.stream().filter(m -> "USER".equals(m.getRole())).reduce((a, b) -> b).orElse(null);
                    String supplied = latestUser == null ? "" : latestUser.getContent();
                    String marker = "\nBrief de la pieza (literal, confirmado por mí):\n";
                    if (supplied.startsWith("Modo de imagen: ") && supplied.contains(marker)) {
                        literal.put("brief", supplied.substring(supplied.indexOf(marker) + marker.length()));
                        literal.put("modo", supplied.startsWith("Modo de imagen: RETOQUE.") ? "RETOQUE" : "ANUNCIO");
                    }
                    String rawBrief = literal.path("brief").asText("");
                    AssistantMessage imageSource = actual.stream().filter(m -> "USER".equals(m.getRole())
                            && parse(m.getAttachmentsJson()).findValuesAsText("type").contains("IMAGE")).reduce((a, b) -> b).orElse(null);
                    boolean adjustedAfterImage = false;
                    if (imageSource != null) for (AssistantMessage candidate : actual.subList(actual.indexOf(imageSource) + 1, actual.size())) {
                        if (!"USER".equals(candidate.getRole())) continue;
                        if (candidate.getContent().startsWith("Modo de imagen: ")) adjustedAfterImage = true;
                        for (JsonNode artifact : parse(candidate.getArtifactsJson()))
                            if ("form_answer".equals(artifact.path("type").asText()) && artifact.path("values").hasNonNull("brief")) adjustedAfterImage = true;
                    }
                    boolean longAttachment = imageSource != null && imageSource.getContent().length() > 1500
                            && !imageSource.getContent().startsWith("Modo de imagen: ") && !adjustedAfterImage;
                    if (rawBrief.length() > 1500 || longAttachment) {
                        ObjectNode form = json.createObjectNode().put("respuesta", "El prompt admite máximo 1500 caracteres. Ajústalo conservando lo importante; no lo recortaré ni generaré todavía.")
                                .put("boton", "Revisar mi prompt");
                        form.putArray("campos").addObject().put("nombre", "brief").put("etiqueta", "Instrucciones de la imagen")
                                .put("tipo", "texto").put("valor", longAttachment ? imageSource.getContent() : rawBrief);
                        return askWithForm(user, thread, run, turn, form);
                    }
                    argumentos = literal;
                }
                AssistantToolCall call = calls.save(AssistantToolCall.builder().runId(run.getId())
                        .toolName(requested.name()).riskLevel(policy.riskLevel()).permission(policy.permission())
                        .argumentsJson(sanitize(argumentos).toString())
                        .status(AssistantToolCallStatus.PROPOSED).createdAt(LocalDateTime.now()).build());
                requirePermission(user, policy.permission());
                if ("generar_contenido".equals(requested.name())) {
                    String signature = AssistantReadCache.key(requested.name(), argumentos);
                    boolean repeatedFailure = calls.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                            .anyMatch(previous -> previous.getStatus() == AssistantToolCallStatus.FAILED
                                    && requested.name().equals(previous.getToolName())
                                    && signature.equals(AssistantReadCache.key(previous.getToolName(), executableArguments(parse(previous.getArgumentsJson())))));
                    if (repeatedFailure) {
                        call.setStatus(AssistantToolCallStatus.CANCELLED);
                        calls.save(call);
                        fail(run, "El mismo pedido de imagen ya falló en este turno. No lo repetí ni pedí otra confirmación. "
                                + "Hay que corregir los datos del pedido antes de continuar; revisa el resultado anterior.");
                        return new Result(threadService.detail(user, thread.getId()), run.getId(), null);
                    }
                    events.emit(run, "run.progress", payload("phase", "validating_image")
                            .put("message", "Revisando los datos de tu imagen"));
                    long validationStarted = System.nanoTime();
                    JsonNode validation = session.call("validar_generacion", argumentos);
                    if (validation == null || (!validation.path("isError").asBoolean(false)
                            && !validation.at("/structuredContent/valido").asBoolean(false))) {
                        throw new IllegalStateException("El MCP no devolvió una validación de generación verificable.");
                    }
                    log.info("Asistente turno {} validacion de imagen en {} ms, valida={}", run.getId(),
                            (System.nanoTime() - validationStarted) / 1_000_000,
                            !validation.path("isError").asBoolean(false));
                    if (validation.path("isError").asBoolean(false)) {
                        completeCall(run, call, validation, validationStarted);
                        history.add(toolResult(requested.id(), validation));
                        continue;
                    }
                    int quotedCost = validation.at("/structuredContent/creditos").asInt(-1);
                    if (quotedCost < 0) throw new IllegalStateException("Falta el coste verificado de generación.");
                    ObjectNode storedArguments = executableArguments(argumentos);
                    storedArguments.put("_picaleQuotedCredits", quotedCost);
                    call.setArgumentsJson(storedArguments.toString());
                }
                if (policy.requiresConfirmation()
                        && confirmado == null
                        && ("generar_contenido".equals(requested.name())
                            || !confirmadoEnFormulario(policy, requested.arguments(), respuestaDeFormulario))) {
                    String rawToken = token();
                    call.setStatus(AssistantToolCallStatus.WAITING_CONFIRMATION);
                    call.setConfirmationTokenHash(hash(rawToken));
                    call.setConfirmationExpiresAt(LocalDateTime.now().plus(15, ChronoUnit.MINUTES));
                    calls.save(call);
                    run.setStatus(AssistantRunStatus.WAITING_CONFIRMATION);
                    runs.save(run);
                    messages.save(confirmationMessage(thread.getId(), run.getId(), call, definition, rawToken,
                            policy.confirmation(), turn.content()));
                    events.emit(run, "confirmation.required", payload("toolCallId", call.getId().toString()));
                    return new Result(threadService.detail(user, thread.getId()), run.getId(), rawToken);
                }

                call.setStatus(AssistantToolCallStatus.RUNNING);
                calls.save(call);
                // Con el nombre, el chat puede decir «Revisando tus comentarios» en vez de
                // «Consultando informacion real» doce veces seguidas. En un turno de varios
                // pasos ese aviso es lo unico que la persona ve mientras espera.
                events.emit(run, "tool.started", payload("toolCallId", call.getId().toString())
                        .put("tool", requested.name()));
                long started = System.nanoTime();
                String readKey = AssistantReadCache.key(requested.name(), argumentos);
                boolean eligible = AssistantReadCache.eligible(requested.name(), policy.riskLevel());
                JsonNode result = eligible ? reads.get(readKey) : null;
                boolean cached = result != null;
                if (policy.riskLevel() > 0) readsInHistory.removeAll(reads.invalidateFor(requested.name()));
                if (!cached) result = session.call(requested.name(), argumentos);
                if (eligible && !cached) reads.put(readKey, result);
                completeCall(run, call, result, started);
                log.info("Asistente turno {} herramienta {} en {} ms, reutilizada={}", run.getId(),
                        requested.name(), (System.nanoTime() - started) / 1_000_000, cached);
                if (cached && readsInHistory.contains(readKey)) {
                    ObjectNode reused = json.createObjectNode();
                    reused.putArray("content").addObject().put("type", "text").put("text",
                            "Lectura idéntica ya disponible en este turno. Usa el resultado anterior de " + requested.name()
                            + "; no volvió a ejecutarse ni se adjuntó otra vez la imagen.");
                    history.add(toolResult(requested.id(), reused));
                } else {
                    history.add(toolResult(requested.id(), result));
                    if (eligible) readsInHistory.add(readKey);
                }
            }
        }
        throw new IllegalStateException("Se alcanzo el limite seguro de pasos del asistente.");
    }

    /**
     * Suma al catalogo del MCP una herramienta que no ejecuta nada: solo deja la pregunta
     * con respuestas listas para tocar. Existe para que el modelo tenga una alternativa
     * mejor que soltar una pregunta abierta y dejar a la persona escribiendo desde cero.
     */
    private JsonNode withOwnTools(JsonNode tools) {
        ArrayNode result = json.createArrayNode();
        if (tools != null && tools.isArray()) tools.forEach(tool -> {
            // Preflight interno ejecutado por el servidor; no duplicar su esquema en cada llamada al modelo.
            if (!"validar_generacion".equals(tool.path("name").asText())) result.add(tool);
        });

        ObjectNode tool = result.addObject();
        tool.put("name", CHOICES_TOOL);
        tool.put("description", "Haz una pregunta dejando entre 2 y " + MAX_CHOICES + " respuestas listas"
                + " para tocar. Usala SIEMPRE en vez de preguntar a campo abierto: la persona elige en vez"
                + " de redactar. Cada opcion es una RESPUESTA posible a la pregunta, ya redactada como la diria"
                + " la persona (por ejemplo, para cuando publicar: 'Hoy a las 7 pm', 'Manana temprano')."
                + " NUNCA pongas como opciones una lista de campos, temas o pasos siguientes: eso le devuelve"
                + " el trabajo a la persona. La primera opcion es la mas probable segun lo que ya sabes, SALVO"
                + " cuando una respuesta tiene consecuencias dificiles de deshacer (publicar en una cuenta que"
                + " quiza no es la suya, borrar, gastar creditos): ahi la primera es la segura (revisar,"
                + " cambiarla, no hacerlo), porque un toque distraido no debe causar el dano."
                + " NO la uses para el giro del negocio: ese va con " + FORM_TOOL + " y tipo 'giro', que"
                + " ensena el selector con todos los giros.");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("respuesta").put("type", "string")
                .put("description", "Lo que la persona tiene que LEER antes de elegir: la propuesta completa,"
                        + " el resumen o lo que encontraste, con sus datos. Va arriba de los botones y es lo"
                        + " unico que va a ver. Nunca te refieras a algo (\"esta propuesta\") sin escribirlo"
                        + " aqui. Si de verdad no hay nada que mostrar, deja la cadena vacia.");
        props.putObject("pregunta").put("type", "string")
                .put("description", "La pregunta, en una linea y en palabras de la persona.");
        ObjectNode opciones = props.putObject("opciones");
        opciones.put("type", "array");
        opciones.put("description", "Entre 2 y " + MAX_CHOICES + " respuestas posibles.");
        ObjectNode item = opciones.putObject("items");
        item.put("type", "object");
        item.putObject("properties").putObject("etiqueta").put("type", "string")
                .put("description", "Lo que se lee en el boton; breve.");
        item.putArray("required").add("etiqueta");
        ArrayNode required = schema.putArray("required");
        required.add("respuesta");
        required.add("pregunta");
        required.add("opciones");
        tool.putObject("_meta").putObject("picale/policy")
                .put("riskLevel", 0).put("permission", "MEMBERSHIP")
                .put("confirmation", "none").put("idempotencyRequired", false);

        result.add(formTool());
        result.add(blocksTool());
        result.add(connectTool());
        return result;
    }

    /**
     * Describe una respuesta como piezas visibles y acotadas. Es una herramienta de
     * presentación: no consulta ni cambia datos y por eso no genera un recibo MCP.
     */
    private ObjectNode blocksTool() {
        ObjectNode tool = json.createObjectNode();
        tool.put("name", BLOCKS_TOOL);
        tool.put("description", "Entrega una respuesta profesional en hasta " + MAX_BLOCKS + " bloques"
                + " ordenados cuando necesites intercalar explicacion, un archivo visible y una propuesta."
                + " No inventa capacidades ni ejecuta acciones. Usa 'media' solo con el ID de un archivo que"
                + " ya recibiste o consultaste; el servidor lo valida y la persona nunca vera ese ID."
                + " Termina con opciones concretas cuando la persona tenga que decidir.");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode blocks = schema.putObject("properties").putObject("bloques");
        blocks.put("type", "array").put("maxItems", MAX_BLOCKS);
        ObjectNode item = blocks.putObject("items");
        item.put("type", "object");
        ObjectNode props = item.putObject("properties");
        props.putObject("tipo").put("type", "string").putArray("enum")
                .add("texto").add("media").add("propuesta").add("opciones");
        props.putObject("markdown").put("type", "string")
                .put("description", "Texto visible. Obligatorio solo para tipo texto.");
        props.putObject("archivo").put("type", "string")
                .put("description", "ID interno del archivo. Obligatorio solo para media; nunca lo escribas en texto.");
        props.putObject("pie").put("type", "string")
                .put("description", "Pie visible del archivo, maximo " + MAX_CAPTION + " caracteres.");
        props.putObject("titulo").put("type", "string")
                .put("description", "Titulo visible de la propuesta.");
        ObjectNode fields = props.putObject("campos");
        fields.put("type", "array").put("maxItems", MAX_PROPOSAL_FIELDS);
        ObjectNode field = fields.putObject("items");
        field.put("type", "object");
        ObjectNode fieldProps = field.putObject("properties");
        fieldProps.putObject("etiqueta").put("type", "string");
        fieldProps.putObject("valor").put("type", "string");
        field.putArray("required").add("etiqueta").add("valor");
        props.putObject("pregunta").put("type", "string")
                .put("description", "Pregunta visible para tipo opciones.");
        ObjectNode options = props.putObject("opciones");
        options.put("type", "array").put("maxItems", MAX_CHOICES);
        options.putObject("items").put("type", "string");
        item.putArray("required").add("tipo");
        schema.putArray("required").add("bloques");
        tool.putObject("_meta").putObject("picale/policy")
                .put("riskLevel", 0).put("permission", "MEMBERSHIP")
                .put("confirmation", "none").put("idempotencyRequired", false);
        return tool;
    }

    /**
     * Convierte la descripción del modelo en el contrato que recibe la web.
     *
     * <p>El modelo nunca decide URLs: solo propone un identificador y este método lo
     * resuelve contra el repositorio y el workspace del turno. Un archivo inválido se
     * omite sin tirar la respuesta completa. {@code content} se reconstruye en paralelo
     * para historial, copiar y clientes anteriores a los bloques.
     */
    private Result answerWithBlocks(User user, AssistantThread thread, AssistantRun run,
            AssistantLlmClient.Turn turn, JsonNode arguments) {
        ArrayNode artifacts = json.createArrayNode();
        StringBuilder fallback = new StringBuilder();

        for (JsonNode source : arguments.path("bloques")) {
            if (artifacts.size() >= MAX_BLOCKS) break;
            String type = source.path("tipo").asText("").strip().toLowerCase(Locale.ROOT);
            switch (type) {
                case "texto" -> addTextBlock(source, artifacts, fallback);
                case "media" -> addMediaBlock(user, source, artifacts, fallback);
                case "propuesta" -> addProposalBlock(source, artifacts, fallback);
                case "opciones" -> addChoicesBlock(source, artifacts, fallback);
                default -> {
                    // Un tipo nuevo no rompe el turno ni se interpreta por aproximación.
                }
            }
        }

        String answer = fallback.toString().strip();
        if (answer.isEmpty()) answer = turn.content() == null ? "" : turn.content().strip();
        if (answer.isEmpty()) answer = "No pude preparar una respuesta segura con la información disponible.";
        return finish(user, thread, run, turn, answer, artifacts.isEmpty() ? null : artifacts.toString());
    }

    private void addTextBlock(JsonNode source, ArrayNode artifacts, StringBuilder fallback) {
        String markdown = truncate(source.path("markdown").asText("").strip(), 4000);
        if (markdown.isEmpty()) return;
        artifacts.addObject().put("type", "texto").put("markdown", markdown);
        appendFallback(fallback, markdown);
    }

    private void addMediaBlock(User user, JsonNode source, ArrayNode artifacts, StringBuilder fallback) {
        UUID assetId;
        try {
            assetId = UUID.fromString(source.path("archivo").asText("").strip());
        } catch (IllegalArgumentException invalid) {
            return;
        }
        MediaAsset asset = media.findById(assetId)
                .filter(item -> item.getStatus() == MediaAssetStatus.READY)
                .filter(item -> user.getWorkspace() != null && user.getWorkspace().getId() != null
                        && user.getWorkspace().getId().toString().equals(item.getTenantId()))
                .orElse(null);
        if (asset == null || asset.getUrl() == null || asset.getUrl().isBlank()) return;

        String caption = truncate(source.path("pie").asText("").strip(), MAX_CAPTION);
        ObjectNode block = artifacts.addObject();
        block.put("type", "media");
        block.put("id", asset.getId().toString());
        block.put("url", asset.getUrl());
        block.put("thumbnailUrl", asset.getThumbnailUrl() == null ? "" : asset.getThumbnailUrl());
        block.put("name", asset.getFileName() == null ? "Contenido adjunto" : truncate(asset.getFileName(), 160));
        block.put("format", asset.getType() == null ? "Archivo"
                : asset.getType().name().equals("VIDEO") ? "Video" : "Foto");
        if (!caption.isEmpty()) block.put("caption", caption);
        appendFallback(fallback, caption.isEmpty() ? "Contenido visual adjunto." : caption);
    }

    private void addProposalBlock(JsonNode source, ArrayNode artifacts, StringBuilder fallback) {
        String title = truncate(source.path("titulo").asText("").strip(), 120);
        ArrayNode fields = json.createArrayNode();
        for (JsonNode sourceField : source.path("campos")) {
            if (fields.size() >= MAX_PROPOSAL_FIELDS) break;
            String label = truncate(sourceField.path("etiqueta").asText("").strip(), 80);
            String value = truncate(sourceField.path("valor").asText("").strip(), 1000);
            if (label.isEmpty() || value.isEmpty()) continue;
            fields.addObject().put("label", label).put("value", value);
        }
        if (title.isEmpty() && fields.isEmpty()) return;

        ObjectNode block = artifacts.addObject();
        block.put("type", "propuesta");
        block.put("title", title.isEmpty() ? "Propuesta" : title);
        block.set("fields", fields);
        appendFallback(fallback, title.isEmpty() ? "Propuesta:" : title);
        for (JsonNode field : fields) {
            fallback.append("\n- ").append(field.path("label").asText())
                    .append(": ").append(field.path("value").asText());
        }
    }

    private void addChoicesBlock(JsonNode source, ArrayNode artifacts, StringBuilder fallback) {
        String question = truncate(source.path("pregunta").asText("").strip(), 240);
        ArrayNode options = json.createArrayNode();
        for (JsonNode option : source.path("opciones")) {
            String label = truncate(option.asText("").strip(), 80);
            if (!label.isEmpty() && options.size() < MAX_CHOICES) options.addObject().put("label", label);
        }
        if (question.isEmpty() || options.size() < 2) return;
        ObjectNode block = artifacts.addObject();
        block.put("type", "choices");
        block.put("question", question);
        block.set("options", options);
        appendFallback(fallback, question);
    }

    private void appendFallback(StringBuilder fallback, String text) {
        if (text == null || text.isBlank()) return;
        if (!fallback.isEmpty()) fallback.append("\n\n");
        fallback.append(text.strip());
    }

    /**
     * Pide varios datos a la vez, cada uno con el control que le toca.
     *
     * <p>Unos botones sirven para elegir entre pocas salidas, pero no para doce giros
     * ni para una descripcion de dos frases. Con esto el chat puede enseñar un
     * desplegable con buscador, varias casillas o una caja de texto, ya rellenados con
     * la propuesta: la persona corrige lo que no le cuadre en vez de escribirlo todo.
     */
    private ObjectNode formTool() {
        ObjectNode tool = json.createObjectNode();
        tool.put("name", FORM_TOOL);
        tool.put("description", "Pide hasta " + MAX_FIELDS + " datos a la vez con el control adecuado:"
                + " un desplegable con buscador cuando hay una lista (el giro, el objetivo), casillas cuando"
                + " se eligen varios (el tono) y una caja de texto cuando es redaccion libre (la descripcion)."
                + " Usala en vez de " + CHOICES_TOOL + " cuando necesites mas de un dato o cuando las opciones"
                + " no quepan en unos botones. Rellena siempre 'valor' con tu propuesta: la persona corrige, no"
                + " escribe desde cero. Las opciones salen del esquema de la herramienta que luego vas a usar,"
                + " pero ESCRITAS COMO LAS LEE LA PERSONA: 'Conseguir mas clientes', no 'MAS_CLIENTES';"
                + " 'Profesional y serio', no 'PROFESIONAL'. El codigo interno lo traduces tu al guardar.");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("respuesta").put("type", "string")
                .put("description", "Lo que se lee arriba del formulario: que propones y que asumiste.");
        ObjectNode campos = props.putObject("campos");
        campos.put("type", "array");
        campos.put("description", "De 1 a " + MAX_FIELDS + " datos.");
        ObjectNode item = campos.putObject("items");
        item.put("type", "object");
        ObjectNode campo = item.putObject("properties");
        campo.putObject("nombre").put("type", "string")
                .put("description", "La clave del campo en la herramienta que lo guarda, por ejemplo 'giro'.");
        campo.putObject("etiqueta").put("type", "string")
                .put("description", "Como se le llama a la persona, por ejemplo 'Giro del negocio'.");
        campo.putObject("tipo").put("type", "string")
                .put("description", "'opcion' para elegir uno de una lista, 'opciones' para elegir varios,"
                        + " 'texto' para escribir libre, y 'giro' para el giro del negocio: pinta el selector"
                        + " del registro con TODOS los giros y buscador, asi que no mandes 'opciones'.");
        campo.putObject("opciones").put("type", "array").putObject("items").put("type", "string");
        campo.putObject("valor").put("type", "string")
                .put("description", "Tu propuesta ya puesta. En 'opciones', separadas por coma.");
        campo.putObject("ayuda").put("type", "string")
                .put("description", "Una linea opcional que explique el campo o por que lo propones asi.");
        item.putArray("required").add("nombre").add("etiqueta").add("tipo");
        props.putObject("boton").put("type", "string")
                .put("description", "Lo que dice el boton, por ejemplo 'Guardar mi marca'.");
        schema.putArray("required").add("respuesta").add("campos");
        tool.putObject("_meta").putObject("picale/policy")
                .put("riskLevel", 0).put("permission", "MEMBERSHIP")
                .put("confirmation", "none").put("idempotencyRequired", false);
        return tool;
    }

    /** Cierra el turno enseñando el formulario; no toca el MCP ni la cuenta. */
    private Result askWithForm(User user, AssistantThread thread, AssistantRun run,
            AssistantLlmClient.Turn turn, JsonNode arguments) {
        ArrayNode fields = json.createArrayNode();
        for (JsonNode source : arguments.path("campos")) {
            String name = source.path("nombre").asText("").strip();
            String label = source.path("etiqueta").asText("").strip();
            if (name.isEmpty() || label.isEmpty() || fields.size() >= MAX_FIELDS) continue;
            String type = switch (source.path("tipo").asText("texto").strip().toLowerCase(Locale.ROOT)) {
                case "opcion", "opciones", "giro" -> source.path("tipo").asText().strip().toLowerCase(Locale.ROOT);
                default -> "texto";
            };
            // El giro va SIEMPRE con el selector del registro, lo haya pedido asi el modelo o no:
            // ahi estan todos los giros agrupados por ramo y con buscador. Tres botones inventados
            // —lo que pasaba— dejan fuera el que de verdad es, y la persona no tiene donde buscarlo.
            if ("giro".equals(name.toLowerCase(Locale.ROOT))) type = "giro";
            ObjectNode field = fields.addObject();
            field.put("name", name);
            field.put("label", truncate(label, 80));
            field.put("type", type);
            field.put("maxLength", "brief".equals(name) ? 1500 : 1000);
            field.put("value", truncate(source.path("valor").asText(""), "brief".equals(name) ? 12000 : 1000));
            String help = source.path("ayuda").asText("").strip();
            if (!help.isEmpty()) field.put("help", truncate(help, 200));
            ArrayNode options = field.putArray("options");
            // El catalogo de giros vive en la web, en un solo sitio; el modelo no lo copia.
            if (!"giro".equals(type)) for (JsonNode option : source.path("opciones")) {
                String value = option.asText("").strip();
                if (value.isEmpty() || options.size() >= MAX_FIELD_OPTIONS) continue;
                options.add(truncate(value, 120));
            }
            // Sin lista no hay nada que desplegar: se queda como texto libre.
            if (!"texto".equals(type) && !"giro".equals(type) && options.isEmpty()) field.put("type", "texto");
        }
        String said = arguments.path("respuesta").asText("").strip();
        if (fields.isEmpty()) {
            return finish(user, thread, run, turn,
                    said.isEmpty() ? "Cuentame un poco mas y seguimos." : said, null);
        }
        ObjectNode card = json.createObjectNode();
        card.put("type", "form");
        card.put("submit", truncate(arguments.path("boton").asText("Guardar").strip(), 40));
        card.set("fields", fields);
        return finish(user, thread, run, turn, said.isEmpty() ? "Revisa y corrige lo que no cuadre." : said,
                json.createArrayNode().add(card).toString());
    }

    private ObjectNode connectTool() {
        ObjectNode tool = json.createObjectNode();
        tool.put("name", CONNECT_TOOL);
        tool.put("description", "Pon en el chat el boton para conectar o reconectar redes sociales"
                + " (Facebook, Instagram, TikTok, LinkedIn, YouTube). Es el mismo modulo de Redes de Picale:"
                + " la persona toca la red, se abre la pagina de esa red para dar el permiso, y al volver"
                + " la conexion aparece sola. Usala SIEMPRE que la persona quiera conectar una red, que una"
                + " este por reconectar o que publicar falle por una red sin conectar. NUNCA digas que no"
                + " puedes conectar, ni le preguntes en que pantalla esta: esto lo resuelve desde aqui.");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("respuesta").put("type", "string")
                .put("description", "Una o dos frases sobre que red conectar y por que. Sin pasos tecnicos.");
        ObjectNode redes = props.putObject("redes");
        redes.put("type", "array")
                .put("description", "Las redes que hay que conectar, para destacarlas. Vacio = todas.");
        redes.putObject("items").put("type", "string").putArray("enum")
                .add("facebook").add("instagram").add("tiktok").add("linkedin").add("youtube");
        schema.putArray("required").add("respuesta");
        tool.putObject("_meta").putObject("picale/policy")
                .put("riskLevel", 0).put("permission", "MEMBERSHIP")
                .put("confirmation", "none").put("idempotencyRequired", false);
        return tool;
    }

    /**
     * Cierra el turno con el módulo de Redes dentro del chat.
     *
     * <p>Conectar una red es de quien administra el espacio (NETWORK_MANAGE). A quien no
     * puede, enseñarle un botón que va a fallar es peor que decirle a quién pedírselo.
     */
    private Result askToConnect(User user, AssistantThread thread, AssistantRun run,
            AssistantLlmClient.Turn turn, JsonNode arguments) {
        if (!permissions.permisosDe(user).contains(Permission.NETWORK_MANAGE)) {
            return finish(user, thread, run, turn, "Conectar redes lo hace quien administra este espacio."
                    + " Pídele que entre a Redes, o que te dé ese permiso, y en cuanto esté conectada"
                    + " seguimos desde aquí.", null);
        }
        ArrayNode destacadas = json.createArrayNode();
        for (JsonNode red : arguments.path("redes")) {
            String clave = red.asText("").strip().toLowerCase(Locale.ROOT);
            if (REDES_CONECTABLES.contains(clave) && destacadas.size() < REDES_CONECTABLES.size()) {
                boolean repetida = false;
                for (JsonNode ya : destacadas) repetida |= ya.asText().equals(clave);
                if (!repetida) destacadas.add(clave);
            }
        }
        ObjectNode card = json.createObjectNode();
        card.put("type", "social_connect");
        card.set("networks", destacadas);
        String said = arguments.path("respuesta").asText("").strip();
        if (said.isEmpty()) said = turn.content() == null ? "" : turn.content().strip();
        if (said.isEmpty()) {
            said = "Toca la red que quieres conectar. Se abre su página para que des el permiso;"
                    + " al volver aquí, la conexión aparece sola.";
        }
        return finish(user, thread, run, turn, truncate(said, 600), json.createArrayNode().add(card).toString());
    }

    /** Cierra el turno con la pregunta y sus opciones; no toca el MCP ni la cuenta. */
    private Result askWithChoices(User user, AssistantThread thread, AssistantRun run,
            AssistantLlmClient.Turn turn, JsonNode arguments) {
        String question = arguments.path("pregunta").asText("").strip();
        ArrayNode options = json.createArrayNode();
        for (JsonNode option : arguments.path("opciones")) {
            String label = option.path("etiqueta").asText("").strip();
            if (label.isEmpty() || options.size() >= MAX_CHOICES) continue;
            options.addObject().put("label", truncate(label, 80));
        }
        // Sin pregunta o con una sola salida no hay nada que elegir: mejor texto normal.
        if (question.isEmpty() || options.size() < 2) {
            String fallback = question.isEmpty() ? turn.content() : question;
            return finish(user, thread, run, turn,
                    fallback.isBlank() ? "Cuentame un poco mas y seguimos." : fallback, null);
        }
        ObjectNode card = json.createObjectNode();
        card.put("type", "choices");
        card.set("options", options);
        // Lo que se lee arriba de los botones. El modelo tiende a preguntar por "esta
        // propuesta" sin haberla escrito nunca, asi que la herramienta la pide como campo
        // propio; si aun asi no viene, se usa lo que haya escrito al lado.
        String said = arguments.path("respuesta").asText("").strip();
        if (said.isEmpty()) said = turn.content() == null ? "" : turn.content().strip();
        String content = said.isEmpty() || said.endsWith(question) ? question : said + "\n\n" + question;
        return finish(user, thread, run, turn, content, json.createArrayNode().add(card).toString());
    }

    /**
     * Tapa las groserías que se le hayan escapado al modelo. Las reglas del prompt casi
     * siempre bastan; esto es lo que garantiza que no lleguen nunca a la pantalla.
     */
    private String sinGroserias(AssistantRun run, String texto) {
        if (!com.metricol.api.service.ai.LenguajeCuidado.tieneGroserias(texto)) return texto;
        log.warn("Respuesta del asistente con lenguaje vulgar, se tapo. run={} palabras={}", run.getId(),
                com.metricol.api.service.ai.LenguajeCuidado.groserias(texto).size());
        return com.metricol.api.service.ai.LenguajeCuidado.cubrir(texto);
    }

    /** Guarda la respuesta del asistente, cierra el turno y devuelve el detalle. */
    private Result finish(User user, AssistantThread thread, AssistantRun run,
            AssistantLlmClient.Turn turn, String answer, String artifacts) {
        answer = sinGroserias(run, answer);
        readCaches.remove(run.getId());
        messages.save(AssistantMessage.builder().threadId(thread.getId()).runId(run.getId())
                .role("ASSISTANT").content(answer).model(turn.model())
                .artifactsJson(conMedios(answer, run.getId(), conVistas(run.getId(), artifacts)))
                .inputTokens(turn.inputTokens()).outputTokens(turn.outputTokens())
                .createdAt(LocalDateTime.now()).build());
        events.emit(run, "message.delta", payload("content", answer));
        run.setStatus(AssistantRunStatus.COMPLETED);
        run.setFinishedAt(LocalDateTime.now());
        runs.save(run);
        thread.setUpdatedAt(LocalDateTime.now());
        threads.save(thread);
        events.emit(run, "run.completed", payload("message", answer));
        return new Result(threadService.detail(user, thread.getId()), run.getId(), null);
    }

    /**
     * Añade al mensaje los archivos de los que habla, para que se vean.
     *
     * <p>El modelo solo conoce un archivo por su identificador, asi que lo escribe:
     * «la imagen cad63c21-...». La persona tendria que estar viendo la foto. Aqui se
     * busca cualquier identificador —en lo que escribio y en lo que pidio durante el
     * turno— y se resuelve contra el repositorio, que es quien sabe donde vive.
     *
     * <p>Lo que no se encuentre se ignora: un id inventado no rompe el mensaje, y uno
     * de otra cuenta no aparece porque el filtro por inquilino no lo alcanza.
     */
    private String conMedios(String answer, UUID runId, String artifacts) {
        ArrayNode fuentes = json.createArrayNode();
        fuentes.add(answer == null ? "" : answer);
        for (AssistantToolCall call : calls.findByRunIdOrderByCreatedAtAsc(runId)) {
            if (call.getArgumentsJson() != null) fuentes.add(call.getArgumentsJson());
            if (call.getResultSummary() != null) fuentes.add(call.getResultSummary());
        }
        ArrayNode medios = resolveMedia(fuentes);
        if (medios.isEmpty()) return artifacts;

        ArrayNode tarjetas = artifacts == null || artifacts.isBlank()
                ? json.createArrayNode()
                : (ArrayNode) parse(artifacts);
        ObjectNode tarjeta = json.createObjectNode();
        tarjeta.put("type", "media");
        tarjeta.set("items", medios);
        // Delante de las demas: primero se ve de que hablamos y luego que hacer con ello.
        ArrayNode ordenadas = json.createArrayNode();
        ordenadas.add(tarjeta);
        tarjetas.forEach(ordenadas::add);
        return ordenadas.toString();
    }

    private ArrayNode history(UUID threadId, String currentPath) {
        return history(threadId, currentPath, "");
    }

    /**
     * Las reglas de publicacion y los flujos expertos los mantiene el catalogo MCP. Traerlos
     * aqui evita que la web y el modelo inventen su propia version de las mismas reglas.
     */
    private String briefing(AssistantMcpClient.Session session, AssistantRun run) {
        StringBuilder extra = new StringBuilder();
        try {
            String reglas = session.resource(REGLAS_URI);
            if (reglas != null && !reglas.isBlank()) {
                extra.append("\n\nReglas de publicacion vigentes (no inventes otras):\n").append(reglas);
            }
        } catch (RuntimeException e) {
            log.warn("No se pudieron leer las reglas del catalogo MCP para el turno {}", run.getId(), e);
        }
        if (run.getPromptName() != null && !run.getPromptName().isBlank()) {
            try {
                String flujo = session.prompt(run.getPromptName(), null);
                if (flujo != null && !flujo.isBlank()) {
                    extra.append("\n\nLa persona pidio este trabajo desde un atajo. Sigue estas instrucciones")
                            .append(" sin recitarlas ni mencionar nombres internos:\n").append(flujo);
                }
            } catch (RuntimeException e) {
                log.warn("No se pudo expandir el flujo '{}' del turno {}", run.getPromptName(), run.getId(), e);
            }
        }
        return extra.toString();
    }

    private ArrayNode history(UUID threadId, String currentPath, String briefing) {
        ArrayNode result = json.createArrayNode();
        String memory = threads.findById(threadId).map(AssistantThread::getSummary).orElse(null);
        String memoryContext = memory == null || memory.isBlank() ? "" : """

                Memoria operativa verificada del negocio (es historica, puede haber cambiado):
                %s
                Usala para dar continuidad y evitar repetir trabajo, pero vuelve a consultar el MCP antes de
                afirmar un estado actual o ejecutar una decision. Un intento fallido es una restriccion conocida,
                no una prueba de que siempre vaya a fallar.
                """.formatted(memory);
        result.addObject().put("role", "system").put("content", SYSTEM_PROMPT + memoryContext
                + ((currentPath == null || currentPath.isBlank()) ? "" : "\nLa persona esta en: " + currentPath)
                + (briefing == null ? "" : briefing));
        List<AssistantMessage> all = AssistantThreads.visibles(threads.findById(threadId).orElse(null),
                messages.findByThreadIdOrderByCreatedAtAsc(threadId));
        int start = Math.max(0, all.size() - 20);
        for (AssistantMessage message : all.subList(start, all.size())) {
            if ("SYSTEM".equals(message.getRole())) continue;
            String content = message.getContent();
            if (message.getAttachmentsJson() != null && !message.getAttachmentsJson().isBlank()) {
                content += "\nArchivos adjuntos (usa estos IDs): " + forModel(message.getAttachmentsJson());
            }
            if (message.getArtifactsJson() != null) for (JsonNode artifact : parse(message.getArtifactsJson())) {
                if (!"generation".equals(artifact.path("type").asText())) continue;
                content += "\nTrabajo visual verificado (estado historico): " + artifact.path("jobId").asText()
                        + ", " + artifact.path("status").asText();
                for (JsonNode version : artifact.path("versions")) {
                    content += "\nVersion " + version.path("ratio").asText() + ": " + forModel(version.path("items").toString());
                }
            }
            result.addObject().put("role", message.getRole().toLowerCase(Locale.ROOT)).put("content", content);
        }
        if (respuestaDeFormulario(threadId) != null) {
            // Sin esto el modelo leía "Giro principal: X" como una propuesta más, contestaba
            // "no se guardará hasta que confirmes" y volvía a enseñar el mismo formulario: un
            // bucle en el que la persona confirmaba una y otra vez y nada se guardaba.
            result.addObject().put("role", "system").put("content", "El ultimo mensaje de la persona RESPONDE"
                    + " al formulario que le mostraste: son valores que ella YA CONFIRMO. Guardalos ahora con la"
                    + " herramienta que corresponde. No le vuelvas a pedir confirmacion ni le muestres el mismo"
                    + " formulario. Si un valor hay que traducirlo al codigo de la herramienta, traducelo y guarda."
                    + " EXCEPCION: un campo brief confirma SOLO las instrucciones literales de una imagen, no guardar marca"
                    + " ni aceptar costes. Conserva su texto exacto, presenta el coste y respeta la confirmacion de generacion.");
        }
        return result;
    }

    /**
     * El mensaje de la persona si responde a un formulario que se le acaba de mostrar; si no, null.
     *
     * <p>Se sabe por estructura, no por lo que diga el texto: el último mensaje es de la persona
     * y el inmediatamente anterior es de Chispa con una tarjeta de formulario.
     */
    private String respuestaDeFormulario(UUID threadId) {
        List<AssistantMessage> all = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
        if (all == null) return null;
        List<AssistantMessage> visibles = all.stream().filter(m -> !"SYSTEM".equals(m.getRole())).toList();
        if (visibles.size() < 2) return null;
        AssistantMessage ultimo = visibles.get(visibles.size() - 1);
        AssistantMessage anterior = visibles.get(visibles.size() - 2);
        if (!"USER".equals(ultimo.getRole()) || !"ASSISTANT".equals(anterior.getRole())) return null;
        if (anterior.getArtifactsJson() == null) return null;
        for (JsonNode artifact : parse(anterior.getArtifactsJson())) {
            if ("form".equals(artifact.path("type").asText())) return ultimo.getContent();
        }
        return null;
    }

    /**
     * La persona ya confirmó en la tarjeta del chat: se le dice a la herramienta.
     *
     * <p>Las herramientas que salen a la calle (publicar_ahora, reintentar_publicacion…) piden su
     * propia confirmación con el parámetro {@code confirmar}: sin él solo devuelven una vista previa.
     * Antes la tarjeta se confirmaba y la herramienta seguía sin enterarse: contestaba "vista previa,
     * no se publicó nada" o pedía una segunda confirmación. Solo se añade si la herramienta lo declara.
     */
    private JsonNode yaConfirmado(JsonNode definition, JsonNode arguments) {
        if (!definition.path("inputSchema").path("properties").has("confirmar") || !arguments.isObject()) {
            return arguments;
        }
        ObjectNode copia = ((ObjectNode) arguments).deepCopy();
        copia.put("confirmar", true);
        return copia;
    }

    /** Herramientas de edicion parcial: su contrato es "solo cambia lo que se manda". */
    private static final Set<String> EDICIONES_PARCIALES = Set.of("actualizar_marca");

    // ---------------------------------------------------------------- vistas y acciones de renglon

    /**
     * Las acciones que un boton de renglon puede pedir directo, con el nombre de su parametro de id.
     *
     * <p>Es una lista cerrada a proposito: el boton manda la herramienta y el id, y nada mas. Lo que
     * no esta aqui no se puede disparar sin pasar por el modelo, y ningun otro argumento viaja.
     */
    private static final java.util.Map<String, String> ACCIONES_DE_RENGLON = java.util.Map.of(
            "aprobar_propuesta", "propuesta_id",
            "descartar_propuesta", "propuesta_id",
            "cancelar_publicacion", "publicacion_id",
            "reintentar_publicacion", "publicacion_id",
            "atender_comentario", "comentario");

    /** Lo que se dice al terminar una accion de renglon, sin gastar una llamada al modelo. */
    private static final java.util.Map<String, String> HECHO_DE_RENGLON = java.util.Map.of(
            "aprobar_propuesta", "Aprobada. Saldra en la fecha que tenia propuesta.",
            "descartar_propuesta", "Descartada. No saldra.",
            "cancelar_publicacion", "Cancelada: volvio a borrador y no saldra.",
            "atender_comentario", "Marcado como atendido.");

    /** Que herramienta dibuja que vista en el chat. */
    private static final java.util.Map<String, String> VISTA_DE_HERRAMIENTA = java.util.Map.of(
            "publicaciones", "calendario",
            "agente_bandeja", "bandeja",
            "comentarios", "comentarios");

    private static final int MAX_RENGLONES = 12;

    /** Las vistas que junto cada turno, para colgarlas de su respuesta final. */
    private final java.util.Map<UUID, ObjectNode> vistasDelTurno = new java.util.concurrent.ConcurrentHashMap<>();

    /** Las tarjetas del mensaje de la persona: lo confirmado en un formulario y la accion de un boton. */
    private String artefactosDelMensaje(Send request) {
        if (request == null) return null;
        ArrayNode cards = json.createArrayNode();
        String formulario = respuestasDelFormulario(request.formAnswers());
        if (formulario != null) parse(formulario).forEach(cards::add);
        ObjectNode accion = accionValida(request.accion());
        if (accion != null) cards.add(accion);
        return cards.isEmpty() ? null : cards.toString();
    }

    /** La accion de un boton, si es una de la lista y trae un id con forma de id; si no, null. */
    private ObjectNode accionValida(JsonNode pedida) {
        if (pedida == null || !pedida.isObject()) return null;
        String herramienta = pedida.path("herramienta").asText("");
        String parametro = ACCIONES_DE_RENGLON.get(herramienta);
        if (parametro == null) return null;
        String id = pedida.path("argumentos").path(parametro).asText("").strip();
        try { UUID.fromString(id); } catch (IllegalArgumentException invalido) { return null; }
        ObjectNode accion = json.createObjectNode().put("type", "accion").put("herramienta", herramienta);
        ObjectNode argumentos = accion.putObject("argumentos");
        argumentos.put(parametro, id);
        // En la bandeja una propuesta puede ir a varias cuentas: el boton dice cual.
        String cuenta = pedida.path("argumentos").path("cuenta_id").asText("").strip();
        if (!cuenta.isEmpty() && "aprobar_propuesta".equals(herramienta)) {
            try {
                UUID.fromString(cuenta);
                argumentos.put("cuenta_id", cuenta);
            } catch (IllegalArgumentException ignorada) {
                // Sin una cuenta valida se aprueba como venga por defecto.
            }
        }
        return accion;
    }

    /** La accion de boton del ultimo mensaje de la persona, si la trae. */
    private ObjectNode accionDeRenglon(UUID threadId) {
        AssistantMessage ultimo = messages.findByThreadIdOrderByCreatedAtAsc(threadId).stream()
                .filter(m -> !"SYSTEM".equals(m.getRole())).reduce((a, b) -> b).orElse(null);
        if (ultimo == null || !"USER".equals(ultimo.getRole()) || ultimo.getArtifactsJson() == null) return null;
        for (JsonNode artifact : parse(ultimo.getArtifactsJson())) {
            if ("accion".equals(artifact.path("type").asText())) return (ObjectNode) artifact;
        }
        return null;
    }

    /**
     * Ejecuta el boton de un renglon sin pasar por el modelo.
     *
     * <p>Aprobar, descartar, cancelar o dar por atendido desde su propio renglon no necesita que
     * nadie interprete nada: la persona toco el boton de ESA publicacion. Pasar por el modelo eran
     * catorce segundos para algo que el servidor hace en uno. Pero lo que sale a la calle
     * —reintentar una publicacion, nivel 3— se confirma igual que siempre: un clic sin querer no
     * puede publicar. Los permisos y la politica de cada herramienta se aplican como en cualquier turno.
     */
    private void ejecutarAccionDeRenglon(User user, AssistantThread thread, AssistantRun run, JsonNode tools,
            AssistantMcpClient.Session session, ObjectNode accion) {
        String herramienta = accion.path("herramienta").asText();
        JsonNode argumentos = accion.path("argumentos");
        JsonNode definition = findTool(tools, herramienta);
        Policy policy = policy(definition);
        AssistantToolCall call = calls.save(AssistantToolCall.builder().runId(run.getId())
                .toolName(herramienta).riskLevel(policy.riskLevel()).permission(policy.permission())
                .argumentsJson(sanitize(argumentos).toString())
                .status(AssistantToolCallStatus.PROPOSED).createdAt(LocalDateTime.now()).build());
        requirePermission(user, policy.permission());
        AssistantLlmClient.Turn directo = new AssistantLlmClient.Turn("", List.of(), json.createObjectNode(),
                "accion-directa", 0, 0);
        if (policy.riskLevel() >= 3) {
            String rawToken = token();
            call.setStatus(AssistantToolCallStatus.WAITING_CONFIRMATION);
            call.setConfirmationTokenHash(hash(rawToken));
            call.setConfirmationExpiresAt(LocalDateTime.now().plus(15, ChronoUnit.MINUTES));
            calls.save(call);
            run.setStatus(AssistantRunStatus.WAITING_CONFIRMATION);
            runs.save(run);
            messages.save(confirmationMessage(thread.getId(), run.getId(), call, definition, rawToken,
                    policy.confirmation(), "Esto sale publicado. Confirmalo y lo mando."));
            events.emit(run, "confirmation.required", payload("toolCallId", call.getId().toString()));
            return;
        }
        call.setStatus(AssistantToolCallStatus.RUNNING);
        calls.save(call);
        events.emit(run, "tool.started", payload("toolCallId", call.getId().toString()).put("tool", herramienta));
        long started = System.nanoTime();
        JsonNode result = session.call(herramienta, argumentos);
        completeCall(run, call, result, started);
        String texto = call.getStatus() == AssistantToolCallStatus.FAILED
                ? (call.getResultSummary() == null || call.getResultSummary().isBlank()
                        ? "No se pudo hacer. No cambie nada." : call.getResultSummary())
                : HECHO_DE_RENGLON.getOrDefault(herramienta, "Listo.");
        finish(user, thread, run, directo, texto, resultArtifacts(run.getId(), tools));
    }

    /** Guarda la vista que dibuja esta herramienta, si dibuja alguna. */
    private void recordarVista(UUID runId, String herramienta, JsonNode resultado) {
        String vista = VISTA_DE_HERRAMIENTA.get(herramienta);
        if (vista == null) return;
        JsonNode datos = resultado.path("structuredContent");
        ArrayNode items = switch (vista) {
            case "calendario" -> renglonesDePublicaciones(datos.path("publicaciones"), false);
            case "bandeja" -> renglonesDePublicaciones(datos.path("propuestas"), true);
            default -> renglonesDeComentarios(datos.path("comentarios").isArray()
                    ? datos.path("comentarios") : datos.path("content"));
        };
        ObjectNode card = json.createObjectNode().put("type", "vista").put("vista", vista);
        card.set("items", items);
        vistasDelTurno.computeIfAbsent(runId, id -> json.createObjectNode()).set(vista, card);
    }

    /**
     * Cada renglon con lo que hace falta para verlo y actuar: nada de textos completos ni datos
     * internos. Los ids vienen del resultado de nuestra propia API, no del modelo.
     */
    private ArrayNode renglonesDePublicaciones(JsonNode lista, boolean deLaBandeja) {
        ArrayNode items = json.createArrayNode();
        if (!lista.isArray()) return items;
        for (JsonNode p : lista) {
            if (items.size() >= MAX_RENGLONES) break;
            String id = p.path("id").asText("");
            try { UUID.fromString(id); } catch (IllegalArgumentException invalido) { continue; }
            ObjectNode n = items.addObject();
            n.put("id", id);
            n.put("estado", truncate(p.path("estado").asText(""), 30));
            n.put("fecha", truncate(p.path("fecha").asText(""), 40));
            n.put("texto", truncate(p.path("texto").asText(""), 160));
            String miniatura = p.path("miniatura").asText("");
            if (miniatura.startsWith("https://")) n.put("miniatura", miniatura);
            ArrayNode redes = n.putArray("redes");
            for (JsonNode r : p.path("redes")) {
                ObjectNode red = redes.addObject().put("red", truncate(r.path("red").asText(""), 20))
                        .put("estado", truncate(r.path("estado").asText(""), 20));
                String error = r.path("error").asText("");
                if (!error.isBlank()) red.put("error", truncate(error, 160));
            }
            if (deLaBandeja) {
                n.put("puedeAprobar", p.path("puedeAprobar").asBoolean(true));
                n.put("puedeDescartar", p.path("puedeDescartar").asBoolean(true));
                String cuenta = p.path("cuentaId").asText("");
                if (!cuenta.isBlank()) n.put("cuentaId", cuenta);
            }
        }
        return items;
    }

    private ArrayNode renglonesDeComentarios(JsonNode lista) {
        ArrayNode items = json.createArrayNode();
        if (!lista.isArray()) return items;
        for (JsonNode c : lista) {
            if (items.size() >= MAX_RENGLONES) break;
            String id = c.path("id").asText("");
            try { UUID.fromString(id); } catch (IllegalArgumentException invalido) { continue; }
            ObjectNode n = items.addObject();
            n.put("id", id);
            n.put("autor", truncate(c.path("autorNombre").asText("Alguien"), 60));
            n.put("red", truncate(c.path("red").asText(""), 20));
            n.put("texto", truncate(c.path("texto").asText(""), 220));
            n.put("fecha", truncate(c.path("escritoEn").asText(""), 40));
            String sobre = c.path("publicacion").path("caption").asText("");
            if (!sobre.isBlank()) n.put("sobre", truncate(sobre, 60));
        }
        return items;
    }

    /** Cuelga de la respuesta final las vistas que junto el turno, delante de las demas tarjetas. */
    private String conVistas(UUID runId, String artifacts) {
        ObjectNode vistas = vistasDelTurno.remove(runId);
        if (vistas == null || vistas.isEmpty()) return artifacts;
        ArrayNode todas = json.createArrayNode();
        vistas.forEach(todas::add);
        if (artifacts != null && !artifacts.isBlank()) parse(artifacts).forEach(todas::add);
        return todas.toString();
    }

    /** Lo confirmado en un formulario, como tarjeta del mensaje de la persona; null si no hay. */
    private String respuestasDelFormulario(java.util.Map<String, String> respuestas) {
        if (respuestas == null || respuestas.isEmpty()) return null;
        ObjectNode valores = json.createObjectNode();
        for (var entrada : respuestas.entrySet()) {
            String campo = entrada.getKey() == null ? "" : entrada.getKey().strip();
            // Solo nombres de parametro: lo que no lo parece no llega a ninguna herramienta.
            if (!campo.matches("[a-z][a-z_]{0,39}") || valores.size() >= MAX_FIELDS) continue;
            String value = entrada.getValue() == null ? "" : entrada.getValue().strip();
            if ("brief".equals(campo) && value.length() > 1500)
                throw new IllegalArgumentException("El prompt admite máximo 1500 caracteres. Ajústalo conservando tus instrucciones importantes.");
            valores.put(campo, truncate(value, "brief".equals(campo) ? 1500 : 1000));
        }
        if (valores.isEmpty()) return null;
        ObjectNode card = json.createObjectNode().put("type", "form_answer");
        card.set("values", valores);
        return json.createArrayNode().add(card).toString();
    }

    /** Lo que confirmo la persona en el formulario al que responde su ultimo mensaje; null si no. */
    private ObjectNode valoresDelFormulario(UUID threadId) {
        if (respuestaDeFormulario(threadId) == null) return null;
        List<AssistantMessage> all = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
        AssistantMessage ultimo = all.stream().filter(m -> "USER".equals(m.getRole()))
                .reduce((a, b) -> b).orElse(null);
        if (ultimo == null || ultimo.getArtifactsJson() == null) return null;
        for (JsonNode artifact : parse(ultimo.getArtifactsJson())) {
            if ("form_answer".equals(artifact.path("type").asText()) && artifact.path("values").isObject()) {
                return (ObjectNode) artifact.path("values");
            }
        }
        return null;
    }

    /**
     * Los argumentos para guardar SOLO lo que la persona confirmo en el formulario.
     *
     * <p>Al responder la historia, el modelo mandaba la marca entera —nombre, giro, ciudad…—,
     * reescribia la historia con sus palabras y rellenaba con "borrar" lo que no sabia. Como eso
     * no era lo confirmado, volvia a pedir confirmacion y no se avanzaba de tema. Aqui se queda
     * con los campos del formulario y nada mas:
     * <ul>
     *   <li>en texto libre van las palabras de la persona, tal cual: gana su version, no la del modelo;</li>
     *   <li>en los de catalogo (objetivo, tono) la persona eligio una etiqueta legible y el codigo lo
     *       traduce el modelo; se acepta su traduccion solo si es un valor valido del catalogo.</li>
     * </ul>
     * Solo para ediciones parciales: en una herramienta que "solo cambia lo que se manda", quitar el
     * resto es exactamente conservarlo. Con esto ya no hace falta otra confirmacion: es justo lo que
     * confirmo. Null si no aplica, y entonces todo sigue como siempre.
     */
    private JsonNode soloLoConfirmado(JsonNode definition, String herramienta, JsonNode delModelo,
            ObjectNode confirmados) {
        if (confirmados == null || !EDICIONES_PARCIALES.contains(herramienta)) return null;
        JsonNode parametros = definition.path("inputSchema").path("properties");
        ObjectNode resultado = json.createObjectNode();
        confirmados.fields().forEachRemaining(entrada -> {
            JsonNode parametro = parametros.path(entrada.getKey());
            if (parametro.isMissingNode()) return;
            boolean deCatalogo = parametro.has("enum") || parametro.path("items").has("enum");
            if (deCatalogo) {
                JsonNode traducido = delModelo == null ? null : delModelo.get(entrada.getKey());
                if (esDelCatalogo(parametro, traducido)) resultado.set(entrada.getKey(), traducido.deepCopy());
            } else if (!entrada.getValue().asText("").isBlank()) {
                resultado.put(entrada.getKey(), entrada.getValue().asText());
            }
        });
        return resultado.isEmpty() ? null : resultado;
    }

    private static boolean esDelCatalogo(JsonNode parametro, JsonNode valor) {
        if (valor == null || valor.isNull()) return false;
        JsonNode opciones = parametro.has("enum") ? parametro.path("enum") : parametro.path("items").path("enum");
        Set<String> validos = new java.util.HashSet<>();
        opciones.forEach(o -> validos.add(o.asText()));
        if (valor.isTextual()) return parametro.has("enum") && validos.contains(valor.asText());
        if (!valor.isArray() || valor.isEmpty()) return false;
        for (JsonNode item : valor) if (!validos.contains(item.asText())) return false;
        return true;
    }

    /**
     * ¿Esta acción solo guarda lo que la persona acaba de confirmar en el formulario?
     *
     * <p>Entonces pedirle otra confirmación es preguntarle dos veces lo mismo —y era lo que
     * alimentaba el bucle—. Pero se acota mucho, porque saltarse una confirmación es serio:
     * <ul>
     *   <li>solo de nivel 2 o menos —editar la marca—; nunca publicar ni responder en publico,
     *       que son nivel 3 y salen a la calle;</li>
     *   <li>cada valor que la acción va a guardar tiene que aparecer TAL CUAL en lo que confirmó.
     *       Un código traducido ("MAS_CLIENTES" por "Conseguir más clientes") no aparece, y
     *       entonces se pide la confirmación de siempre: mejor una de más que una de menos.</li>
     * </ul>
     */
    private boolean confirmadoEnFormulario(Policy policy, JsonNode arguments, String respuesta) {
        if (respuesta == null || respuesta.isBlank() || policy.riskLevel() > 2) return false;
        List<String> valores = new java.util.ArrayList<>();
        if (!valoresDeTexto(arguments, valores) || valores.isEmpty()) return false;
        String confirmado = normalizado(respuesta);
        for (String valor : valores) {
            if (!confirmado.contains(normalizado(valor))) return false;
        }
        return true;
    }

    /** Junta los textos de la acción. Falso si trae algo que no es texto: eso no se puede cotejar. */
    private static boolean valoresDeTexto(JsonNode node, List<String> found) {
        if (node == null || node.isNull() || node.isMissingNode()) return true;
        if (node.isTextual()) {
            String v = node.asText().strip();
            if (!v.isEmpty()) found.add(v);
            return true;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) if (!valoresDeTexto(child, found)) return false;
            return true;
        }
        return false;
    }

    private static String normalizado(String texto) {
        return java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /**
     * Los adjuntos guardan tambien su direccion para que el chat los enseñe, pero al
     * modelo solo le sirve el id: las direcciones son largas, gastan contexto y lo
     * tientan a usarlas en vez de la herramienta que inspecciona el archivo.
     */
    private String forModel(String attachments) {
        ArrayNode lean = json.createArrayNode();
        for (JsonNode item : parse(attachments)) {
            ObjectNode copy = lean.addObject();
            copy.put("id", item.path("id").asText());
            copy.put("name", item.path("name").asText());
            copy.put("type", item.path("type").asText());
        }
        return lean.isEmpty() ? attachments : lean.toString();
    }

    private String validateAttachments(User user, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return null;
        if (ids.size() > 10) throw new IllegalArgumentException("Puedes adjuntar hasta 10 archivos por mensaje.");
        ArrayNode result = json.createArrayNode();
        for (UUID id : ids.stream().distinct().toList()) {
            MediaAsset asset = media.findById(id)
                    .filter(item -> item.getStatus() == MediaAssetStatus.READY)
                    .filter(item -> user.getWorkspace() != null
                            && user.getWorkspace().getId().toString().equals(item.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Uno de los archivos no esta disponible."));
            ObjectNode item = result.addObject();
            item.put("id", asset.getId().toString());
            item.put("name", asset.getFileName());
            item.put("type", asset.getType() == null ? "" : asset.getType().name());
            item.put("sizeBytes", asset.getSizeBytes() == null ? 0 : asset.getSizeBytes());
            // Para que el chat pueda enseñar lo que la persona subio, sin volver a pedirlo.
            item.put("url", asset.getUrl() == null ? "" : asset.getUrl());
            item.put("thumbnailUrl", asset.getThumbnailUrl() == null ? "" : asset.getThumbnailUrl());
        }
        return result.toString();
    }

    private JsonNode findTool(JsonNode tools, String name) {
        if (tools != null) for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) return tool;
        }
        throw new IllegalArgumentException("El modelo solicito una herramienta que no esta disponible.");
    }

    private Policy policy(JsonNode tool) {
        JsonNode source = tool.path("_meta").path("picale/policy");
        if (!source.isObject()) throw new IllegalStateException("Una herramienta MCP no declaro su politica.");
        return new Policy(source.path("riskLevel").asInt(3), source.path("permission").asText("UNSPECIFIED"),
                source.path("confirmation").asText("explicit"));
    }

    private void requirePermission(User user, String permission) {
        if (permission == null || permission.equals("MEMBERSHIP")) return;
        try {
            if (!permissions.permisosDe(user).contains(Permission.valueOf(permission))) {
                throw new SecurityException("No tienes el permiso necesario para ejecutar esa accion.");
            }
        } catch (IllegalArgumentException e) {
            throw new SecurityException("La herramienta exige un permiso que el sistema no reconoce.");
        }
    }

    private AssistantMessage confirmationMessage(UUID threadId, UUID runId, AssistantToolCall call,
            JsonNode definition, String rawToken, String confirmation, String said) {
        ObjectNode card = json.createObjectNode();
        card.put("type", "confirmation");
        card.put("runId", runId.toString());
        card.put("toolCallId", call.getId().toString());
        card.put("riskLevel", call.getRiskLevel());
        card.put("confirmation", confirmation);
        card.put("title", confirmation.equals("cost_notice") ? "Esta accion puede consumir creditos"
                : "Confirma antes de continuar");
        // La descripcion del catalogo esta escrita para el modelo: nombra herramientas,
        // rutas y formatos internos. Lo que la persona necesita leer es lo que el
        // asistente dijo que iba a hacer, que va en la burbuja de arriba.
        int quotedCost = parse(call.getArgumentsJson()).path("_picaleQuotedCredits").asInt(-1);
        JsonNode arguments = sanitize(executableArguments(parse(call.getArgumentsJson())));
        card.set("arguments", arguments);
        ArrayNode media = resolveMedia(arguments);
        if (!media.isEmpty()) card.set("media", media);
        ArrayNode cards = json.createArrayNode().add(card);
        // Si el modelo ya explico que propone, esa frase va en la burbuja: repetir el titulo
        // de la tarjeta justo encima de la tarjeta no le dice nada nuevo a nadie.
        String content = said == null || said.isBlank()
                ? "Preparé esto y espero tu visto bueno."
                : said.strip();
        if ("generar_contenido".equals(call.getToolName()) && quotedCost >= 0) {
            card.put("title", "Generar esta pieza: " + quotedCost + " créditos de tu cuenta");
            content = "Preparé la solicitud. Generarla consume " + quotedCost
                    + " créditos de tu cuenta según el coste vigente. Generar no publica ni programa contenido. ¿La genero?";
        }
        return AssistantMessage.builder().threadId(threadId).runId(runId).role("ASSISTANT")
                .content(content).artifactsJson(cards.toString()).createdAt(LocalDateTime.now()).build();
    }

    /**
     * Cambia los identificadores de archivo de una accion por la foto que nombran.
     *
     * <p>El modelo solo conoce un archivo por su id, asi que lo escribe tal cual y la
     * persona acaba leyendo un UUID donde deberia ver su foto. Aqui se resuelve contra
     * el repositorio, que es quien sabe donde vive.
     *
     * <p>Un id que viene del modelo no es de fiar, pero no hace falta comprobar a quien
     * pertenece: Hibernate filtra por tenant solo, asi que un id de otro espacio
     * simplemente no se encuentra. Lo que no resuelva se ignora en silencio.
     */
    private ArrayNode resolveMedia(JsonNode arguments) {
        ArrayNode result = json.createArrayNode();
        Set<String> seen = new java.util.LinkedHashSet<>();
        collectIds(arguments, seen);
        for (String id : seen) {
            if (result.size() >= 10) break;
            try {
                media.findById(UUID.fromString(id))
                        .ifPresent(asset -> {
                            ObjectNode item = result.addObject();
                            item.put("id", asset.getId().toString());
                            item.put("name", asset.getFileName() == null ? "Archivo" : asset.getFileName());
                            item.put("url", asset.getUrl() == null ? "" : asset.getUrl());
                            item.put("thumbnailUrl", asset.getThumbnailUrl() == null ? "" : asset.getThumbnailUrl());
                        });
            } catch (RuntimeException e) {
                log.debug("No se pudo resolver el medio {} de una accion", id, e);
            }
        }
        return result;
    }

    /** Recoge cualquier texto con forma de identificador, este donde este en la accion. */
    private void collectIds(JsonNode node, Set<String> found) {
        if (node == null) return;
        if (node.isTextual()) {
            String value = node.asText().strip();
            if (value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) found.add(value);
            return;
        }
        node.forEach(child -> collectIds(child, found));
    }

    private String resultArtifacts(UUID runId, JsonNode tools) {
        ArrayNode artifacts = json.createArrayNode();
        for (AssistantToolCall call : calls.findByRunIdOrderByCreatedAtAsc(runId)) {
            if (call.getStatus() != AssistantToolCallStatus.COMPLETED) continue;
            // Las consultas de lectura alimentan la respuesta redactada por el asistente.
            // Mostrar además su recibo técnico duplica la información y expone el formato MCP.
            if (call.getRiskLevel() == 0) continue;
            if ("generar_contenido".equals(call.getToolName())) continue; // Tiene su propia tarjeta de trabajo visual.
            JsonNode definition = findTool(tools, call.getToolName());
            ObjectNode card = artifacts.addObject();
            card.put("type", "result");
            card.put("toolCallId", call.getId().toString());
            // El nombre corto de la accion ("Actualizar la marca"). La descripcion es para el
            // modelo —traia instrucciones como "manda la palabra 'borrar'"— y no se le enseña a nadie.
            String nombre = definition.path("title").asText("");
            card.put("title", nombre.isBlank() ? "Resultado verificado" : nombre);
            card.put("summary", call.getResultSummary() == null ? "Accion completada." : call.getResultSummary());
            card.put("riskLevel", call.getRiskLevel());
            card.put("status", "completed");
        }
        return artifacts.isEmpty() ? null : artifacts.toString();
    }

    private ObjectNode assistantToolRequest(AssistantToolCall call) {
        ObjectNode message = json.createObjectNode();
        message.put("role", "assistant");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode requested = toolCalls.addObject();
        requested.put("id", call.getId().toString());
        requested.put("type", "function");
        ObjectNode function = requested.putObject("function");
        function.put("name", call.getToolName());
        function.put("arguments", executableArguments(parse(call.getArgumentsJson())).toString());
        return message;
    }

    private ObjectNode executableArguments(JsonNode arguments) {
        ObjectNode copy = arguments != null && arguments.isObject() ? arguments.deepCopy() : json.createObjectNode();
        copy.remove("_picaleQuotedCredits");
        return copy;
    }

    private ObjectNode toolResult(AssistantToolCall call, JsonNode result) {
        return toolResult(call.getId().toString(), result);
    }

    private ObjectNode toolResult(String providerCallId, JsonNode result) {
        return AssistantToolResult.message(json, providerCallId, result);
    }

    private void completeCall(AssistantRun run, AssistantToolCall call, JsonNode result, long started) {
        call.setDurationMs((System.nanoTime() - started) / 1_000_000);
        JsonNode safeResult = sanitize(result);
        boolean failed = safeResult.path("isError").asBoolean(false);
        call.setStatus(failed ? AssistantToolCallStatus.FAILED : AssistantToolCallStatus.COMPLETED);
        call.setResultSummary(truncate(resultSummary(safeResult), 1000));
        calls.save(call);
        saveGenerationReceipt(run, call, safeResult, failed);
        rememberVerified(run, call, failed);
        if (!failed) recordarVista(run.getId(), call.getToolName(), safeResult);
        events.emit(run, "tool.completed", payload("toolCallId", call.getId().toString()));
    }

    /** Guarda resultados verificables aunque el modelo no llegue a redactar su respuesta final. */
    private void saveGenerationReceipt(AssistantRun run, AssistantToolCall call, JsonNode result, boolean failed) {
        boolean starting = "generar_contenido".equals(call.getToolName());
        if (!starting && !"estado_generacion".equals(call.getToolName())) return;
        JsonNode data = result.path("structuredContent");
        String jobId = starting ? data.path("trabajo_id").asText("")
                : parse(call.getArgumentsJson()).path("trabajo_id").asText("");
        try { UUID.fromString(jobId); } catch (IllegalArgumentException invalid) { return; }
        if (starting && failed) return;
        String state = failed ? ("FAILED".equals(data.path("estado").asText()) ? "FAILED" : "UNAVAILABLE")
                : starting ? "QUEUED" : data.path("estado").asText("UNKNOWN");
        if (!Set.of("QUEUED", "THINKING", "CREATING", "READY", "FAILED", "UNAVAILABLE", "UNKNOWN").contains(state)) {
            state = "UNKNOWN";
        }
        ObjectNode card = json.createObjectNode().put("type", "generation").put("jobId", jobId).put("status", state);
        ArrayNode versions = card.putArray("versions");
        // Solo IDs resueltos y pertenecientes al workspace; nunca URLs inventadas por el modelo.
        if (!failed) for (JsonNode version : data.path("versiones")) {
            if (versions.size() >= 6) break;
            ObjectNode gallery = json.createObjectNode().put("ratio", truncate(version.path("proporcion").asText(""), 20))
                    .put("status", truncate(version.path("estado").asText(""), 30));
            if (version.path("revision_visual").isObject()) gallery.set("review", sanitize(version.path("revision_visual")));
            ArrayNode items = gallery.putArray("items");
            Set<UUID> seen = new java.util.LinkedHashSet<>();
            for (JsonNode id : version.path("archivo_ids")) {
                if (items.size() >= 6) break;
                try {
                    UUID assetId = UUID.fromString(id.asText());
                    if (!seen.add(assetId)) continue;
                    media.findById(assetId)
                            .filter(asset -> asset.getStatus() == MediaAssetStatus.READY)
                            .filter(asset -> asset.getType() == com.metricol.api.enums.MediaType.IMAGE)
                            .filter(asset -> run.getWorkspaceId().toString().equals(asset.getTenantId()))
                            .filter(asset -> asset.getUrl() != null && !asset.getUrl().isBlank())
                            .ifPresent(asset -> items.addObject().put("id", assetId.toString())
                                    .put("name", asset.getFileName() == null ? "Imagen generada" : asset.getFileName())
                                    .put("url", asset.getUrl()).put("thumbnailUrl", asset.getThumbnailUrl()));
                } catch (IllegalArgumentException invalid) { /* El resultado externo no es una autoridad de acceso. */ }
            }
            gallery.put("complete", "READY".equals(version.path("estado").asText())
                    && version.path("imagenes").isArray() && items.size() == version.path("imagenes").size());
            if (!items.isEmpty()) versions.add(gallery);
        }
        String content = switch (state) {
            case "READY" -> "La generación terminó. Revisa las piezas disponibles antes de usarlas.";
            case "FAILED" -> "No se pudo completar la generación.";
            case "UNAVAILABLE", "UNKNOWN" -> "No pude verificar el trabajo. Revisa Contenido antes de generar otra vez.";
            default -> "La generación está en proceso. Puedes consultar este mismo trabajo sin generar otra vez.";
        };
        AssistantMessage receipt = messages.findByThreadIdOrderByCreatedAtAsc(run.getThreadId()).stream()
                .filter(message -> message.getArtifactsJson() != null)
                .filter(message -> {
                    for (JsonNode artifact : parse(message.getArtifactsJson())) {
                        if ("generation".equals(artifact.path("type").asText())
                                && jobId.equals(artifact.path("jobId").asText())) return true;
                    }
                    return false;
                }).findFirst().orElse(null);
        // Un trabajo expirado no borra la galería que ya recuperamos. Cada trabajo tiene una sola tarjeta.
        if (receipt != null && versions.isEmpty()) {
            for (JsonNode previous : parse(receipt.getArtifactsJson())) {
                if ("READY".equals(previous.path("status").asText()) && !previous.path("versions").isEmpty()) return;
            }
        }
        if (receipt == null) receipt = AssistantMessage.builder().threadId(run.getThreadId()).runId(run.getId())
                .role("ASSISTANT").createdAt(LocalDateTime.now()).build();
        receipt.setContent(content);
        receipt.setArtifactsJson(json.createArrayNode().add(card).toString());
        messages.save(receipt);
    }

    /**
     * Conserva extractos de hechos e intentos realmente observados por el MCP.
     *
     * <p>No se guarda una "opinion" del modelo: la memoria sale del recibo sanitizado,
     * lleva fecha y se acota para que una conversacion de meses no infle el contexto.
     */
    private void rememberVerified(AssistantRun run, AssistantToolCall call, boolean failed) {
        if (call.getResultSummary() == null || call.getResultSummary().isBlank()) return;
        threads.findById(run.getThreadId()).ifPresent(thread -> {
            String kind = failed ? "Intento que no se completo" : call.getRiskLevel() > 0
                    ? "Accion realizada" : "Dato consultado";
            String fact = truncate(call.getResultSummary().replaceAll("\\s+", " ").strip(), 700);
            String line = "- [" + LocalDateTime.now().toLocalDate() + "] " + kind + ": " + fact;
            String previous = thread.getSummary() == null ? "" : thread.getSummary().strip();
            String next = previous.isEmpty() ? line : previous + "\n" + line;
            if (next.length() > MAX_VERIFIED_MEMORY) {
                next = next.substring(next.length() - MAX_VERIFIED_MEMORY);
                int firstLine = next.indexOf('\n');
                if (firstLine >= 0) next = next.substring(firstLine + 1);
            }
            thread.setSummary(next);
            threads.save(thread);
        });
    }

    /** Convierte la envoltura MCP en texto legible para recibos y auditoría. */
    private String resultSummary(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                String value = item.path("text").asText("").strip();
                if (value.isEmpty()) continue;
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
            if (!text.isEmpty()) return text.toString();
        }
        return result.toString();
    }

    private void fail(AssistantRun run, String safeMessage) {
        fail(run, safeMessage, null);
    }

    /**
     * El texto que ve la persona nunca lleva detalles tecnicos, asi que la causa real solo
     * sobrevive si queda en el log: sin esto un turno fallido es imposible de diagnosticar.
     */
    private void fail(AssistantRun run, String safeMessage, Throwable cause) {
        readCaches.remove(run.getId());
        vistasDelTurno.remove(run.getId());
        if (cause != null) {
            log.error("Turno del asistente fallido. run={} thread={} workspace={} modelo={}",
                    run.getId(), run.getThreadId(), run.getWorkspaceId(), run.getModel(), cause);
        }
        run.setStatus(AssistantRunStatus.FAILED);
        run.setErrorCode("ASSISTANT_RUN_FAILED");
        run.setErrorMessage(safeMessage);
        run.setFinishedAt(LocalDateTime.now());
        runs.save(run);
        messages.save(AssistantMessage.builder().threadId(run.getThreadId()).runId(run.getId()).role("ASSISTANT")
                .content(safeMessage).createdAt(LocalDateTime.now()).build());
        events.emit(run, "run.failed", payload("message", safeMessage));
    }

    private AssistantRun ownedRun(User user, UUID runId) {
        UUID workspaceId = user == null || user.getWorkspace() == null ? null : user.getWorkspace().getId();
        return runs.findById(runId).filter(run -> workspaceId != null && workspaceId.equals(run.getWorkspaceId())
                        && user.getId().equals(run.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Esa ejecucion no esta disponible."));
    }

    private AssistantRun ownedRunLocked(User user, UUID runId) {
        UUID workspaceId = user == null || user.getWorkspace() == null ? null : user.getWorkspace().getId();
        return runs.findLockedById(runId).filter(run -> workspaceId != null && workspaceId.equals(run.getWorkspaceId())
                        && user.getId().equals(run.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Esa ejecucion no esta disponible."));
    }

    private void updateThread(AssistantThread thread, String content, LocalDateTime now) {
        if (AssistantThreads.NUEVA.equals(thread.getTitle())) {
            thread.setTitle(truncate(content.replaceAll("\\s+", " ").strip(), AssistantThreads.MAX_TITULO));
        }
        thread.setUpdatedAt(now);
        threads.save(thread);
    }

    private JsonNode sanitize(JsonNode source) {
        JsonNode copy = source == null ? json.createObjectNode() : source.deepCopy();
        redact(copy);
        return copy;
    }

    private void redact(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                if (key.contains("secret") || key.contains("password") || key.contains("token")
                        || key.contains("authorization")) {
                    ((ObjectNode) node).put(field.getKey(), "[REDACTED]");
                } else if (field.getValue().isTextual() && looksSigned(field.getValue().asText())) {
                    ((ObjectNode) node).put(field.getKey(), "[SIGNED_URL_REDACTED]");
                } else redact(field.getValue());
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                if (node.get(index).isTextual() && looksSigned(node.get(index).asText())) {
                    ((ArrayNode) node).set(index, json.getNodeFactory().textNode("[SIGNED_URL_REDACTED]"));
                } else redact(node.get(index));
            }
        }
    }

    private static boolean looksSigned(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("x-amz-") || lower.contains("x-goog-signature=")
                || lower.contains("signature=") || lower.contains("access_token=");
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (Exception e) { return json.createObjectNode(); }
    }

    private ObjectNode payload(String key, String value) {
        return json.createObjectNode().put(key, value);
    }

    private static String cleanContent(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank()) throw new IllegalArgumentException("Escribe un mensaje para continuar.");
        if (result.length() > AssistantMessage.MAX_CONTENT) {
            throw new IllegalArgumentException("El mensaje es demasiado largo.");
        }
        return result;
    }

    private static String cleanKey(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank() || result.length() > 100 || !result.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("Falta una clave de idempotencia valida.");
        }
        return result;
    }

    private static String cleanPromptName(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (clean.length() > 100 || !clean.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Ese atajo del asistente no existe.");
        }
        return clean;
    }

    private static String cleanPath(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replaceAll("[\\p{Cntrl}]", "").strip();
        return truncate(clean, 300);
    }

    private static String safeError(Throwable error) {
        if (error instanceof AssistantExecutionBudget.Expired) return error.getMessage();
        if (error instanceof IllegalArgumentException || error instanceof SecurityException
                || error instanceof AssistantMcpClient.McpException
                || error instanceof ResourceNotFoundException) {
            return truncate(error.getMessage(), 500);
        }
        // Que el servicio del modelo se caiga no es lo mismo que un error de la cuenta:
        // decirlo por su nombre evita que la persona crea que hizo algo mal.
        // Un 4xx (salvo 429) no es que el servicio se cayera: es que le mandamos algo mal
        // armado, y reintentar da el mismo error. Decirle "vuelve a intentarlo" la dejaba
        // reintentando en un bucle sin salida.
        if (error instanceof org.springframework.web.client.HttpClientErrorException http
                && http.getStatusCode().value() != 429) {
            return "Tuve un problema de mi lado al preparar tu respuesta y quedó registrado para revisarlo. "
                    + "Revisa los resultados de las acciones antes de repetirlas.";
        }
        if (error instanceof RestClientException) {
            return "No pude completar la conexión para preparar tu respuesta. "
                    + "Si había una acción en curso, revisa su estado antes de repetirla.";
        }
        return "No pude completar esta solicitud. Revisa el estado de las acciones iniciadas antes de repetirlas.";
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo proteger la confirmacion.", e);
        }
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record Policy(int riskLevel, String permission, String confirmation) {
        boolean requiresConfirmation() {
            return riskLevel > 0 || !"none".equals(confirmation);
        }
    }
}
