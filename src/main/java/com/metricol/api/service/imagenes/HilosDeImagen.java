package com.metricol.api.service.imagenes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.HiloDeImagen;
import com.metricol.api.entity.MensajeDeImagen;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.HiloDeImagenRepository;
import com.metricol.api.repository.MensajeDeImagenRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.service.agente.RevisorDeMarca;
import com.metricol.api.service.ai.MarcaDelNegocio;
import com.metricol.api.service.campaign.ContenidoJobs;

/**
 * La conversación para crear una imagen: la abre, la guarda y la hace avanzar.
 *
 * <p>Lo que decide si esto vale la pena no es el modelo, es lo que se le pone
 * delante: la marca del negocio y lo que ya se entendió. Eso se arma aquí.
 *
 * <p>Conversar no cuesta créditos. Lo que se cobra —al crear la imagen— viene
 * después y lo hace quien ya sabe cobrarlo; aquí solo se junta el contexto.
 */
@Service
public class HilosDeImagen {

    private static final Logger log = LoggerFactory.getLogger(HilosDeImagen.class);

    /** Lo primero que ve la persona al abrir. Sin preguntas: una invitación. */
    static final String SALUDO =
            "Cuéntame qué quieres anunciar y te la preparo. Puedes dictarlo si prefieres.";

    /**
     * Ejemplos que se tocan, para quien abre y no sabe qué se espera de él.
     *
     * <p><b>Es el punto donde se abandona.</b> Un cuadro de texto vacío
     * delante de alguien que nunca ha pedido una imagen no se contesta: se
     * cierra. Tres ejemplos tocados con el dedo arrancan la conversación, y a
     * partir del primer turno el asistente ya propone solo.
     */
    static final List<String> PARA_EMPEZAR = List.of(
            "Una promoción", "Un producto", "Que abrimos hoy");

    private final HiloDeImagenRepository hilos;
    private final MensajeDeImagenRepository mensajes;
    private final SocialAccountRepository cuentas;
    private final AsistenteDeImagenes asistente;
    private final ContenidoJobs trabajos;
    private final ObjectMapper mapper = new ObjectMapper();

    public HilosDeImagen(HiloDeImagenRepository hilos, MensajeDeImagenRepository mensajes,
            SocialAccountRepository cuentas, AsistenteDeImagenes asistente, ContenidoJobs trabajos) {
        this.hilos = hilos;
        this.mensajes = mensajes;
        this.cuentas = cuentas;
        this.asistente = asistente;
        this.trabajos = trabajos;
    }

    /** Lo que la pantalla necesita para pintar un turno. */
    public record Vista(HiloDeImagen hilo, FichaDeImagen ficha, List<MensajeDeImagen> mensajes,
            List<String> opciones, boolean listoParaCrear) {
    }

    /**
     * Abre una conversación, o devuelve la que estuviera abierta.
     *
     * <p>Se reusa la abierta a propósito: quien cierra la app a media
     * conversación y vuelve espera encontrarla donde la dejó, no empezar de
     * cero y volver a contarlo todo.
     */
    @Transactional
    public Vista abrir(User user) {
        UUID espacio = espacioDe(user);
        HiloDeImagen hilo = hilos.ultimoAbierto(espacio, user.getId()).orElse(null);
        if (hilo == null) {
            LocalDateTime ahora = LocalDateTime.now();
            hilo = hilos.save(HiloDeImagen.builder()
                    .workspaceId(espacio)
                    .userId(user.getId())
                    .ficha(aJson(FichaDeImagen.vacia()))
                    .creadoEn(ahora)
                    .actualizadoEn(ahora)
                    .build());
            guardar(hilo.getId(), "ASISTENTE", SALUDO, PARA_EMPEZAR, null, null);
            return vista(hilo, PARA_EMPEZAR, false);
        }
        return vista(hilo, ultimasOpciones(hilo.getId()), false);
    }

    /** Empezar de nuevo: cierra lo que hubiera y abre limpio. */
    @Transactional
    public Vista empezarDeNuevo(User user) {
        UUID espacio = espacioDe(user);
        hilos.ultimoAbierto(espacio, user.getId()).ifPresent(h -> {
            h.setCerradoEn(LocalDateTime.now());
            hilos.save(h);
        });
        return abrir(user);
    }

    @Transactional(readOnly = true)
    public Vista ver(User user, UUID hiloId) {
        HiloDeImagen hilo = mio(user, hiloId);
        return vista(hilo, ultimasOpciones(hilo.getId()), leer(hilo).completa());
    }

    /**
     * Un turno: lo que dijo la persona, y lo que contesta el asistente.
     *
     * <p>Las dos cosas se guardan aunque el filtro no deje pasar lo pedido:
     * perder lo que alguien acaba de escribir —aunque no se pudiera hacer— es
     * la forma más rápida de que no lo vuelva a intentar.
     */
    @Transactional
    public Vista hablar(User user, UUID hiloId, String texto, List<String> fotos) {
        HiloDeImagen hilo = mio(user, hiloId);
        String dicho = texto == null ? "" : texto.strip();
        if (dicho.length() > MensajeDeImagen.MAX_TEXTO) {
            dicho = dicho.substring(0, MensajeDeImagen.MAX_TEXTO);
        }
        if (dicho.isBlank() && (fotos == null || fotos.isEmpty())) {
            throw new IllegalArgumentException("Escribe o dicta lo que quieres crear.");
        }

        FichaDeImagen ficha = leer(hilo).conFotos(fotos);
        // Las fotos que mandó quedan EN su turno: decir "te mando una foto" sin
        // enseñarla deja a la persona sin saber cuál mandó, y al volver al chat
        // de ayer no queda rastro de con qué se trabajó.
        guardar(hilo.getId(), "PERSONA", dicho, List.of(), null, null,
                fotos == null ? List.of() : fotos);

        Workspace espacio = user.getWorkspace();
        AsistenteDeImagenes.Respuesta r = asistente.hablar(
                dicho.isBlank() ? "(mandó fotos)" : dicho,
                ficha,
                historial(hilo.getId()),
                MarcaDelNegocio.delEspacio(espacio),
                describir(espacio),
                redesConectadas());

        guardar(hilo.getId(), "ASISTENTE", r.mensaje(), r.opciones(),
                r.veredicto() == null ? null : r.veredicto().name(), r.motivo());

        // La ficha solo avanza con lo que se puede hacer: si lo pedido se
        // descartó, no tiene por qué quedar apuntado como si fuera a salir.
        hilo.setFicha(aJson(r.sePuedeSeguir() ? r.ficha() : ficha));
        hilo.setActualizadoEn(LocalDateTime.now());
        hilos.save(hilo);

        return vista(hilo, r.opciones(), r.listo());
    }

    // ------------------------------------------------------------- crear

    /**
     * Crea de verdad. <b>Es el único sitio del asistente donde se gasta
     * dinero.</b>
     *
     * <p>Se exige que la ficha esté completa antes de arrancar: crear a medias
     * gasta créditos para tirar el resultado, y es el error que de verdad
     * enoja. Quien llama ya le avisó a la persona lo que iba a costar.
     *
     * <p>Devuelve el identificador del trabajo; crear tarda más de lo que
     * aguanta una petición web, así que la pantalla lo sondea igual que hoy.
     */
    @Transactional
    public String crear(User user, UUID hiloId) {
        HiloDeImagen hilo = mio(user, hiloId);
        FichaDeImagen ficha = leer(hilo);
        if (!ficha.completa()) {
            throw new IllegalArgumentException(
                    "Antes de crearla me falta saber: " + String.join(" y ", ficha.queFalta()) + ".");
        }

        String trabajoId = trabajos.iniciar(user, PeticionDesdeFicha.armar(ficha));

        guardar(hilo.getId(), "ASISTENTE", "Va, la estoy creando. Tarda un momento.", List.of(), null, null);
        // Queda apuntado: si la persona sale, al volver se reanuda.
        hilo.setTrabajoEnCurso(trabajoId);
        hilo.setCreaciones(hilo.getCreaciones() + 1);
        hilo.setActualizadoEn(LocalDateTime.now());
        hilos.save(hilo);
        return trabajoId;
    }

    /**
     * La persona tocó la versión que le gustó: a partir de aquí se afina ESA.
     *
     * <p>Sin esto, "acércame más los tacos" devolvía una imagen distinta y
     * había que volver a explicarlo todo — gastando créditos cada vez.
     */
    @Transactional
    public Vista elegir(User user, UUID hiloId, String url) {
        HiloDeImagen hilo = mio(user, hiloId);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Toca la versión que te gustó.");
        }
        hilo.setFicha(aJson(leer(hilo).conPieza(url)));
        hilo.setActualizadoEn(LocalDateTime.now());
        hilos.save(hilo);

        guardar(hilo.getId(), "ASISTENTE",
                "Me quedo con esa. ¿Le cambiamos algo o así va?",
                List.of("Así va", "Acércala más", "Cambia el texto"), null, null);
        return vista(hilo, List.of("Así va", "Acércala más", "Cambia el texto"), true);
    }

    /**
     * Apunta en la conversación lo que acabó de salir.
     *
     * <p>Lo manda la pantalla cuando el trabajo termina, porque es ella quien
     * lo está sondeando. Con esto, volver a un chat de ayer devuelve también
     * sus imágenes; sin esto la conversación hablaba de cosas que ya no se
     * veían.
     */
    @Transactional
    public Vista apuntarLoQueSalio(User user, UUID hiloId, List<String> urls) {
        HiloDeImagen hilo = mio(user, hiloId);
        if (urls == null || urls.isEmpty()) {
            return vista(hilo, List.of(), false);
        }
        guardar(hilo.getId(), "ASISTENTE", "Esto es lo que salió. Tócala para verla en grande.",
                List.of(), null, null, urls);
        // La última creada queda puesta SOLA para seguir afinándola. Antes
        // había que tocarla para elegirla, y eso convertía en obligatorio un
        // paso que nadie pidió: lo normal es querer ajustar lo que acabas de
        // ver. Si quieres otra, la abres y dices que te quedas con esa.
        hilo.setFicha(aJson(leer(hilo).conPieza(urls.get(0))));
        hilo.setTrabajoEnCurso(null);
        hilo.setActualizadoEn(LocalDateTime.now());
        hilos.save(hilo);
        return vista(hilo, List.of(), false);
    }

    /**
     * Las opciones del último turno del asistente.
     *
     * <p>Al reabrir una conversación guardada, las respuestas que se tocaban
     * tienen que seguir ahí: volver a un chat y encontrar solo un cuadro
     * vacío es perder el hilo de lo que se estaba contestando.
     */
    private List<String> ultimasOpciones(UUID hiloId) {
        List<MensajeDeImagen> todos = mensajes.findByHiloIdOrderByCreadoEnAsc(hiloId);
        for (int i = todos.size() - 1; i >= 0; i--) {
            MensajeDeImagen m = todos.get(i);
            if ("ASISTENTE".equals(m.getRol()) && m.getOpciones() != null) {
                try {
                    return mapper.readValue(m.getOpciones(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.ArrayList<String>>() {
                            });
                } catch (Exception ex) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    /**
     * Todo lo que se creó en esta conversación, de lo más nuevo a lo más viejo.
     *
     * <p>Es lo que deja ver "el contenido de esta charla" sin tener que
     * recorrer los mensajes hacia arriba. Sale de los propios turnos, así que
     * no hay nada que mantener aparte.
     */
    public List<String> contenidosDe(UUID hiloId) {
        List<String> todas = new ArrayList<>();
        List<MensajeDeImagen> turnos = mensajes.findByHiloIdOrderByCreadoEnAsc(hiloId);
        for (int i = turnos.size() - 1; i >= 0; i--) {
            String json = turnos.get(i).getImagenes();
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                for (String url : mapper.readValue(json,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.ArrayList<String>>() {
                        })) {
                    if (url != null && !url.isBlank() && !todas.contains(url)) {
                        todas.add(url);
                    }
                }
            } catch (Exception ex) {
                log.debug("Imágenes ilegibles en un turno de {}: {}", hiloId, ex.toString());
            }
        }
        return todas;
    }

    /** Las conversaciones de la persona, de la más reciente a la más vieja. */
    @Transactional(readOnly = true)
    public List<HiloDeImagen> historial(User user) {
        return hilos.deLaPersona(espacioDe(user), user.getId());
    }

    /**
     * El título de una conversación, para la lista.
     *
     * <p>Sale de lo que se estaba anunciando, que es como la recuerda quien la
     * tuvo. Sin eso, la fecha; nunca un identificador.
     */
    public String tituloDe(HiloDeImagen hilo) {
        FichaDeImagen ficha = leer(hilo);
        if (ficha.queSeAnuncia() != null && !ficha.queSeAnuncia().isBlank()) {
            String t = ficha.queSeAnuncia().strip();
            return t.length() <= 60 ? t : t.substring(0, 60) + "…";
        }
        return "Conversación sin terminar";
    }

    // ------------------------------------------------------------- apoyos

    private List<AsistenteDeImagenes.Turno> historial(UUID hiloId) {
        List<AsistenteDeImagenes.Turno> turnos = new ArrayList<>();
        for (MensajeDeImagen m : mensajes.findByHiloIdOrderByCreadoEnAsc(hiloId)) {
            turnos.add(new AsistenteDeImagenes.Turno("PERSONA".equals(m.getRol()), m.getTexto()));
        }
        return turnos;
    }

    /** Las redes conectadas del espacio: para no preguntar lo que ya se sabe. */
    private List<String> redesConectadas() {
        try {
            return cuentas.findAllByOrderByConnectedAtDesc().stream()
                    .filter(c -> c.getStatus() == SocialAccountStatus.CONNECTED)
                    .map(SocialAccount::getPlatform)
                    .filter(java.util.Objects::nonNull)
                    .map(Enum::name)
                    .distinct()
                    .toList();
        } catch (RuntimeException ex) {
            log.debug("No se pudieron leer las redes conectadas: {}", ex.toString());
            return List.of();
        }
    }

    private static String describir(Workspace w) {
        StringBuilder sb = new StringBuilder();
        if (w.getName() != null) {
            sb.append("- Se llama: ").append(w.getName()).append('\n');
        }
        if (w.getGiro() != null && !w.getGiro().isBlank()) {
            sb.append("- Giro: ").append(w.getGiro()).append('\n');
        }
        if (w.getCiudad() != null && !w.getCiudad().isBlank()) {
            sb.append("- Ciudad: ").append(w.getCiudad()).append('\n');
        }
        return sb.toString();
    }

    private Vista vista(HiloDeImagen hilo, List<String> opciones, boolean listo) {
        return new Vista(hilo, leer(hilo), mensajes.findByHiloIdOrderByCreadoEnAsc(hilo.getId()),
                opciones, listo);
    }

    private void guardar(UUID hiloId, String rol, String texto, List<String> opciones,
            String veredicto, String motivo) {
        guardar(hiloId, rol, texto, opciones, veredicto, motivo, null);
    }

    private void guardar(UUID hiloId, String rol, String texto, List<String> opciones,
            String veredicto, String motivo, List<String> imagenes) {
        mensajes.save(MensajeDeImagen.builder()
                .hiloId(hiloId)
                .rol(rol)
                .texto(texto)
                .opciones(opciones == null || opciones.isEmpty() ? null : aJson(opciones))
                .veredicto(veredicto)
                .motivo(motivo)
                .imagenes(imagenes == null || imagenes.isEmpty() ? null : aJson(imagenes))
                .creadoEn(LocalDateTime.now())
                .build());
    }

    FichaDeImagen leer(HiloDeImagen hilo) {
        if (hilo.getFicha() == null || hilo.getFicha().isBlank()) {
            return FichaDeImagen.vacia();
        }
        try {
            return mapper.readValue(hilo.getFicha(), FichaDeImagen.class);
        } catch (Exception ex) {
            // Una ficha ilegible no puede tumbar la conversación: se empieza
            // la ficha de nuevo y lo dicho sigue ahí para reconstruirla.
            log.warn("Ficha ilegible en el hilo {}: {}", hilo.getId(), ex.toString());
            return FichaDeImagen.vacia();
        }
    }

    private String aJson(Object valor) {
        try {
            return mapper.writeValueAsString(valor);
        } catch (Exception ex) {
            log.warn("No se pudo guardar como JSON: {}", ex.toString());
            return null;
        }
    }

    private HiloDeImagen mio(User user, UUID hiloId) {
        UUID espacio = espacioDe(user);
        return hilos.findById(hiloId)
                .filter(h -> espacio.equals(h.getWorkspaceId()) && user.getId().equals(h.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Esa conversación ya no está."));
    }

    private static UUID espacioDe(User user) {
        if (user == null || user.getWorkspace() == null) {
            throw new IllegalArgumentException("Elige un espacio de trabajo primero.");
        }
        return user.getWorkspace().getId();
    }

    /** Para que las pruebas puedan comprobar los veredictos sin levantar Spring. */
    static boolean descartado(RevisorDeMarca.Veredicto v) {
        return v == RevisorDeMarca.Veredicto.DESCARTADA;
    }
}
