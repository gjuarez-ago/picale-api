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
            guardar(hilo.getId(), "ASISTENTE", SALUDO, List.of(), null, null);
        }
        return vista(hilo, List.of(), false);
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
        return vista(hilo, List.of(), leer(hilo).completa());
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
        guardar(hilo.getId(), "PERSONA", dicho, List.of(), null, null);

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
        mensajes.save(MensajeDeImagen.builder()
                .hiloId(hiloId)
                .rol(rol)
                .texto(texto)
                .opciones(opciones == null || opciones.isEmpty() ? null : aJson(opciones))
                .veredicto(veredicto)
                .motivo(motivo)
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
